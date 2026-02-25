package com.eacape.speccodingplugin.spec

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Spec 文档存储管理器
 * 负责 Spec 文档的文件系统存储和读取
 */
class SpecStorage(private val project: Project) {
    private val logger = thisLogger()

    /**
     * 保存 Spec 文档
     */
    fun saveDocument(workflowId: String, document: SpecDocument): Result<Path> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            Files.createDirectories(workflowDir)

            val filePath = workflowDir.resolve(document.phase.outputFileName)
            val content = formatDocument(document)

            Files.writeString(filePath, content, StandardCharsets.UTF_8)
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

    fun pruneDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
        keepLatest: Int,
    ): Result<Int> {
        return runCatching {
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
            val workflowDir = getWorkflowDirectory(workflow.id)
            Files.createDirectories(workflowDir)

            val metadataPath = workflowDir.resolve("workflow.yaml")
            val metadata = formatWorkflowMetadata(workflow)

            Files.writeString(metadataPath, metadata, StandardCharsets.UTF_8)
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
            val workflowDir = getWorkflowDirectory(workflowId)
            if (Files.exists(workflowDir)) {
                Files.walk(workflowDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
                appendAuditEntry(
                    eventType = SpecAuditEventType.WORKFLOW_DELETED,
                    workflowId = workflowId,
                )
                logger.info("Deleted workflow: $workflowId")
            }
        }
    }

    fun archiveWorkflow(workflow: SpecWorkflow): Result<SpecArchiveResult> {
        return runCatching {
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
            )

            SpecArchiveResult(
                workflowId = workflow.id,
                archiveId = archiveId,
                archivePath = archivePath,
                auditLogPath = getAuditLogPath(),
            )
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

    private fun getAuditLogPath(): Path {
        return getSpecCodingDirectory().resolve("spec-audit.log")
    }

    /**
     * 获取 Specs 根目录
     */
    private fun getSpecsDirectory(): Path {
        return getSpecCodingDirectory().resolve("specs")
    }

    private fun getSpecCodingDirectory(): Path {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
        return Paths.get(basePath)
            .resolve(".spec-coding")
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
        return buildString {
            appendLine("id: ${workflow.id}")
            appendLine("title: ${workflow.title}")
            appendLine("description: ${workflow.description.replace("\n", " ")}")
            appendLine("changeIntent: ${workflow.changeIntent.name}")
            workflow.baselineWorkflowId?.takeIf { it.isNotBlank() }?.let {
                appendLine("baselineWorkflowId: $it")
            }
            appendLine("currentPhase: ${workflow.currentPhase.name}")
            appendLine("status: ${workflow.status.name}")
            appendLine("createdAt: ${workflow.createdAt}")
            appendLine("updatedAt: ${workflow.updatedAt}")
            appendLine("documents:")
            workflow.documents.forEach { (phase, doc) ->
                appendLine("  - phase: ${phase.name}")
                appendLine("    id: ${doc.id}")
                appendLine("    title: ${doc.metadata.title}")
            }
        }
    }

    /**
     * 解析工作流元数据
     */
    private fun parseWorkflowMetadata(workflowId: String, metadata: String): SpecWorkflow {
        val lines = metadata.lines()
        var currentPhase = SpecPhase.SPECIFY
        var status = WorkflowStatus.IN_PROGRESS
        var title = workflowId
        var description = ""
        var changeIntent = SpecChangeIntent.FULL
        var baselineWorkflowId: String? = null
        var createdAt = System.currentTimeMillis()
        var updatedAt = System.currentTimeMillis()

        for (line in lines) {
            // Only parse top-level fields (no indentation). Nested "documents" entries also contain
            // keys like `title:` and would otherwise override workflow metadata.
            val trimmed = line.trimEnd()
            when {
                trimmed.startsWith("currentPhase:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    currentPhase = runCatching { SpecPhase.valueOf(value) }.getOrDefault(SpecPhase.SPECIFY)
                }
                trimmed.startsWith("title:") -> {
                    title = trimmed.substringAfter(":").trim().ifBlank { workflowId }
                }
                trimmed.startsWith("description:") -> {
                    description = trimmed.substringAfter(":").trim()
                }
                trimmed.startsWith("changeIntent:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    changeIntent = runCatching { SpecChangeIntent.valueOf(value) }.getOrDefault(SpecChangeIntent.FULL)
                }
                trimmed.startsWith("baselineWorkflowId:") -> {
                    baselineWorkflowId = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("status:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    status = runCatching { WorkflowStatus.valueOf(value) }.getOrDefault(WorkflowStatus.IN_PROGRESS)
                }
                trimmed.startsWith("createdAt:") -> {
                    createdAt = trimmed.substringAfter(":").trim().toLongOrNull() ?: createdAt
                }
                trimmed.startsWith("updatedAt:") -> {
                    updatedAt = trimmed.substringAfter(":").trim().toLongOrNull() ?: updatedAt
                }
            }
        }

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
            createdAt = createdAt,
            updatedAt = updatedAt
        )
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
        Files.writeString(snapshotPath, formattedContent, StandardCharsets.UTF_8)
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
    ) {
        try {
            val auditLogPath = getAuditLogPath()
            Files.createDirectories(auditLogPath.parent)

            val detailPayload = details.entries
                .sortedBy { it.key }
                .joinToString(";") { (key, value) ->
                    "${sanitizeAuditValue(key)}=${sanitizeAuditValue(value)}"
                }
            val line = "${System.currentTimeMillis()}|${eventType.name}|${sanitizeAuditValue(workflowId)}|$detailPayload\n"
            Files.writeString(
                auditLogPath,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            logger.warn("Failed to append spec audit log entry", e)
        }
    }

    private fun sanitizeAuditValue(raw: String): String {
        return raw
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("|", "/")
            .trim()
    }

    companion object {
        fun getInstance(project: Project): SpecStorage {
            return SpecStorage(project)
        }
    }
}
