package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.UUID

@Service(Service.Level.PROJECT)
class SpecTaskExecutionService(private val project: Project) {
    private val storage: SpecStorage by lazy { SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy { SpecTasksService(project) }

    data class LegacyTaskExecutionMigrationResult(
        val workflow: SpecWorkflow,
        val migratedRuns: List<TaskExecutionRun>,
    ) {
        val migrated: Boolean
            get() = migratedRuns.isNotEmpty()
    }

    fun listRuns(workflowId: String, taskId: String? = null): List<TaskExecutionRun> {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = taskId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTaskId)
        return workflow.taskExecutionRuns
            .asSequence()
            .filter { run -> normalizedTaskId == null || run.taskId == normalizedTaskId }
            .sortedWith(compareByDescending<TaskExecutionRun> { it.startedAt }.thenByDescending { it.runId })
            .toList()
    }

    fun createRun(
        workflowId: String,
        taskId: String,
        status: TaskExecutionRunStatus,
        trigger: ExecutionTrigger,
        startedAt: String = Instant.now().toString(),
        finishedAt: String? = null,
        summary: String? = null,
    ): TaskExecutionRun {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        return createRun(
            workflow = workflow,
            taskId = taskId,
            status = status,
            trigger = trigger,
            startedAt = startedAt,
            finishedAt = finishedAt,
            summary = summary,
        ).second
    }

    fun updateRunStatus(
        workflowId: String,
        runId: String,
        status: TaskExecutionRunStatus,
        finishedAt: String? = null,
        summary: String? = null,
    ): TaskExecutionRun {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedRunId = runId.trim().takeIf(String::isNotBlank)
            ?: throw MissingTaskExecutionRunError(runId)
        val existingRun = workflow.taskExecutionRuns.firstOrNull { run -> run.runId == normalizedRunId }
            ?: throw MissingTaskExecutionRunError(normalizedRunId)
        if (!existingRun.status.canTransitionTo(status)) {
            throw InvalidTaskExecutionRunTransitionError(existingRun.runId, existingRun.status, status)
        }
        val resolvedFinishedAt = when {
            finishedAt?.isNotBlank() == true -> finishedAt.trim()
            status.isTerminal() -> Instant.now().toString()
            else -> null
        }
        val normalizedSummary = summary?.trim()?.takeIf(String::isNotBlank) ?: existingRun.summary
        val updatedRun = existingRun.copy(
            status = status,
            finishedAt = resolvedFinishedAt,
            summary = normalizedSummary,
        )
        val updatedWorkflow = workflow.copy(
            taskExecutionRuns = replaceRun(workflow.taskExecutionRuns, updatedRun),
            updatedAt = System.currentTimeMillis(),
        )
        storage.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = SpecAuditEventType.TASK_EXECUTION_RUN_STATUS_CHANGED,
            details = linkedMapOf(
                "runId" to updatedRun.runId,
                "taskId" to updatedRun.taskId,
                "fromStatus" to existingRun.status.name,
                "toStatus" to updatedRun.status.name,
                "trigger" to updatedRun.trigger.name,
                "startedAt" to updatedRun.startedAt,
                "finishedAt" to (updatedRun.finishedAt ?: ""),
                "summary" to (updatedRun.summary ?: ""),
            ),
        ).getOrThrow()
        return updatedRun
    }

    fun migrateLegacyInProgressTasks(workflow: SpecWorkflow): LegacyTaskExecutionMigrationResult {
        val tasks = tasksService.parse(workflow.id)
        if (tasks.isEmpty()) {
            return LegacyTaskExecutionMigrationResult(workflow = workflow, migratedRuns = emptyList())
        }

        var currentWorkflow = workflow
        val migratedRuns = mutableListOf<TaskExecutionRun>()
        tasks.filter { task -> task.status == TaskStatus.IN_PROGRESS }
            .sortedBy(StructuredTask::id)
            .forEach { task ->
                if (currentWorkflow.taskExecutionRuns.any { run ->
                        run.taskId == task.id && !run.status.isTerminal()
                    }
                ) {
                    return@forEach
                }
                val (updatedWorkflow, run) = createRun(
                    workflow = currentWorkflow,
                    taskId = task.id,
                    status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                    trigger = ExecutionTrigger.SYSTEM_RECOVERY,
                    summary = "Recovered from legacy task status IN_PROGRESS.",
                )
                currentWorkflow = updatedWorkflow
                migratedRuns += run
            }
        return LegacyTaskExecutionMigrationResult(
            workflow = currentWorkflow,
            migratedRuns = migratedRuns,
        )
    }

    private fun createRun(
        workflow: SpecWorkflow,
        taskId: String,
        status: TaskExecutionRunStatus,
        trigger: ExecutionTrigger,
        startedAt: String = Instant.now().toString(),
        finishedAt: String? = null,
        summary: String? = null,
    ): Pair<SpecWorkflow, TaskExecutionRun> {
        val normalizedTaskId = normalizeTaskId(taskId)
        ensureTaskExists(workflow.id, normalizedTaskId)
        if (workflow.taskExecutionRuns.any { run ->
                run.taskId == normalizedTaskId && !run.status.isTerminal()
            }
        ) {
            throw ActiveTaskExecutionRunExistsError(normalizedTaskId)
        }

        val normalizedStartedAt = startedAt.trim().takeIf(String::isNotBlank) ?: Instant.now().toString()
        val normalizedFinishedAt = when {
            finishedAt?.isNotBlank() == true -> finishedAt.trim()
            status.isTerminal() -> normalizedStartedAt
            else -> null
        }
        val normalizedSummary = summary?.trim()?.takeIf(String::isNotBlank)
        val run = TaskExecutionRun(
            runId = buildRunId(normalizedTaskId),
            taskId = normalizedTaskId,
            status = status,
            trigger = trigger,
            startedAt = normalizedStartedAt,
            finishedAt = normalizedFinishedAt,
            summary = normalizedSummary,
        )
        val updatedWorkflow = workflow.copy(
            taskExecutionRuns = appendRun(workflow.taskExecutionRuns, run),
            updatedAt = System.currentTimeMillis(),
        )
        storage.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = SpecAuditEventType.TASK_EXECUTION_RUN_CREATED,
            details = linkedMapOf(
                "runId" to run.runId,
                "taskId" to run.taskId,
                "status" to run.status.name,
                "trigger" to run.trigger.name,
                "startedAt" to run.startedAt,
                "finishedAt" to (run.finishedAt ?: ""),
                "summary" to (run.summary ?: ""),
                "migratedFromStatus" to if (trigger == ExecutionTrigger.SYSTEM_RECOVERY) TaskStatus.IN_PROGRESS.name else "",
            ),
        ).getOrThrow()
        return updatedWorkflow to run
    }

    private fun ensureTaskExists(workflowId: String, taskId: String) {
        val exists = tasksService.parse(workflowId).any { task -> task.id == taskId }
        require(exists) { "Task $taskId does not exist in workflow $workflowId" }
    }

    private fun appendRun(
        existingRuns: List<TaskExecutionRun>,
        run: TaskExecutionRun,
    ): List<TaskExecutionRun> {
        return (existingRuns + run)
            .sortedWith(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
    }

    private fun replaceRun(
        existingRuns: List<TaskExecutionRun>,
        updatedRun: TaskExecutionRun,
    ): List<TaskExecutionRun> {
        return existingRuns
            .map { run -> if (run.runId == updatedRun.runId) updatedRun else run }
            .sortedWith(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
    }

    private fun buildRunId(taskId: String): String {
        return "run-${taskId.lowercase()}-${UUID.randomUUID()}"
    }

    private fun normalizeTaskId(taskId: String): String {
        val normalizedTaskId = taskId.trim().uppercase()
        require(TASK_ID_REGEX.matches(normalizedTaskId)) {
            "taskId must use the format T-001."
        }
        return normalizedTaskId
    }

    companion object {
        private val TASK_ID_REGEX = Regex("""^T-\d{3}$""")

        fun getInstance(project: Project): SpecTaskExecutionService = project.service()
    }
}
