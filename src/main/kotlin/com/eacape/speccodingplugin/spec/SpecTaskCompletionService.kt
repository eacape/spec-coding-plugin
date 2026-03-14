package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SpecTaskCompletionService(private val project: Project) {
    data class TaskCompletionPlan(
        val planId: String,
        val workflowId: String,
        val taskId: String,
        val generatedAt: String,
        val suggestedRelatedFiles: List<String>,
        val existingVerificationResult: TaskVerificationResult?,
        val awaitingExecutionConfirmation: Boolean,
    )

    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null
    private var _relatedFilesResolverOverride: ((String, List<String>) -> List<String>)? = null
    private var _taskExecutionServiceOverride: SpecTaskExecutionService? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy {
        _tasksServiceOverride ?: SpecTasksService.getInstance(project)
    }
    private val relatedFilesResolver: (String, List<String>) -> List<String> by lazy {
        _relatedFilesResolverOverride ?: { taskId, existingRelatedFiles ->
            SpecRelatedFilesService.getInstance(project).suggestRelatedFiles(taskId, existingRelatedFiles)
        }
    }
    private val taskExecutionService: SpecTaskExecutionService by lazy {
        _taskExecutionServiceOverride ?: SpecTaskExecutionService.getInstance(project)
    }
    private val pendingPlans = ConcurrentHashMap<String, TaskCompletionPlan>()

    internal constructor(
        project: Project,
        storage: SpecStorage,
        tasksService: SpecTasksService,
        relatedFilesResolver: (String, List<String>) -> List<String>,
        taskExecutionService: SpecTaskExecutionService,
    ) : this(project) {
        _storageOverride = storage
        _tasksServiceOverride = tasksService
        _relatedFilesResolverOverride = relatedFilesResolver
        _taskExecutionServiceOverride = taskExecutionService
    }

    fun previewCompletion(workflowId: String, taskId: String): TaskCompletionPlan {
        val task = resolveTask(workflowId, taskId)
        val plan = TaskCompletionPlan(
            planId = UUID.randomUUID().toString(),
            workflowId = workflowId.trim(),
            taskId = task.id,
            generatedAt = Instant.now().toString(),
            suggestedRelatedFiles = relatedFilesResolver(task.id, task.relatedFiles),
            existingVerificationResult = task.verificationResult,
            awaitingExecutionConfirmation = task.awaitingCompletionConfirmation,
        )
        pendingPlans[plan.planId] = plan
        return plan
    }

    fun completeTask(
        planId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String> = emptyMap(),
        completionRunSummary: String? = null,
    ): StructuredTask {
        val plan = pendingPlans[planId]
            ?: throw IllegalArgumentException("Task completion plan not found: $planId")
        val completedTask = completeTask(
            workflowId = plan.workflowId,
            taskId = plan.taskId,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
            auditContext = auditContext,
            completionRunSummary = completionRunSummary,
        )
        pendingPlans.remove(planId)
        return completedTask
    }

    fun completeTask(
        workflowId: String,
        taskId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String> = emptyMap(),
        completionRunSummary: String? = null,
    ): StructuredTask {
        val task = resolveTask(workflowId, taskId)
        tasksService.updateRelatedFiles(
            workflowId = workflowId,
            taskId = task.id,
            files = relatedFiles,
            auditContext = auditContext,
        )
        when {
            verificationResult != null -> tasksService.updateVerificationResult(
                workflowId = workflowId,
                taskId = task.id,
                verificationResult = verificationResult,
                auditContext = auditContext,
            )

            task.verificationResult != null -> tasksService.clearVerificationResult(
                workflowId = workflowId,
                taskId = task.id,
                auditContext = auditContext,
            )
        }
        if (task.awaitingCompletionConfirmation) {
            taskExecutionService.resolveWaitingConfirmationRun(
                workflowId = workflowId,
                taskId = task.id,
                summary = completionRunSummary ?: "Completed from shared task completion flow.",
            )
        }
        tasksService.transitionStatus(
            workflowId = workflowId,
            taskId = task.id,
            to = TaskStatus.COMPLETED,
            auditContext = auditContext,
        )
        return resolveTask(workflowId, taskId)
    }

    private fun resolveTask(workflowId: String, taskId: String): StructuredTask {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = taskId.trim().uppercase()
        return tasksService.parse(workflowId)
            .attachActiveExecutionRuns(workflow.taskExecutionRuns)
            .firstOrNull { task -> task.id == normalizedTaskId }
            ?: throw MissingStructuredTaskError(normalizedTaskId)
    }

    companion object {
        fun getInstance(project: Project): SpecTaskCompletionService = project.service()
    }
}
