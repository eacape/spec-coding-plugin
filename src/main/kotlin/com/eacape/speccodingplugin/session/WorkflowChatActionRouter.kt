package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.spec.MissingStructuredTaskError
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTaskCompletionService
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.attachActiveExecutionRuns
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class WorkflowChatActionRouter(private val project: Project) {
    private var _sessionManagerOverride: SessionManager? = null
    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null
    private var _executionServiceOverride: SpecTaskExecutionService? = null
    private var _completionServiceOverride: SpecTaskCompletionService? = null

    private val sessionManager: SessionManager by lazy {
        _sessionManagerOverride ?: SessionManager.getInstance(project)
    }
    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy {
        _tasksServiceOverride ?: SpecTasksService.getInstance(project)
    }
    private val executionService: SpecTaskExecutionService by lazy {
        _executionServiceOverride ?: SpecTaskExecutionService.getInstance(project)
    }
    private val completionService: SpecTaskCompletionService by lazy {
        _completionServiceOverride ?: SpecTaskCompletionService.getInstance(project)
    }

    internal constructor(
        project: Project,
        sessionManager: SessionManager,
        storage: SpecStorage,
        tasksService: SpecTasksService,
        executionService: SpecTaskExecutionService,
        completionService: SpecTaskCompletionService,
    ) : this(project) {
        _sessionManagerOverride = sessionManager
        _storageOverride = storage
        _tasksServiceOverride = tasksService
        _executionServiceOverride = executionService
        _completionServiceOverride = completionService
    }

    fun executeBoundTask(
        sessionId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        val context = resolveBoundTaskContext(sessionId)
        return executionService.startAiExecution(
            workflowId = context.binding.workflowId,
            taskId = context.task.id,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            sessionId = context.session.id,
            sessionSource = context.binding.source,
            auditContext = buildAuditContext(
                context = context,
                action = WorkflowChatActionIntent.EXECUTE_TASK,
            ),
        )
    }

    fun retryBoundTask(
        sessionId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        previousRunId: String? = null,
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        val context = resolveBoundTaskContext(sessionId)
        return executionService.retryAiExecution(
            workflowId = context.binding.workflowId,
            taskId = context.task.id,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            previousRunId = previousRunId,
            sessionId = context.session.id,
            sessionSource = context.binding.source,
            auditContext = buildAuditContext(
                context = context,
                action = WorkflowChatActionIntent.RETRY_TASK,
            ),
        )
    }

    fun previewCompleteBoundTask(sessionId: String): SpecTaskCompletionService.TaskCompletionPlan {
        val context = resolveBoundTaskContext(sessionId)
        return completionService.previewCompletion(
            workflowId = context.binding.workflowId,
            taskId = context.task.id,
        )
    }

    fun completeBoundTask(
        sessionId: String,
        planId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
    ): StructuredTask {
        val context = resolveBoundTaskContext(sessionId)
        val completedTask = completionService.completeTask(
            planId = planId,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
            auditContext = buildAuditContext(
                context = context,
                action = WorkflowChatActionIntent.COMPLETE_TASK,
            ),
            completionRunSummary = "Completed from workflow chat task action.",
        )
        sessionManager.updateWorkflowChatBinding(
            context.session.id,
            context.binding.copy(actionIntent = WorkflowChatActionIntent.COMPLETE_TASK),
        ).getOrThrow()
        return completedTask
    }

    private fun resolveBoundTaskContext(sessionId: String): BoundTaskContext {
        val normalizedSessionId = sessionId.trim()
        require(normalizedSessionId.isNotBlank()) { "sessionId cannot be blank" }
        val session = sessionManager.getSession(normalizedSessionId)
            ?: throw IllegalArgumentException("Session not found: $normalizedSessionId")
        val binding = session.resolvedWorkflowChatBinding()
            ?: throw IllegalStateException("Workflow chat binding is not available for session $normalizedSessionId")
        val taskId = binding.taskId?.trim()?.ifBlank { null }
            ?: throw IllegalStateException("Workflow chat session $normalizedSessionId is not bound to a task")
        val workflow = storage.loadWorkflow(binding.workflowId).getOrThrow()
        val task = tasksService.parse(binding.workflowId)
            .attachActiveExecutionRuns(workflow.taskExecutionRuns)
            .firstOrNull { candidate -> candidate.id == taskId }
            ?: throw MissingStructuredTaskError(taskId)
        return BoundTaskContext(
            session = session,
            binding = binding,
            task = task,
        )
    }

    private fun buildAuditContext(
        context: BoundTaskContext,
        action: WorkflowChatActionIntent,
    ): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            put("triggerSource", "WORKFLOW_CHAT")
            put("workflowChatSessionId", context.session.id)
            put("workflowChatSource", context.binding.source.name)
            put("workflowChatActionIntent", action.name)
            put("workflowChatFocusedStage", context.binding.focusedStage?.name.orEmpty())
            put("taskAction", action.name)
            put("taskLifecycleStatus", context.task.status.name)
            put("taskDisplayStatus", context.task.displayStatus.name)
            put("taskExecutionRunId", context.task.activeExecutionRun?.runId.orEmpty())
            put("taskExecutionRunStatus", context.task.activeExecutionRun?.status?.name.orEmpty())
        }.filterValues { value -> value.isNotBlank() }
    }

    private data class BoundTaskContext(
        val session: ConversationSession,
        val binding: WorkflowChatBinding,
        val task: StructuredTask,
    )

    companion object {
        fun getInstance(project: Project): WorkflowChatActionRouter = project.service()
    }
}
