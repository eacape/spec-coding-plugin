package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextSnapshot
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service(Service.Level.PROJECT)
class SpecTaskExecutionService(private val project: Project) {
    data class TaskExecutionChatRequest(
        val providerId: String?,
        val userInput: String,
        val modelId: String?,
        val contextSnapshot: ContextSnapshot?,
        val operationMode: OperationMode,
        val requestId: String,
    )

    data class TaskAiExecutionResult(
        val run: TaskExecutionRun,
        val sessionId: String,
        val sessionTitle: String,
        val requestId: String,
        val prompt: String,
        val assistantReply: String,
        val previousRunId: String? = null,
    )

    data class LegacyTaskExecutionMigrationResult(
        val workflow: SpecWorkflow,
        val migratedRuns: List<TaskExecutionRun>,
    ) {
        val migrated: Boolean
            get() = migratedRuns.isNotEmpty()
    }

    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null
    private var _relatedFilesResolverOverride: ((String, List<String>) -> List<String>)? = null
    private var _projectServiceOverride: SpecCodingProjectService? = null
    private var _sessionManagerOverride: SessionManager? = null
    private var _chatExecutorOverride: (suspend (TaskExecutionChatRequest) -> LlmResponse)? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy { _tasksServiceOverride ?: SpecTasksService(project) }
    private val relatedFilesResolver: (String, List<String>) -> List<String> by lazy {
        _relatedFilesResolverOverride ?: { taskId, existingRelatedFiles ->
            SpecRelatedFilesService.getInstance(project).suggestRelatedFiles(taskId, existingRelatedFiles)
        }
    }
    private val projectService: SpecCodingProjectService by lazy { _projectServiceOverride ?: SpecCodingProjectService(project) }
    private val sessionManager: SessionManager by lazy { _sessionManagerOverride ?: SessionManager(project) }
    private val chatExecutor: suspend (TaskExecutionChatRequest) -> LlmResponse by lazy {
        _chatExecutorOverride ?: { request ->
            projectService.chat(
                providerId = request.providerId,
                userInput = request.userInput,
                modelId = request.modelId,
                contextSnapshot = request.contextSnapshot,
                operationMode = request.operationMode,
                requestId = request.requestId,
                onChunk = {},
            )
        }
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        tasksService: SpecTasksService,
        relatedFilesResolver: (String, List<String>) -> List<String>,
        projectService: SpecCodingProjectService,
        sessionManager: SessionManager,
        chatExecutor: suspend (TaskExecutionChatRequest) -> LlmResponse,
    ) : this(project) {
        _storageOverride = storage
        _tasksServiceOverride = tasksService
        _relatedFilesResolverOverride = relatedFilesResolver
        _projectServiceOverride = projectService
        _sessionManagerOverride = sessionManager
        _chatExecutorOverride = chatExecutor
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

    fun startAiExecution(
        workflowId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        sessionId: String? = null,
        sessionSource: WorkflowChatEntrySource = WorkflowChatEntrySource.TASK_PANEL,
        auditContext: Map<String, String> = emptyMap(),
    ): TaskAiExecutionResult {
        return executeWithAi(
            workflowId = workflowId,
            taskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            trigger = ExecutionTrigger.USER_EXECUTE,
            previousRunId = null,
            sessionId = sessionId,
            sessionSource = sessionSource,
            auditContext = auditContext,
        )
    }

    fun retryAiExecution(
        workflowId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        previousRunId: String? = null,
        sessionId: String? = null,
        sessionSource: WorkflowChatEntrySource = WorkflowChatEntrySource.TASK_PANEL,
        auditContext: Map<String, String> = emptyMap(),
    ): TaskAiExecutionResult {
        return executeWithAi(
            workflowId = workflowId,
            taskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            trigger = ExecutionTrigger.USER_RETRY,
            previousRunId = previousRunId,
            sessionId = sessionId,
            sessionSource = sessionSource,
            auditContext = auditContext,
        )
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

    fun resolveWaitingConfirmationRun(
        workflowId: String,
        taskId: String,
        summary: String? = null,
    ): TaskExecutionRun? {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = normalizeTaskId(taskId)
        val run = workflow.taskExecutionRuns
            .asSequence()
            .filter { candidate ->
                candidate.taskId == normalizedTaskId &&
                    candidate.status == TaskExecutionRunStatus.WAITING_CONFIRMATION
            }
            .sortedBy(TaskExecutionRun::startedAt)
            .lastOrNull()
            ?: return null
        return updateRunStatus(
            workflowId = workflowId,
            runId = run.runId,
            status = TaskExecutionRunStatus.SUCCEEDED,
            summary = summary,
        )
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
                val existingActiveRun = currentWorkflow.taskExecutionRuns.any { run ->
                    run.taskId == task.id && !run.status.isTerminal()
                }
                if (!existingActiveRun) {
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
                normalizeLegacyInProgressTask(
                    workflowId = workflow.id,
                    taskId = task.id,
                )
            }
        return LegacyTaskExecutionMigrationResult(
            workflow = currentWorkflow,
            migratedRuns = migratedRuns,
        )
    }

    private fun executeWithAi(
        workflowId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        trigger: ExecutionTrigger,
        previousRunId: String?,
        sessionId: String?,
        sessionSource: WorkflowChatEntrySource,
        auditContext: Map<String, String>,
    ): TaskAiExecutionResult {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val task = resolveTask(workflowId, taskId)
        val previousRun = resolvePreviousRun(workflow, task.id, previousRunId, trigger)
        val suggestedRelatedFiles = relatedFilesResolver(task.id, task.relatedFiles)
        val queuedRun = createRun(
            workflow = workflow,
            taskId = task.id,
            status = TaskExecutionRunStatus.QUEUED,
            trigger = trigger,
            summary = buildQueuedSummary(trigger, previousRun),
        ).second
        val session = resolveExecutionSession(
            workflow = workflow,
            task = task,
            providerId = providerId,
            trigger = trigger,
            sessionId = sessionId,
            sessionSource = sessionSource,
        )
        val requestId = UUID.randomUUID().toString()
        val prompt = buildExecutionPrompt(
            workflow = workflow,
            task = task,
            run = queuedRun,
            trigger = trigger,
            previousRun = previousRun,
            suggestedRelatedFiles = suggestedRelatedFiles,
        )
        val contextSnapshot = buildRelatedFilesContextSnapshot(suggestedRelatedFiles)
        val executionAuditContext = buildExecutionAuditContext(
            auditContext = auditContext,
            task = task,
            run = queuedRun,
            trigger = trigger,
            sessionId = session.id,
            providerId = providerId,
            modelId = modelId,
            requestId = requestId,
            previousRunId = previousRun?.runId,
        )

        persistExecutionMessage(
            sessionId = session.id,
            role = ConversationRole.USER,
            content = prompt,
            run = queuedRun,
            workflowId = workflowId,
            requestId = requestId,
            providerId = providerId,
            modelId = modelId,
            previousRunId = previousRun?.runId,
        )

        normalizeTaskLifecycleForExecution(workflowId, task, executionAuditContext)

        return try {
            updateRunStatus(
                workflowId = workflowId,
                runId = queuedRun.runId,
                status = TaskExecutionRunStatus.RUNNING,
            )
            val response = runBlocking {
                chatExecutor(
                    TaskExecutionChatRequest(
                        providerId = providerId,
                        userInput = prompt,
                        modelId = modelId,
                        contextSnapshot = contextSnapshot,
                        operationMode = operationMode,
                        requestId = requestId,
                    ),
                )
            }
            val assistantReply = response.content.trim()
            val completedRun = updateRunStatus(
                workflowId = workflowId,
                runId = queuedRun.runId,
                status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                summary = summarizeAssistantReply(assistantReply),
            )
            persistExecutionMessage(
                sessionId = session.id,
                role = ConversationRole.ASSISTANT,
                content = assistantReply.ifBlank { summarizeAssistantReply(response.content) },
                run = completedRun,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRun?.runId,
            )
            TaskAiExecutionResult(
                run = completedRun,
                sessionId = session.id,
                sessionTitle = session.title,
                requestId = requestId,
                prompt = prompt,
                assistantReply = assistantReply,
                previousRunId = previousRun?.runId,
            )
        } catch (error: Throwable) {
            val failureSummary = summarizeFailure(error)
            persistExecutionMessage(
                sessionId = session.id,
                role = ConversationRole.SYSTEM,
                content = failureSummary,
                run = queuedRun,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRun?.runId,
            )
            markRunFailed(workflowId, queuedRun.runId, failureSummary)
            transitionTaskToBlocked(
                workflowId = workflowId,
                taskId = task.id,
                reason = failureSummary,
                auditContext = executionAuditContext,
            )
            throw error
        }
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

    private fun resolveExecutionSession(
        workflow: SpecWorkflow,
        task: StructuredTask,
        providerId: String?,
        trigger: ExecutionTrigger,
        sessionId: String?,
        sessionSource: WorkflowChatEntrySource,
    ) = sessionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { existingSessionId ->
            sessionManager.updateWorkflowChatBinding(
                sessionId = existingSessionId,
                binding = WorkflowChatBinding(
                    workflowId = workflow.id,
                    taskId = task.id,
                    focusedStage = StageId.IMPLEMENT,
                    source = sessionSource,
                    actionIntent = when (trigger) {
                        ExecutionTrigger.USER_EXECUTE -> WorkflowChatActionIntent.EXECUTE_TASK
                        ExecutionTrigger.USER_RETRY -> WorkflowChatActionIntent.RETRY_TASK
                        ExecutionTrigger.SYSTEM_RECOVERY -> WorkflowChatActionIntent.DISCUSS
                    },
                ),
            ).getOrThrow()
        }
        ?: sessionManager.createSession(
            title = buildSessionTitle(workflow, task, trigger),
            specTaskId = workflow.id,
            modelProvider = providerId,
            workflowChatBinding = WorkflowChatBinding(
                workflowId = workflow.id,
                taskId = task.id,
                focusedStage = StageId.IMPLEMENT,
                source = sessionSource,
                actionIntent = when (trigger) {
                    ExecutionTrigger.USER_EXECUTE -> WorkflowChatActionIntent.EXECUTE_TASK
                    ExecutionTrigger.USER_RETRY -> WorkflowChatActionIntent.RETRY_TASK
                    ExecutionTrigger.SYSTEM_RECOVERY -> WorkflowChatActionIntent.DISCUSS
                },
            ),
        ).getOrThrow()

    private fun buildSessionTitle(
        workflow: SpecWorkflow,
        task: StructuredTask,
        trigger: ExecutionTrigger,
    ): String {
        val workflowLabel = workflow.title.trim().ifBlank { workflow.id }
        val triggerLabel = when (trigger) {
            ExecutionTrigger.USER_EXECUTE -> "Execute"
            ExecutionTrigger.USER_RETRY -> "Retry"
            ExecutionTrigger.SYSTEM_RECOVERY -> "Recovery"
        }
        return "$workflowLabel · ${task.id} · $triggerLabel".take(MAX_SESSION_TITLE_LENGTH)
    }

    private fun buildExecutionPrompt(
        workflow: SpecWorkflow,
        task: StructuredTask,
        run: TaskExecutionRun,
        trigger: ExecutionTrigger,
        previousRun: TaskExecutionRun?,
        suggestedRelatedFiles: List<String>,
    ): String {
        val taskSummary = listOf(
            "Task ID: ${task.id}",
            "Task Title: ${task.title}",
            "Task Status: ${task.status.name}",
            "Priority: ${task.priority.name}",
        )
        val dependencySummary = buildDependencySummary(workflow.id, task)
        val documentSummaries = buildDocumentSummaries(workflow)
        val clarificationConclusions = extractClarificationConclusions(workflow)

        return buildString {
            appendLine("Interaction mode: workflow")
            appendLine("Workflow=${workflow.id} (docs: .spec-coding/specs/${workflow.id}/{requirements,design,tasks}.md)")
            appendLine(SpecCodingBundle.message("toolwindow.chat.mode.spec.instruction"))
            appendLine("Execution action: ${trigger.toExecutionActionName()}")
            appendLine("Run ID: ${run.runId}")
            appendLine()
            appendLine("## Task")
            taskSummary.forEach(::appendLine)
            appendLine()
            appendLine("## Stage Context")
            appendLine("Current phase: ${workflow.currentPhase.name}")
            appendLine("Current stage: ${workflow.currentStage.name}")
            appendLine()
            appendLine("## Dependencies")
            if (dependencySummary.isEmpty()) {
                appendLine("- None")
            } else {
                dependencySummary.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("## Artifact Summaries")
            if (documentSummaries.isEmpty()) {
                appendLine("- No artifact summaries available.")
            } else {
                documentSummaries.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("## Confirmed Clarification Conclusions")
            if (clarificationConclusions.isEmpty()) {
                appendLine("- None recorded.")
            } else {
                clarificationConclusions.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("## Candidate Related Files")
            if (suggestedRelatedFiles.isEmpty()) {
                appendLine("- None suggested.")
            } else {
                suggestedRelatedFiles.forEach { line -> appendLine("- $line") }
            }
            if (previousRun != null) {
                appendLine()
                appendLine("## Previous Execution")
                appendLine("Previous run ID: ${previousRun.runId}")
                appendLine("Previous status: ${previousRun.status.name}")
                previousRun.summary?.takeIf(String::isNotBlank)?.let { summary ->
                    appendLine("Previous summary: ${summary.trim()}")
                }
            }
            appendLine()
            appendLine("## Execution Request")
            appendLine("Use the task-scoped context above to execute this structured task.")
            appendLine("Keep the response grounded in this repository and this task only.")
            appendLine("Do not mark the task completed automatically.")
            appendLine("End with:")
            appendLine("1. a concise implementation summary,")
            appendLine("2. suggested relatedFiles entries,")
            appendLine("3. an optional verificationResult draft if verification evidence exists.")
        }.trimEnd()
    }

    private fun buildDependencySummary(
        workflowId: String,
        task: StructuredTask,
    ): List<String> {
        if (task.dependsOn.isEmpty()) {
            return emptyList()
        }
        val tasksById = tasksService.parse(workflowId).associateBy(StructuredTask::id)
        return task.dependsOn.map { dependencyId ->
            val dependency = tasksById[dependencyId]
            if (dependency == null) {
                "$dependencyId · missing"
            } else {
                "${dependency.id} · ${dependency.status.name} · ${dependency.title}"
            }
        }
    }

    private fun buildDocumentSummaries(workflow: SpecWorkflow): List<String> {
        return listOf(SpecPhase.SPECIFY, SpecPhase.DESIGN, SpecPhase.IMPLEMENT)
            .mapNotNull { phase ->
                val document = workflow.documents[phase] ?: return@mapNotNull null
                val fileName = phase.outputFileName
                val summary = summarizeDocument(document.content)
                "$fileName: $summary"
            }
    }

    private fun summarizeDocument(content: String): String {
        return content.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    !line.startsWith("```")
            }
            .take(6)
            .joinToString(" ")
            .replace(WHITESPACE_REGEX, " ")
            .take(DOCUMENT_SUMMARY_CHAR_LIMIT)
            .ifBlank { "No summary available." }
    }

    private fun extractClarificationConclusions(workflow: SpecWorkflow): List<String> {
        val fromRetryState = workflow.clarificationRetryState
            ?.takeIf { retry -> retry.confirmed }
            ?.confirmedContext
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.startsWith("- ") || line.startsWith("* ") || line.matches(ORDERED_LIST_REGEX) }
            .map { line -> line.removePrefix("- ").removePrefix("* ").replace(ORDERED_LIST_REGEX, "").trim() }

        val fromDocuments = workflow.documents.values
            .asSequence()
            .flatMap { document -> extractClarificationSections(document.content).asSequence() }

        return (fromRetryState + fromDocuments)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_CLARIFICATION_LINES)
            .toList()
    }

    private fun extractClarificationSections(content: String): List<String> {
        val collected = mutableListOf<String>()
        var inClarificationSection = false
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("## ") || line.startsWith("### ") -> {
                    val heading = line.removePrefix("## ").removePrefix("### ").trim()
                    inClarificationSection = heading.equals("Clarifications", ignoreCase = true) ||
                        heading.equals("Decisions", ignoreCase = true) ||
                        heading.equals("Decisions from Clarification", ignoreCase = true)
                }

                inClarificationSection && (line.startsWith("- ") || line.startsWith("* ")) -> {
                    collected += line.removePrefix("- ").removePrefix("* ").trim()
                }

                inClarificationSection && line.matches(ORDERED_LIST_REGEX) -> {
                    collected += line.replace(ORDERED_LIST_REGEX, "").trim()
                }
            }
        }
        return collected
    }

    private fun buildRelatedFilesContextSnapshot(relatedFiles: List<String>): ContextSnapshot? {
        val projectRoot = project.basePath?.trim()?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        val items = relatedFiles
            .take(MAX_CONTEXT_FILES)
            .mapNotNull { relativePath ->
                val absolutePath = projectRoot.resolve(relativePath).normalize()
                if (!absolutePath.startsWith(projectRoot) || !Files.isRegularFile(absolutePath)) {
                    return@mapNotNull null
                }
                val content = runCatching {
                    Files.readString(absolutePath)
                }.getOrNull()?.take(MAX_CONTEXT_FILE_CHARS)?.trim()
                    ?: return@mapNotNull null
                if (content.isBlank()) {
                    return@mapNotNull null
                }
                ContextItem(
                    type = ContextType.REFERENCED_FILE,
                    label = relativePath,
                    content = content,
                    filePath = absolutePath.toString(),
                )
            }
        if (items.isEmpty()) {
            return null
        }
        return ContextSnapshot(
            items = items,
            tokenBudget = items.sumOf(ContextItem::tokenEstimate).coerceAtLeast(1),
        )
    }

    private fun persistExecutionMessage(
        sessionId: String,
        role: ConversationRole,
        content: String,
        run: TaskExecutionRun,
        workflowId: String,
        requestId: String,
        providerId: String?,
        modelId: String?,
        previousRunId: String?,
    ) {
        sessionManager.addMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            metadataJson = TaskExecutionSessionMetadataCodec.encode(
                run = run,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRunId,
            ),
        ).getOrThrow()
    }

    private fun normalizeTaskLifecycleForExecution(
        workflowId: String,
        task: StructuredTask,
        auditContext: Map<String, String>,
    ) {
        if (task.status == TaskStatus.BLOCKED || task.status == TaskStatus.IN_PROGRESS) {
            tasksService.transitionStatus(
                workflowId = workflowId,
                taskId = task.id,
                to = TaskStatus.PENDING,
                auditContext = auditContext,
            )
        }
    }

    private fun transitionTaskToBlocked(
        workflowId: String,
        taskId: String,
        reason: String,
        auditContext: Map<String, String>,
    ) {
        val currentTask = resolveTask(workflowId, taskId)
        if (currentTask.status == TaskStatus.PENDING || currentTask.status == TaskStatus.IN_PROGRESS) {
            tasksService.transitionStatus(
                workflowId = workflowId,
                taskId = taskId,
                to = TaskStatus.BLOCKED,
                reason = reason,
                auditContext = auditContext,
            )
        }
    }

    private fun buildExecutionAuditContext(
        auditContext: Map<String, String>,
        task: StructuredTask,
        run: TaskExecutionRun,
        trigger: ExecutionTrigger,
        sessionId: String,
        providerId: String?,
        modelId: String?,
        requestId: String,
        previousRunId: String?,
    ): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            putAll(auditContext)
            put("taskExecutionAction", trigger.toExecutionActionName())
            put("taskExecutionRunId", run.runId)
            put("taskExecutionTrigger", trigger.name)
            put("taskSessionId", sessionId)
            put("taskRequestId", requestId)
            put("taskStatusBeforeExecution", task.status.name)
            providerId?.takeIf(String::isNotBlank)?.let { put("providerId", it.trim()) }
            modelId?.takeIf(String::isNotBlank)?.let { put("modelId", it.trim()) }
            previousRunId?.takeIf(String::isNotBlank)?.let { put("previousRunId", it.trim()) }
        }
    }

    private fun normalizeLegacyInProgressTask(
        workflowId: String,
        taskId: String,
    ) {
        val currentTask = resolveTask(workflowId, taskId)
        if (currentTask.status != TaskStatus.IN_PROGRESS) {
            return
        }
        tasksService.transitionStatus(
            workflowId = workflowId,
            taskId = taskId,
            to = TaskStatus.PENDING,
            reason = "Recovered legacy IN_PROGRESS status into execution run state.",
            auditContext = mapOf(
                "taskExecutionAction" to ExecutionTrigger.SYSTEM_RECOVERY.toExecutionActionName(),
            ),
        )
    }

    private fun resolvePreviousRun(
        workflow: SpecWorkflow,
        taskId: String,
        previousRunId: String?,
        trigger: ExecutionTrigger,
    ): TaskExecutionRun? {
        if (trigger != ExecutionTrigger.USER_RETRY) {
            return null
        }
        val explicitRunId = previousRunId?.trim()?.takeIf(String::isNotBlank)
        val previousRun = when {
            explicitRunId != null -> workflow.taskExecutionRuns.firstOrNull { run -> run.runId == explicitRunId }
            else -> workflow.taskExecutionRuns
                .filter { run -> run.taskId == taskId && run.status.isTerminal() }
                .maxWithOrNull(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
        }
        if (explicitRunId != null && previousRun == null) {
            throw MissingTaskExecutionRunError(explicitRunId)
        }
        return previousRun
    }

    private fun resolveTask(workflowId: String, taskId: String): StructuredTask {
        val normalizedTaskId = normalizeTaskId(taskId)
        return tasksService.parse(workflowId)
            .firstOrNull { task -> task.id == normalizedTaskId }
            ?: throw MissingStructuredTaskError(normalizedTaskId)
    }

    private fun markRunFailed(
        workflowId: String,
        runId: String,
        failureSummary: String,
    ) {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val currentRun = workflow.taskExecutionRuns.firstOrNull { run -> run.runId == runId } ?: return
        if (currentRun.status.isTerminal() || !currentRun.status.canTransitionTo(TaskExecutionRunStatus.FAILED)) {
            return
        }
        updateRunStatus(
            workflowId = workflowId,
            runId = runId,
            status = TaskExecutionRunStatus.FAILED,
            summary = failureSummary,
        )
    }

    private fun summarizeAssistantReply(content: String): String {
        return content
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .replace(WHITESPACE_REGEX, " ")
            .take(RUN_SUMMARY_CHAR_LIMIT)
            .ifBlank { "AI execution finished. Review the task session for details." }
    }

    private fun summarizeFailure(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) {
            "AI execution failed."
        } else {
            "AI execution failed: ${message.take(RUN_SUMMARY_CHAR_LIMIT)}"
        }
    }

    private fun buildQueuedSummary(
        trigger: ExecutionTrigger,
        previousRun: TaskExecutionRun?,
    ): String {
        val action = when (trigger) {
            ExecutionTrigger.USER_EXECUTE -> "Queued spec page AI execution."
            ExecutionTrigger.USER_RETRY -> "Queued spec page AI retry."
            ExecutionTrigger.SYSTEM_RECOVERY -> "Recovered execution run."
        }
        val previousSummary = previousRun?.runId?.let { runId -> " Previous run: $runId." }.orEmpty()
        return action + previousSummary
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
        private val ORDERED_LIST_REGEX = Regex("""^\d+\.\s+""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val MAX_SESSION_TITLE_LENGTH = 80
        private const val MAX_CONTEXT_FILES = 6
        private const val MAX_CONTEXT_FILE_CHARS = 12_000
        private const val RUN_SUMMARY_CHAR_LIMIT = 260
        private const val DOCUMENT_SUMMARY_CHAR_LIMIT = 240
        private const val MAX_CLARIFICATION_LINES = 12

        fun getInstance(project: Project): SpecTaskExecutionService = project.service()
    }
}

private fun ExecutionTrigger.toExecutionActionName(): String {
    return when (this) {
        ExecutionTrigger.USER_EXECUTE -> "EXECUTE_WITH_AI"
        ExecutionTrigger.USER_RETRY -> "RETRY_EXECUTION"
        ExecutionTrigger.SYSTEM_RECOVERY -> "SYSTEM_RECOVERY"
    }
}

internal object TaskExecutionSessionMetadataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    internal data class DecodedMetadata(
        val runId: String?,
        val taskId: String?,
        val workflowId: String?,
        val trigger: ExecutionTrigger?,
        val requestId: String?,
        val providerId: String?,
        val modelId: String?,
        val previousRunId: String?,
    )

    fun encode(
        run: TaskExecutionRun,
        workflowId: String,
        requestId: String,
        providerId: String?,
        modelId: String?,
        previousRunId: String?,
    ): String {
        val payload = linkedMapOf<String, JsonPrimitive>(
            "format" to JsonPrimitive("task_execution_session_v1"),
            "runId" to JsonPrimitive(run.runId),
            "taskId" to JsonPrimitive(run.taskId),
            "workflowId" to JsonPrimitive(workflowId),
            "trigger" to JsonPrimitive(run.trigger.name),
            "requestId" to JsonPrimitive(requestId),
        ).apply {
            providerId?.takeIf(String::isNotBlank)?.let { put("providerId", JsonPrimitive(it.trim())) }
            modelId?.takeIf(String::isNotBlank)?.let { put("modelId", JsonPrimitive(it.trim())) }
            previousRunId?.takeIf(String::isNotBlank)?.let { put("previousRunId", JsonPrimitive(it.trim())) }
        }
        return JsonObject(payload).toString()
    }

    fun decode(metadataJson: String?): DecodedMetadata {
        if (metadataJson.isNullOrBlank()) {
            return DecodedMetadata(null, null, null, null, null, null, null, null)
        }
        val root = runCatching { json.parseToJsonElement(metadataJson) as? JsonObject }
            .getOrNull()
            ?: return DecodedMetadata(null, null, null, null, null, null, null, null)
        val trigger = root["trigger"]
            ?.toString()
            ?.trim('"')
            ?.let { raw -> ExecutionTrigger.entries.firstOrNull { entry -> entry.name == raw } }
        return DecodedMetadata(
            runId = root["runId"]?.toString()?.trim('"'),
            taskId = root["taskId"]?.toString()?.trim('"'),
            workflowId = root["workflowId"]?.toString()?.trim('"'),
            trigger = trigger,
            requestId = root["requestId"]?.toString()?.trim('"'),
            providerId = root["providerId"]?.toString()?.trim('"'),
            modelId = root["modelId"]?.toString()?.trim('"'),
            previousRunId = root["previousRunId"]?.toString()?.trim('"'),
        )
    }
}
