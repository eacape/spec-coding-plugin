package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookTriggerContext
import com.eacape.speccodingplugin.llm.LlmRouter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Spec 工作流引擎
 * 负责管理 Spec 工作流的状态机和阶段流转
 */
@Service(Service.Level.PROJECT)
class SpecEngine(private val project: Project) {
    private val logger = thisLogger()

    // Overridable by test constructor; lazy to avoid service lookups during construction
    private var _storageOverride: SpecStorage? = null
    private var _generationOverride: (suspend (SpecGenerationRequest) -> SpecGenerationResult)? = null
    private var _clarificationOverride: (suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>)? = null

    private val storageDelegate: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult by lazy {
        _generationOverride ?: SpecGenerator(LlmRouter.getInstance())::generate
    }
    private val clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft> by lazy {
        _clarificationOverride ?: SpecGenerator(LlmRouter.getInstance())::draftClarification
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>,
    ) : this(project) {
        this._storageOverride = storage
        this._generationOverride = generationHandler
        this._clarificationOverride = clarificationHandler
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
    ) : this(
        project = project,
        storage = storage,
        generationHandler = generationHandler,
        clarificationHandler = {
            Result.success(
                SpecClarificationDraft(
                    phase = it.phase,
                    questions = emptyList(),
                    rawContent = "",
                )
            )
        },
    )

    // 当前活跃的工作流
    private val activeWorkflows = mutableMapOf<String, SpecWorkflow>()

    /**
     * 创建新的工作流
     */
    fun createWorkflow(
        title: String,
        description: String,
        changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
        baselineWorkflowId: String? = null,
    ): Result<SpecWorkflow> {
        return runCatching {
            val normalizedBaselineWorkflowId = baselineWorkflowId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (changeIntent == SpecChangeIntent.INCREMENTAL && normalizedBaselineWorkflowId != null) {
                val baselineId = normalizedBaselineWorkflowId
                val baselineExists = activeWorkflows.containsKey(baselineId) ||
                    storageDelegate.loadWorkflow(baselineId).isSuccess
                require(baselineExists) {
                    "Baseline workflow not found: $baselineId"
                }
            }
            val workflowId = generateWorkflowId()
            val workflow = SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.SPECIFY,
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = title,
                description = description,
                changeIntent = changeIntent,
                baselineWorkflowId = if (changeIntent == SpecChangeIntent.INCREMENTAL) {
                    normalizedBaselineWorkflowId
                } else {
                    null
                },
            )

            activeWorkflows[workflowId] = workflow
            storageDelegate.saveWorkflow(workflow).getOrThrow()

            logger.info("Created workflow: $workflowId")
            workflow
        }
    }

    /**
     * 加载工作流
     */
    fun loadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            // 先从内存缓存查找
            activeWorkflows[workflowId]?.let { return@runCatching it }

            // 从存储加载
            val workflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow
            workflow
        }
    }

    /**
     * 强制从存储重载工作流，覆盖内存缓存。
     */
    fun reloadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow
            workflow
        }
    }

    /**
     * 列出所有工作流
     */
    fun listWorkflows(): List<String> {
        return storageDelegate.listWorkflows()
    }

    fun listDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
    ): List<SpecDocumentHistoryEntry> {
        return storageDelegate.listDocumentHistory(workflowId, phase)
    }

    fun loadDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<SpecDocument> {
        return storageDelegate.loadDocumentSnapshot(workflowId, phase, snapshotId)
    }

    fun deleteDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<Unit> {
        return storageDelegate.deleteDocumentSnapshot(workflowId, phase, snapshotId)
    }

    fun pruneDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
        keepLatest: Int,
    ): Result<Int> {
        return storageDelegate.pruneDocumentHistory(workflowId, phase, keepLatest)
    }

    /**
     * 生成当前阶段的文档
     */
    suspend fun generateCurrentPhase(
        workflowId: String,
        input: String,
    ): Flow<SpecGenerationProgress> {
        return generateCurrentPhase(
            workflowId = workflowId,
            input = input,
            options = GenerationOptions(),
        )
    }

    suspend fun draftCurrentPhaseClarification(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions(),
    ): Result<SpecClarificationDraft> {
        val workflow = activeWorkflows[workflowId]
            ?: storageDelegate.loadWorkflow(workflowId).getOrElse { return Result.failure(it) }
        activeWorkflows[workflowId] = workflow

        val previousPhase = workflow.currentPhase.previous()
        val previousDocument = previousPhase?.let { workflow.getDocument(it) }
        val effectiveOptions = enrichGenerationOptions(workflow, options)
        val request = SpecGenerationRequest(
            phase = workflow.currentPhase,
            input = input,
            previousDocument = previousDocument,
            options = effectiveOptions,
        )
        return clarificationHandler(request)
    }

    suspend fun generateCurrentPhase(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions()
    ): Flow<SpecGenerationProgress> = flow {
        val workflow = activeWorkflows[workflowId]
            ?: throw IllegalStateException("Workflow not found: $workflowId")

        emit(SpecGenerationProgress.Started(workflow.currentPhase))

        try {
            // 获取前一阶段的文档（如果有）
            val previousPhase = workflow.currentPhase.previous()
            val previousDocument = previousPhase?.let { workflow.getDocument(it) }
            val effectiveOptions = enrichGenerationOptions(workflow, options)

            // 构建生成请求
            val request = SpecGenerationRequest(
                phase = workflow.currentPhase,
                input = input,
                previousDocument = previousDocument,
                options = effectiveOptions
            )

            emit(SpecGenerationProgress.Generating(workflow.currentPhase, 0.3))

            // 生成文档
            val result = generationHandler(request)

            when (result) {
                is SpecGenerationResult.Success -> {
                    emit(SpecGenerationProgress.Generating(workflow.currentPhase, 0.7))

                    // 保存文档
                    storageDelegate.saveDocument(workflowId, result.document).getOrThrow()

                    // 更新工作流
                    val updatedWorkflow = workflow.copy(
                        documents = workflow.documents + (workflow.currentPhase to result.document),
                        updatedAt = System.currentTimeMillis()
                    )
                    activeWorkflows[workflowId] = updatedWorkflow
                    storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

                    emit(SpecGenerationProgress.Completed(result.document))
                }

                is SpecGenerationResult.ValidationFailed -> {
                    // 保存文档（即使验证失败）
                    storageDelegate.saveDocument(workflowId, result.document).getOrThrow()

                    val updatedWorkflow = workflow.copy(
                        documents = workflow.documents + (workflow.currentPhase to result.document),
                        updatedAt = System.currentTimeMillis()
                    )
                    activeWorkflows[workflowId] = updatedWorkflow
                    storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

                    emit(SpecGenerationProgress.ValidationFailed(result.document, result.validation))
                }

                is SpecGenerationResult.Failure -> {
                    emit(SpecGenerationProgress.Failed(result.error, result.details))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate ${workflow.currentPhase} document", e)
            emit(SpecGenerationProgress.Failed(e.message ?: "Unknown error", e.stackTraceToString()))
        }
    }

    /**
     * 更新指定阶段文档内容（用于 Chat Spec 卡片内编辑）。
     * 可选 expectedRevision 用于并发冲突检测。
     */
    fun updateDocumentContent(
        workflowId: String,
        phase: SpecPhase,
        content: String,
        expectedRevision: Long? = null,
    ): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val existingDocument = workflow.getDocument(phase)
                ?: SpecDocument(
                    id = "$workflowId-${phase.name.lowercase()}",
                    phase = phase,
                    content = "",
                    metadata = SpecMetadata(
                        title = "${phase.displayName} Document",
                        description = "Manually edited ${phase.displayName} document",
                    ),
                    validationResult = null,
                )

            expectedRevision?.let { expected ->
                val actual = existingDocument.metadata.updatedAt
                if (actual != expected) {
                    throw DocumentRevisionConflictException(
                        workflowId = workflowId,
                        phase = phase,
                        expectedRevision = expected,
                        actualRevision = actual,
                    )
                }
            }

            val normalizedContent = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            require(normalizedContent.isNotBlank()) { "Document content cannot be blank" }

            val now = System.currentTimeMillis()
            val draftDocument = existingDocument.copy(
                content = normalizedContent,
                metadata = existingDocument.metadata.copy(updatedAt = now),
            )
            val validation = SpecValidator.validate(draftDocument)
            val updatedDocument = draftDocument.copy(validationResult = validation)

            storageDelegate.saveDocument(workflowId, updatedDocument).getOrThrow()

            val updatedWorkflow = workflow.copy(
                documents = workflow.documents + (phase to updatedDocument),
                updatedAt = now,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId updated ${phase.displayName} document")
            updatedWorkflow
        }
    }

    fun updateWorkflowMetadata(
        workflowId: String,
        title: String,
        description: String,
    ): Result<SpecWorkflow> {
        return runCatching {
            val normalizedTitle = title
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .trim()
            require(normalizedTitle.isNotBlank()) { "Workflow title cannot be blank" }

            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val now = System.currentTimeMillis()
            val updatedWorkflow = workflow.copy(
                title = normalizedTitle,
                description = description
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim(),
                updatedAt = now,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId updated metadata")
            updatedWorkflow
        }
    }

    /**
     * 进入下一阶段
     */
    fun proceedToNextPhase(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val currentDocument = workflow.getCurrentDocument()
                ?: throw IllegalStateException(
                    "Cannot proceed to next phase. Current phase document is missing. " +
                        "Run /spec generate <input> first.",
                )

            // 每次推进阶段前按最新规则实时重算校验，避免历史持久化结果过期
            val currentValidation = SpecValidator.validate(currentDocument)
            if (!currentValidation.valid) {
                val detail = currentValidation.errors
                    .take(3)
                    .joinToString("；")
                    .ifBlank { "unknown validation error" }
                throw IllegalStateException(
                    "Cannot proceed to next phase. Current phase validation failed: $detail",
                )
            }

            val nextPhase = workflow.currentPhase.next()
                ?: throw IllegalStateException("Already at the last phase")

            // 验证阶段转换
            val validation = SpecValidator.validatePhaseTransition(
                workflow.currentPhase,
                currentDocument.copy(validationResult = currentValidation),
                nextPhase
            )

            if (!validation.valid) {
                throw IllegalStateException("Phase transition validation failed: ${validation.errors.joinToString(", ")}")
            }

            // 更新工作流
            val updatedWorkflow = workflow.copy(
                currentPhase = nextPhase,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            emitSpecStageChangedHook(
                workflowId = workflowId,
                previousPhase = workflow.currentPhase,
                currentPhase = nextPhase,
            )
            logger.info("Workflow $workflowId proceeded to ${nextPhase.displayName}")
            updatedWorkflow
        }
    }

    /**
     * 返回上一阶段
     */
    fun goBackToPreviousPhase(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (!workflow.canGoBack()) {
                throw IllegalStateException("Already at the first phase")
            }

            val previousPhase = workflow.currentPhase.previous()
                ?: throw IllegalStateException("No previous phase")

            val updatedWorkflow = workflow.copy(
                currentPhase = previousPhase,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            emitSpecStageChangedHook(
                workflowId = workflowId,
                previousPhase = workflow.currentPhase,
                currentPhase = previousPhase,
            )
            logger.info("Workflow $workflowId went back to ${previousPhase.displayName}")
            updatedWorkflow
        }
    }

    /**
     * 完成工作流
     */
    fun completeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.currentPhase != SpecPhase.IMPLEMENT) {
                throw IllegalStateException("Cannot complete workflow. Must be at Implement phase.")
            }

            val implementDoc = workflow.getDocument(SpecPhase.IMPLEMENT)
                ?: throw IllegalStateException("Implement phase document not found")

            if (implementDoc.validationResult?.valid != true) {
                throw IllegalStateException("Cannot complete workflow. Implement phase validation failed.")
            }

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.COMPLETED,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId completed")
            updatedWorkflow
        }
    }

    /**
     * 暂停工作流
     */
    fun pauseWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId paused")
            updatedWorkflow
        }
    }

    /**
     * 恢复工作流
     */
    fun resumeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.status != WorkflowStatus.PAUSED) {
                throw IllegalStateException("Workflow is not paused")
            }

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId resumed")
            updatedWorkflow
        }
    }

    /**
     * 删除工作流
     */
    fun deleteWorkflow(workflowId: String): Result<Unit> {
        return runCatching {
            activeWorkflows.remove(workflowId)
            storageDelegate.deleteWorkflow(workflowId).getOrThrow()
            logger.info("Workflow $workflowId deleted")
        }
    }

    fun archiveWorkflow(workflowId: String): Result<SpecArchiveResult> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            if (workflow.status != WorkflowStatus.COMPLETED) {
                throw IllegalStateException("Only completed workflow can be archived")
            }

            val result = storageDelegate.archiveWorkflow(workflow).getOrThrow()
            activeWorkflows.remove(workflowId)
            logger.info("Workflow $workflowId archived to ${result.archivePath}")
            result
        }
    }

    private fun emitSpecStageChangedHook(
        workflowId: String,
        previousPhase: SpecPhase,
        currentPhase: SpecPhase,
    ) {
        runCatching {
            HookManager.getInstance(project).trigger(
                event = HookEvent.SPEC_STAGE_CHANGED,
                triggerContext = HookTriggerContext(
                    specStage = currentPhase.name,
                    metadata = mapOf(
                        "workflowId" to workflowId,
                        "previousStage" to previousPhase.name,
                        "currentStage" to currentPhase.name,
                    ),
                ),
            )
        }.onFailure { error ->
            logger.warn(
                "Failed to emit SPEC_STAGE_CHANGED hook for workflow=$workflowId " +
                    "(${previousPhase.name} -> ${currentPhase.name})",
                error,
            )
        }
    }

    private fun enrichGenerationOptions(
        workflow: SpecWorkflow,
        options: GenerationOptions,
    ): GenerationOptions {
        val normalizedWorkingDirectory = options.workingDirectory
            ?.trim()
            ?.ifBlank { null }
            ?: project.basePath
                ?.trim()
                ?.ifBlank { null }
        val normalizedOperationMode = options.operationMode
            ?.trim()
            ?.ifBlank { null }
            ?: OperationMode.PLAN.name
        val enrichedOptions = options.copy(
            workingDirectory = normalizedWorkingDirectory,
            operationMode = normalizedOperationMode,
        )

        val baselineContext = buildIncrementalBaselineContext(workflow)
        val projectContext = buildIncrementalProjectContext(workflow)
        val existingContext = enrichedOptions.confirmedContext
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        val mergedContextSections = listOfNotNull(
            existingContext.takeIf { it.isNotBlank() },
            baselineContext?.takeIf { it.isNotBlank() },
            projectContext?.takeIf { it.isNotBlank() },
        )
        if (mergedContextSections.isEmpty()) {
            return enrichedOptions
        }
        val mergedContext = mergedContextSections.joinToString(separator = "\n\n---\n\n")
        return enrichedOptions.copy(confirmedContext = mergedContext)
    }

    private fun buildIncrementalBaselineContext(workflow: SpecWorkflow): String? {
        if (!workflow.isIncrementalWorkflow()) {
            return null
        }
        val baselineWorkflowId = workflow.baselineWorkflowId?.trim().orEmpty()
        if (baselineWorkflowId.isBlank()) {
            return null
        }
        val baseline = activeWorkflows[baselineWorkflowId]
            ?: storageDelegate.loadWorkflow(baselineWorkflowId).getOrElse { error ->
                logger.warn("Failed to load baseline workflow for incremental generation: $baselineWorkflowId", error)
                return null
            }
        activeWorkflows[baselineWorkflowId] = baseline

        fun baselineDoc(phase: SpecPhase): String {
            return baseline.getDocument(phase)?.content?.trim().takeUnless { it.isNullOrEmpty() } ?: "(无)"
        }

        return buildString {
            appendLine("## 增量需求基线上下文")
            appendLine("当前工作流是增量需求，请在输出中明确区分“新增 / 修改 / 保持不变”。")
            appendLine("基线工作流 ID: ${baseline.id}")
            appendLine("基线标题: ${baseline.title.ifBlank { baseline.id }}")
            if (workflow.description.isNotBlank()) {
                appendLine("当前工作流描述（变更目标）: ${workflow.description.trim()}")
            }
            appendLine()
            appendLine("## 基线 requirements.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.SPECIFY))
            appendLine("```")
            appendLine()
            appendLine("## 基线 design.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.DESIGN))
            appendLine("```")
            appendLine()
            appendLine("## 基线 tasks.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.IMPLEMENT))
            appendLine("```")
        }
    }

    private fun buildIncrementalProjectContext(workflow: SpecWorkflow): String? {
        if (!workflow.isIncrementalWorkflow() || workflow.currentPhase != SpecPhase.SPECIFY) {
            return null
        }
        val basePath = project.basePath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val root = runCatching { Path.of(basePath) }.getOrNull() ?: return null
        if (!Files.isDirectory(root)) {
            return null
        }

        val topLevelDirectories = listTopLevelEntries(root, directoriesOnly = true)
        val topLevelFiles = listTopLevelEntries(root, directoriesOnly = false)
        val keyFileSnippets = readKeyProjectFiles(root)
        val sourceSnapshot = buildSourceSnapshot(root)

        if (topLevelDirectories.isEmpty() && topLevelFiles.isEmpty() && keyFileSnippets.isEmpty() && sourceSnapshot.isBlank()) {
            return null
        }

        return buildString {
            appendLine("## 现有项目上下文（增量需求生成要求）")
            appendLine("生成 requirements.md 时，请结合当前代码结构和已有配置，优先复用现有模块与命名。")
            if (topLevelDirectories.isNotEmpty()) {
                appendLine("顶层目录: ${topLevelDirectories.joinToString(", ")}")
            }
            if (topLevelFiles.isNotEmpty()) {
                appendLine("顶层文件: ${topLevelFiles.joinToString(", ")}")
            }
            if (sourceSnapshot.isNotBlank()) {
                appendLine()
                appendLine("### 现有源码文件（节选）")
                appendLine(sourceSnapshot)
            }
            if (keyFileSnippets.isNotEmpty()) {
                appendLine()
                appendLine("### 关键项目文件（节选）")
                keyFileSnippets.forEach { snippet ->
                    appendLine("#### ${snippet.relativePath}")
                    appendLine("```")
                    appendLine(snippet.content)
                    appendLine("```")
                    appendLine()
                }
            }
        }.trimEnd()
    }

    private fun listTopLevelEntries(root: Path, directoriesOnly: Boolean): List<String> {
        return runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName?.toString().orEmpty()
                        if (name.isBlank() || shouldIgnoreProjectEntry(name)) {
                            return@filter false
                        }
                        if (directoriesOnly) {
                            Files.isDirectory(path)
                        } else {
                            Files.isRegularFile(path)
                        }
                    }
                    .map { it.fileName.toString() }
                    .sorted()
                    .limit(MAX_TOP_LEVEL_ENTRIES.toLong())
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    private fun readKeyProjectFiles(root: Path): List<ProjectContextSnippet> {
        return KEY_PROJECT_CONTEXT_FILES.mapNotNull { relativePath ->
            val path = root.resolve(relativePath)
            if (!Files.isRegularFile(path)) {
                return@mapNotNull null
            }
            val raw = runCatching {
                Files.readString(path, DEFAULT_PROJECT_CONTEXT_CHARSET)
            }.getOrElse { error ->
                logger.debug("Skip project context file due to read failure: $relativePath", error)
                return@mapNotNull null
            }
            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isBlank()) {
                return@mapNotNull null
            }
            ProjectContextSnippet(
                relativePath = relativePath,
                content = clipContextText(normalized, MAX_KEY_FILE_SNIPPET_LINES, MAX_KEY_FILE_SNIPPET_CHARS),
            )
        }
    }

    private fun buildSourceSnapshot(root: Path): String {
        val lines = mutableListOf<String>()
        for (relativeDir in SOURCE_CONTEXT_DIRS) {
            val dir = root.resolve(relativeDir)
            if (!Files.isDirectory(dir)) {
                continue
            }
            val files = runCatching {
                Files.walk(dir, SOURCE_SNAPSHOT_DEPTH).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .map { root.relativize(it).toString().replace('\\', '/') }
                        .sorted()
                        .limit(MAX_SOURCE_FILES_PER_DIR.toLong())
                        .toList()
                }
            }.getOrElse { error ->
                logger.debug("Skip source snapshot for $relativeDir", error)
                emptyList()
            }
            if (files.isEmpty()) {
                continue
            }
            lines += "- $relativeDir"
            files.forEach { path -> lines += "  - `$path`" }
        }
        return lines.joinToString("\n")
    }

    private fun clipContextText(content: String, maxLines: Int, maxChars: Int): String {
        val lines = content.lines()
        val clippedByLines = lines.size > maxLines
        val linesWithinBudget = lines.take(maxLines).joinToString("\n")
        val clippedByChars = linesWithinBudget.length > maxChars
        val clipped = if (clippedByChars) {
            linesWithinBudget.take(maxChars).trimEnd()
        } else {
            linesWithinBudget
        }
        return if (clippedByLines || clippedByChars) {
            "$clipped\n...(截断)"
        } else {
            clipped
        }
    }

    private fun shouldIgnoreProjectEntry(name: String): Boolean {
        return name in PROJECT_CONTEXT_IGNORED_ENTRIES
    }

    private data class ProjectContextSnippet(
        val relativePath: String,
        val content: String,
    )

    /**
     * 生成工作流 ID
     */
    private fun generateWorkflowId(): String {
        return "spec-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    companion object {
        private val PROJECT_CONTEXT_IGNORED_ENTRIES = setOf(
            ".git",
            ".idea",
            ".gradle",
            ".spec-coding",
            "build",
            "out",
            "node_modules",
            "__pycache__",
            ".DS_Store",
            "Thumbs.db",
        )
        private val KEY_PROJECT_CONTEXT_FILES = listOf(
            "README.md",
            "README.zh-CN.md",
            "docs/spec-coding-plugin-plan.md",
            "docs/dev-checklist.md",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "src/main/resources/META-INF/plugin.xml",
            "package.json",
            "pom.xml",
            "pyproject.toml",
        )
        private val SOURCE_CONTEXT_DIRS = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/main/resources",
            "src/test/kotlin",
            "src/test/java",
        )
        private val DEFAULT_PROJECT_CONTEXT_CHARSET: Charset = Charsets.UTF_8
        private const val MAX_TOP_LEVEL_ENTRIES = 12
        private const val MAX_KEY_FILE_SNIPPET_LINES = 120
        private const val MAX_KEY_FILE_SNIPPET_CHARS = 4000
        private const val SOURCE_SNAPSHOT_DEPTH = 2
        private const val MAX_SOURCE_FILES_PER_DIR = 18

        fun getInstance(project: Project): SpecEngine = project.service()
    }
}

class DocumentRevisionConflictException(
    val workflowId: String,
    val phase: SpecPhase,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Document revision conflict: expected $expectedRevision but was $actualRevision " +
        "(workflow=$workflowId, phase=${phase.name})",
)

/**
 * Spec 生成进度
 */
sealed class SpecGenerationProgress {
    data class Started(val phase: SpecPhase) : SpecGenerationProgress()
    data class Generating(val phase: SpecPhase, val progress: Double) : SpecGenerationProgress()
    data class Completed(val document: SpecDocument) : SpecGenerationProgress()
    data class ValidationFailed(val document: SpecDocument, val validation: ValidationResult) : SpecGenerationProgress()
    data class Failed(val error: String, val details: String?) : SpecGenerationProgress()
}
