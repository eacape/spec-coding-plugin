package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmRouter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    fun createWorkflow(title: String, description: String): Result<SpecWorkflow> {
        return runCatching {
            val workflowId = generateWorkflowId()
            val workflow = SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.SPECIFY,
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = title,
                description = description,
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
        val request = SpecGenerationRequest(
            phase = workflow.currentPhase,
            input = input,
            previousDocument = previousDocument,
            options = options,
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

            // 构建生成请求
            val request = SpecGenerationRequest(
                phase = workflow.currentPhase,
                input = input,
                previousDocument = previousDocument,
                options = options
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

    /**
     * 生成工作流 ID
     */
    private fun generateWorkflowId(): String {
        return "spec-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    companion object {
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
