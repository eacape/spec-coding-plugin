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
    private val storage = SpecStorage.getInstance(project)
    private val generator = SpecGenerator(LlmRouter())

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult
    ) : this(project) {
        this.storageDelegate = storage
        this.generationHandler = generationHandler
    }

    private var storageDelegate: SpecStorage = storage
    private var generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult = generator::generate

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

    /**
     * 生成当前阶段的文档
     */
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
     * 进入下一阶段
     */
    fun proceedToNextPhase(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            // 检查是否可以进入下一阶段
            if (!workflow.canProceedToNext()) {
                throw IllegalStateException("Cannot proceed to next phase. Current phase validation failed or is incomplete.")
            }

            val nextPhase = workflow.currentPhase.next()
                ?: throw IllegalStateException("Already at the last phase")

            // 验证阶段转换
            val currentDocument = workflow.getCurrentDocument()
            val validation = SpecValidator.validatePhaseTransition(
                workflow.currentPhase,
                currentDocument,
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
