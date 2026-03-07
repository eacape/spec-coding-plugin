package com.eacape.speccodingplugin.spec

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Spec 文档存储管理器
 * 负责 Spec 文档的文件系统存储和读取
 */
class SpecStorage(
    project: Project,
    private val atomicFileIO: AtomicFileIO = AtomicFileIO(),
    private val workspaceInitializer: SpecWorkspaceInitializer = SpecWorkspaceInitializer(project),
    private val lockManager: SpecFileLockManager = SpecFileLockManager(workspaceInitializer),
) {
    private val logger = thisLogger()
    private val yamlCodec = SpecYamlCodec

    /**
     * 保存 Spec 文档
     */
    fun saveDocument(workflowId: String, document: SpecDocument): Result<Path> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val workflowDir = workspaceInitializer
                    .initializeWorkflowWorkspace(workflowId)
                    .workflowDir

                val filePath = workflowDir.resolve(document.phase.outputFileName)
                val content = formatDocument(document)

                atomicFileIO.writeString(filePath, content, StandardCharsets.UTF_8)
                val snapshotId = saveDocumentSnapshot(workflowId, document, content)
                appendAuditEntry(
                    eventType = SpecAuditEventType.DOCUMENT_SAVED,
                    workflowId = workflowId,
                    details = mapOf(
                        "phase" to document.phase.name,
                        "snapshotId" to snapshotId,
                        "file" to document.phase.outputFileName,
                    ),
                )
                logger.info("Saved ${document.phase} document to $filePath")

                filePath
            }
        }
    }

    fun listDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
    ): List<SpecDocumentHistoryEntry> {
        val historyDir = getHistoryDirectory(workflowId, phase)
        if (!Files.exists(historyDir)) {
            return emptyList()
        }

        return Files.list(historyDir).use { stream ->
            val entries = mutableListOf<SpecDocumentHistoryEntry>()
            stream.forEach { path ->
                if (!Files.isRegularFile(path)) {
                    return@forEach
                }
                val name = path.fileName.toString()
                if (!name.endsWith(".md")) {
                    return@forEach
                }
                val snapshotId = name.removeSuffix(".md")
                val createdAt = snapshotId.toLongOrNull() ?: return@forEach
                entries.add(
                    SpecDocumentHistoryEntry(
                        snapshotId = snapshotId,
                        phase = phase,
                        createdAt = createdAt,
                    )
                )
            }
            entries.sortedByDescending { it.createdAt }
        }
    }

    fun loadDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<SpecDocument> {
        return runCatching {
            val snapshotPath = getHistoryDirectory(workflowId, phase).resolve("$snapshotId.md")
            if (!Files.exists(snapshotPath)) {
                throw IllegalStateException("Document snapshot not found: $snapshotPath")
            }

            val content = Files.readString(snapshotPath, StandardCharsets.UTF_8)
            parseDocument(workflowId, phase, content)
        }
    }

    fun deleteDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<Unit> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val snapshotPath = getHistoryDirectory(workflowId, phase).resolve("$snapshotId.md")
                if (!Files.exists(snapshotPath)) {
                    throw IllegalStateException("Document snapshot not found: $snapshotPath")
                }
                Files.delete(snapshotPath)
                cleanupIfEmpty(getHistoryDirectory(workflowId, phase))
                appendAuditEntry(
                    eventType = SpecAuditEventType.SNAPSHOT_DELETED,
                    workflowId = workflowId,
                    details = mapOf(
                        "phase" to phase.name,
                        "snapshotId" to snapshotId,
                    ),
                )
            }
        }
    }

    fun pruneDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
        keepLatest: Int,
    ): Result<Int> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                require(keepLatest >= 0) { "keepLatest must be >= 0" }
                val history = listDocumentHistory(workflowId, phase)
                val toDelete = history.drop(keepLatest)
                toDelete.forEach { entry ->
                    val path = getHistoryDirectory(workflowId, phase).resolve("${entry.snapshotId}.md")
                    if (Files.exists(path)) {
                        Files.delete(path)
                    }
                }
                cleanupIfEmpty(getHistoryDirectory(workflowId, phase))
                appendAuditEntry(
                    eventType = SpecAuditEventType.HISTORY_PRUNED,
                    workflowId = workflowId,
                    details = mapOf(
                        "phase" to phase.name,
                        "keepLatest" to keepLatest.toString(),
                        "pruned" to toDelete.size.toString(),
                    ),
                )
                toDelete.size
            }
        }
    }

    /**
     * 加载 Spec 文档
     */
    fun loadDocument(workflowId: String, phase: SpecPhase): Result<SpecDocument> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            val filePath = workflowDir.resolve(phase.outputFileName)

            if (!Files.exists(filePath)) {
                throw IllegalStateException("Document not found: $filePath")
            }

            val content = Files.readString(filePath, StandardCharsets.UTF_8)
            parseDocument(workflowId, phase, content)
        }
    }

    /**
     * 保存工作流状态
     */
    fun saveWorkflow(workflow: SpecWorkflow): Result<Path> {
        return runCatching {
            lockManager.withWorkflowLock(workflow.id) {
                val workflowDir = workspaceInitializer
                    .initializeWorkflowWorkspace(workflow.id)
                    .workflowDir

                val metadataPath = workflowDir.resolve("workflow.yaml")
                val metadata = formatWorkflowMetadata(workflow)

                atomicFileIO.writeString(metadataPath, metadata, StandardCharsets.UTF_8)
                appendAuditEntry(
                    eventType = SpecAuditEventType.WORKFLOW_SAVED,
                    workflowId = workflow.id,
                    details = mapOf(
                        "status" to workflow.status.name,
                        "phase" to workflow.currentPhase.name,
                        "changeIntent" to workflow.changeIntent.name,
                    ),
                )
                logger.info("Saved workflow metadata to $metadataPath")

                metadataPath
            }
        }
    }

    /**
     * 加载工作流状态
     */
    fun loadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            val metadataPath = workflowDir.resolve("workflow.yaml")

            if (!Files.exists(metadataPath)) {
                throw IllegalStateException("Workflow not found: $workflowId")
            }

            val metadata = Files.readString(metadataPath, StandardCharsets.UTF_8)
            parseWorkflowMetadata(workflowId, metadata)
        }
    }

    /**
     * 列出所有工作流
     */
    fun listWorkflows(): List<String> {
        val specsDir = getSpecsDirectory()
        if (!Files.exists(specsDir)) {
            return emptyList()
        }

        return Files.list(specsDir).use { stream ->
            val workflows = mutableListOf<String>()
            stream.forEach { path ->
                if (Files.isDirectory(path)) {
                    workflows.add(path.fileName.toString())
                }
            }
            workflows
        }
    }

    /**
     * 删除工作流
     */
    fun deleteWorkflow(workflowId: String): Result<Unit> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val workflowDir = getWorkflowDirectory(workflowId)
                if (Files.exists(workflowDir)) {
                    appendAuditEntry(
                        eventType = SpecAuditEventType.WORKFLOW_DELETED,
                        workflowId = workflowId,
                    )
                    Files.walk(workflowDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { Files.delete(it) }
                    logger.info("Deleted workflow: $workflowId")
                }
            }
        }
    }

    fun archiveWorkflow(workflow: SpecWorkflow): Result<SpecArchiveResult> {
        return runCatching {
            lockManager.withWorkflowLock(workflow.id) {
                require(workflow.status == WorkflowStatus.COMPLETED) {
                    "Only completed workflow can be archived: ${workflow.id}"
                }

                val workflowDir = getWorkflowDirectory(workflow.id)
                if (!Files.exists(workflowDir)) {
                    throw IllegalStateException("Workflow not found for archive: ${workflow.id}")
                }

                val archiveRoot = getArchiveDirectory()
                Files.createDirectories(archiveRoot)
                val archiveId = "${workflow.id}-${System.currentTimeMillis()}"
                val archivePath = archiveRoot.resolve(archiveId)
                try {
                    Files.move(workflowDir, archivePath, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(workflowDir, archivePath)
                }
                val auditLogPath = getAuditLogPath(archivePath)

                appendAuditEntry(
                    eventType = SpecAuditEventType.WORKFLOW_ARCHIVED,
                    workflowId = workflow.id,
                    details = mapOf(
                        "archiveId" to archiveId,
                        "status" to workflow.status.name,
                        "phase" to workflow.currentPhase.name,
                        "title" to workflow.title,
                        "changeIntent" to workflow.changeIntent.name,
                    ),
                    auditLogPath = auditLogPath,
                )

                SpecArchiveResult(
                    workflowId = workflow.id,
                    archiveId = archiveId,
                    archivePath = archivePath,
                    auditLogPath = auditLogPath,
                )
            }
        }
    }

    fun initializeWorkspace(): Result<SpecWorkspacePaths> {
        return runCatching {
            workspaceInitializer.initializeProjectWorkspace()
        }
    }

    fun initializeWorkflowWorkspace(workflowId: String): Result<WorkflowWorkspacePaths> {
        return runCatching {
            workspaceInitializer.initializeWorkflowWorkspace(workflowId)
        }
    }

    /**
     * 获取工作流目录
     */
    private fun getWorkflowDirectory(workflowId: String): Path {
        return getSpecsDirectory().resolve(workflowId)
    }

    private fun getHistoryDirectory(workflowId: String, phase: SpecPhase): Path {
        return getWorkflowDirectory(workflowId)
            .resolve(".history")
            .resolve(phase.name.lowercase())
    }

    private fun getArchiveDirectory(): Path {
        return getSpecCodingDirectory().resolve("spec-archive")
    }

    private fun getAuditLogPath(workflowId: String): Path {
        return getAuditLogPath(getWorkflowDirectory(workflowId))
    }

    private fun getAuditLogPath(workflowRoot: Path): Path {
        return workflowRoot.resolve(".history").resolve("audit.yaml")
    }

    /**
     * 获取 Specs 根目录
     */
    private fun getSpecsDirectory(): Path {
        return getSpecCodingDirectory().resolve("specs")
    }

    private fun getSpecCodingDirectory(): Path {
        return workspaceInitializer.specCodingDirectory()
    }

    /**
     * 格式化文档内容
     */
    private fun formatDocument(document: SpecDocument): String {
        return buildString {
            appendLine("# ${document.metadata.title}")
            appendLine()
            appendLine("**阶段**: ${document.phase.displayName}")
            appendLine("**作者**: ${document.metadata.author}")
            appendLine("**创建时间**: ${formatTimestamp(document.metadata.createdAt)}")
            appendLine("**更新时间**: ${formatTimestamp(document.metadata.updatedAt)}")
            appendLine("**版本**: ${document.metadata.version}")

            if (document.metadata.tags.isNotEmpty()) {
                appendLine("**标签**: ${document.metadata.tags.joinToString(", ")}")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine(document.content)

            // 添加验证结果（如果有）
            document.validationResult?.let { validation ->
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## 验证结果")
                appendLine()
                appendLine("```")
                appendLine(validation.getSummary())
                appendLine("```")
            }
        }
    }

    /**
     * 解析文档内容
     */
    private fun parseDocument(workflowId: String, phase: SpecPhase, content: String): SpecDocument {
        // 简化版本：直接提取内容部分
        // 实际实现可以解析 YAML front matter
        val lines = content.lines()
        val contentStart = lines.indexOfFirst { it.trim() == "---" }
        val actualContent = if (contentStart >= 0 && contentStart < lines.size - 1) {
            lines.subList(contentStart + 1, lines.size).joinToString("\n")
        } else {
            content
        }

        return SpecDocument(
            id = "$workflowId-${phase.name.lowercase()}",
            phase = phase,
            content = actualContent.trim(),
            metadata = SpecMetadata(
                title = "${phase.displayName} Document",
                description = "Generated ${phase.displayName} document"
            )
        )
    }

    /**
     * 格式化工作流元数据
     */
    private fun formatWorkflowMetadata(workflow: SpecWorkflow): String {
        val metadata = linkedMapOf<String, Any?>(
            "id" to workflow.id,
            "title" to workflow.title,
            "description" to workflow.description.replace("\n", " "),
            "changeIntent" to workflow.changeIntent.name,
            "currentPhase" to workflow.currentPhase.name,
            "status" to workflow.status.name,
            "createdAt" to workflow.createdAt,
            "updatedAt" to workflow.updatedAt,
            "documents" to workflow.documents.entries
                .sortedBy { (phase, _) -> phase.name }
                .map { (phase, doc) ->
                    linkedMapOf<String, Any?>(
                        "phase" to phase.name,
                        "id" to doc.id,
                        "title" to doc.metadata.title,
                    )
                },
        )
        workflow.baselineWorkflowId?.takeIf { it.isNotBlank() }?.let { baseline ->
            metadata["baselineWorkflowId"] = baseline
        }
        workflow.clarificationRetryState?.let { retry ->
            metadata["clarificationRetryState"] = encodeClarificationRetryState(retry)
        }
        return yamlCodec.encodeMap(metadata)
    }

    /**
     * 解析工作流元数据
     */
    private fun parseWorkflowMetadata(workflowId: String, metadata: String): SpecWorkflow {
        val root = yamlCodec.decodeMap(metadata)
        val currentPhase = parseEnumValue(root["currentPhase"], SpecPhase.SPECIFY, SpecPhase.entries)
        val status = parseEnumValue(root["status"], WorkflowStatus.IN_PROGRESS, WorkflowStatus.entries)
        val changeIntent = parseEnumValue(root["changeIntent"], SpecChangeIntent.FULL, SpecChangeIntent.entries)
        val title = root["title"]?.toString()?.trim()?.ifBlank { workflowId } ?: workflowId
        val description = root["description"]?.toString()?.trim().orEmpty()
        val baselineWorkflowId = root["baselineWorkflowId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val clarificationRetryState = root["clarificationRetryState"]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeClarificationRetryState)
        val now = System.currentTimeMillis()
        val createdAt = parseLong(root["createdAt"], now)
        val updatedAt = parseLong(root["updatedAt"], now)

        // 加载已有文档
        val documents = mutableMapOf<SpecPhase, SpecDocument>()
        for (phase in SpecPhase.entries) {
            loadDocument(workflowId, phase).getOrNull()?.let {
                documents[phase] = it
            }
        }

        return SpecWorkflow(
            id = workflowId,
            currentPhase = currentPhase,
            documents = documents,
            status = status,
            title = title,
            description = description,
            changeIntent = changeIntent,
            baselineWorkflowId = baselineWorkflowId,
            clarificationRetryState = clarificationRetryState,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun parseLong(raw: Any?, fallback: Long): Long {
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun <E : Enum<E>> parseEnumValue(
        raw: Any?,
        fallback: E,
        candidates: Iterable<E>,
    ): E {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return fallback
        }
        return candidates.firstOrNull { candidate -> candidate.name == normalized } ?: fallback
    }

    private fun encodeClarificationRetryState(state: ClarificationRetryState): String {
        val structuredPayload = state.structuredQuestions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(STRUCTURED_QUESTION_DELIMITER.toString())
        return listOf(
            encodeBase64Url(state.input),
            encodeBase64Url(state.confirmedContext),
            encodeBase64Url(state.questionsMarkdown),
            encodeBase64Url(structuredPayload),
            state.clarificationRound.coerceAtLeast(1).toString(),
            encodeBase64Url(state.lastError.orEmpty()),
            if (state.confirmed) "1" else "0",
        ).joinToString(RETRY_FIELD_DELIMITER.toString())
    }

    private fun decodeClarificationRetryState(payload: String): ClarificationRetryState? {
        if (payload.isBlank()) {
            return null
        }
        val parts = payload.split(RETRY_FIELD_DELIMITER)
        if (parts.size < 7) {
            return null
        }
        val input = decodeBase64Url(parts[0]) ?: return null
        val confirmedContext = decodeBase64Url(parts[1]) ?: return null
        val questionsMarkdown = decodeBase64Url(parts[2]) ?: return null
        val structuredPayload = decodeBase64Url(parts[3]).orEmpty()
        val clarificationRound = parts[4].toIntOrNull()?.coerceAtLeast(1) ?: 1
        val lastError = decodeBase64Url(parts[5])
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val confirmed = parts[6] == "1" || parts[6].equals("true", ignoreCase = true)
        val structuredQuestions = structuredPayload
            .split(STRUCTURED_QUESTION_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (input.isBlank() && confirmedContext.isBlank() && questionsMarkdown.isBlank() && structuredQuestions.isEmpty()) {
            return null
        }
        return ClarificationRetryState(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
        )
    }

    private fun encodeBase64Url(raw: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeBase64Url(raw: String): String? {
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(raw)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }

    private fun saveDocumentSnapshot(
        workflowId: String,
        document: SpecDocument,
        formattedContent: String,
    ): String {
        val historyDir = getHistoryDirectory(workflowId, document.phase)
        Files.createDirectories(historyDir)
        val snapshotId = System.currentTimeMillis().toString()
        val snapshotPath = historyDir.resolve("$snapshotId.md")
        atomicFileIO.writeString(snapshotPath, formattedContent, StandardCharsets.UTF_8)
        return snapshotId
    }

    private fun cleanupIfEmpty(directory: Path) {
        if (!Files.exists(directory)) {
            return
        }
        Files.list(directory).use { stream ->
            if (!stream.findAny().isPresent) {
                Files.delete(directory)
            }
        }
    }

    private fun appendAuditEntry(
        eventType: SpecAuditEventType,
        workflowId: String,
        details: Map<String, String> = emptyMap(),
        auditLogPath: Path = getAuditLogPath(workflowId),
    ) {
        try {
            lockManager.withAuditLogLock(workflowId) {
                Files.createDirectories(auditLogPath.parent)
                val event = buildAuditEvent(
                    eventType = eventType,
                    workflowId = workflowId,
                    details = details,
                )
                val document = SpecAuditLogCodec.encodeDocument(event)
                Files.writeString(
                    auditLogPath,
                    document,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to append spec audit log entry", e)
        }
    }

    private fun buildAuditEvent(
        eventType: SpecAuditEventType,
        workflowId: String,
        details: Map<String, String>,
    ): SpecAuditEvent {
        val timestamp = System.currentTimeMillis()
        val normalizedDetails = details.entries
            .mapNotNull { (key, value) ->
                val normalizedKey = normalizeAuditValue(key)
                if (normalizedKey.isBlank()) {
                    return@mapNotNull null
                }
                normalizedKey to normalizeAuditValue(value)
            }
            .toMap()
            .toSortedMap()
        return SpecAuditEvent(
            eventId = "event-${UUID.randomUUID()}",
            workflowId = normalizedWorkflowId(workflowId),
            eventType = eventType,
            occurredAtEpochMs = timestamp,
            occurredAt = Instant.ofEpochMilli(timestamp).toString(),
            actor = System.getProperty("user.name")?.trim()?.takeIf { it.isNotBlank() },
            details = normalizedDetails,
        )
    }

    private fun normalizedWorkflowId(workflowId: String): String {
        return normalizeAuditValue(workflowId).ifBlank { "unknown-workflow" }
    }

    private fun normalizeAuditValue(raw: String): String {
        return raw
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
    }

    companion object {
        private const val RETRY_FIELD_DELIMITER = ':'
        private const val STRUCTURED_QUESTION_DELIMITER = '\u001F'

        fun getInstance(project: Project): SpecStorage {
            return SpecStorage(project)
        }
    }
}
