package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.attachActiveExecutionRuns
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class WorkflowChatContextAssembler(private val project: Project) {
    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy { _tasksServiceOverride ?: SpecTasksService.getInstance(project) }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        tasksService: SpecTasksService,
    ) : this(project) {
        _storageOverride = storage
        _tasksServiceOverride = tasksService
    }

    fun buildPrompt(
        binding: WorkflowChatBinding?,
        userInstruction: String,
    ): String {
        val normalizedBinding = binding?.normalizedOrNull()
        if (normalizedBinding == null) {
            return buildFallbackPrompt(
                workflowId = null,
                userInstruction = userInstruction,
            )
        }

        val context = assemble(normalizedBinding)
        if (context == null) {
            return buildFallbackPrompt(
                workflowId = normalizedBinding.workflowId,
                userInstruction = userInstruction,
                contextNotice = "Workflow context is unavailable. Ground the response in the repo and call out any missing workflow state.",
            )
        }

        return buildString {
            appendLine("Interaction mode: workflow")
            appendLine("Workflow=${context.workflowId} (docs: .spec-coding/specs/${context.workflowId}/{requirements,design,tasks}.md)")
            appendLine(SpecCodingBundle.message("toolwindow.chat.mode.spec.instruction"))
            appendLine()
            appendLine("## Workflow Context")
            appendLine("Title: ${context.workflowTitle}")
            appendLine("Current phase: ${context.currentPhase.name}")
            appendLine("Current stage: ${context.currentStage.name}")
            appendLine("Focused stage: ${context.focusedStage.name}")
            appendLine("Action intent: ${context.actionIntent.name}")
            appendLine()
            appendLine("## Bound Task")
            appendTaskSection(context)
            appendLine()
            appendListSection(
                title = "## Artifact Summaries",
                items = context.artifactSummaries,
                emptyText = "No artifact summaries available.",
            )
            appendLine()
            appendListSection(
                title = "## Confirmed Clarification Conclusions",
                items = context.clarificationConclusions,
                emptyText = "None recorded.",
            )
            appendLine()
            appendListSection(
                title = "## Recent Execution Runs",
                items = context.recentRuns,
                emptyText = "No execution runs recorded.",
            )
            appendLine()
            appendLine("Use the workflow context above. If critical details are missing, say what is missing instead of inventing it.")
            appendLine("User instruction:")
            appendLine(userInstruction.trim().ifBlank { "No user instruction provided." })
        }.trimEnd()
    }

    internal fun assemble(binding: WorkflowChatBinding): WorkflowChatPromptContext? {
        val normalizedBinding = binding.normalizedOrNull() ?: return null
        val workflow = storage.loadWorkflow(normalizedBinding.workflowId).getOrNull() ?: return null
        val tasks = runCatching {
            tasksService.parse(workflow.id)
                .attachActiveExecutionRuns(workflow.taskExecutionRuns)
                .sortedBy(StructuredTask::id)
        }.getOrDefault(emptyList())
        val boundTaskId = normalizedBinding.taskId?.trim()?.ifBlank { null }
        val boundTask = boundTaskId?.let { taskId -> tasks.firstOrNull { task -> task.id == taskId } }

        return WorkflowChatPromptContext(
            workflowId = workflow.id,
            workflowTitle = workflow.title.trim().ifBlank { workflow.id },
            currentPhase = workflow.currentPhase,
            currentStage = workflow.currentStage,
            focusedStage = normalizedBinding.focusedStage ?: workflow.currentStage,
            actionIntent = normalizedBinding.actionIntent,
            boundTaskId = boundTaskId,
            boundTask = boundTask,
            dependencySummary = boundTask?.let { buildDependencySummary(tasks, it) }.orEmpty(),
            artifactSummaries = buildDocumentSummaries(workflow),
            clarificationConclusions = extractClarificationConclusions(workflow),
            recentRuns = buildRecentRuns(workflow.taskExecutionRuns, boundTaskId),
        )
    }

    private fun StringBuilder.appendTaskSection(context: WorkflowChatPromptContext) {
        val boundTaskId = context.boundTaskId
        val boundTask = context.boundTask
        when {
            boundTaskId == null -> appendLine("- No task binding.")
            boundTask == null -> {
                appendLine("- Task ID: $boundTaskId")
                appendLine("- Task context unavailable because the task is missing from tasks.md.")
            }

            else -> {
                appendLine("Task ID: ${boundTask.id}")
                appendLine("Task Title: ${boundTask.title}")
                appendLine("Task Status: ${boundTask.status.name}")
                appendLine("Task Display Status: ${boundTask.displayStatus.name}")
                appendLine("Priority: ${boundTask.priority.name}")
                appendLine("Dependencies:")
                if (context.dependencySummary.isEmpty()) {
                    appendLine("- None")
                } else {
                    context.dependencySummary.forEach { dependency -> appendLine("- $dependency") }
                }
                appendLine("Related files:")
                if (boundTask.relatedFiles.isEmpty()) {
                    appendLine("- None")
                } else {
                    boundTask.relatedFiles.forEach { relatedFile -> appendLine("- $relatedFile") }
                }
                boundTask.activeExecutionRun?.let { run ->
                    appendLine("Active run: ${run.runId} | ${run.status.name}")
                }
            }
        }
    }

    private fun StringBuilder.appendListSection(
        title: String,
        items: List<String>,
        emptyText: String,
    ) {
        appendLine(title)
        if (items.isEmpty()) {
            appendLine("- $emptyText")
            return
        }
        items.forEach { item -> appendLine("- $item") }
    }

    private fun buildFallbackPrompt(
        workflowId: String?,
        userInstruction: String,
        contextNotice: String? = null,
    ): String {
        val workflowHint = if (workflowId.isNullOrBlank()) {
            "No active workflow. If needed, run /workflow <requirements> first."
        } else {
            "Workflow=$workflowId (docs: .spec-coding/specs/$workflowId/{requirements,design,tasks}.md)"
        }
        return buildString {
            appendLine("Interaction mode: workflow")
            appendLine(workflowHint)
            appendLine(SpecCodingBundle.message("toolwindow.chat.mode.spec.instruction"))
            contextNotice?.trim()?.takeIf(String::isNotBlank)?.let { notice ->
                appendLine(notice)
            }
            appendLine("User instruction:")
            appendLine(userInstruction.trim().ifBlank { "No user instruction provided." })
        }.trimEnd()
    }

    private fun buildDependencySummary(
        tasks: List<StructuredTask>,
        task: StructuredTask,
    ): List<String> {
        if (task.dependsOn.isEmpty()) {
            return emptyList()
        }
        val tasksById = tasks.associateBy(StructuredTask::id)
        return task.dependsOn.map { dependencyId ->
            val dependency = tasksById[dependencyId]
            if (dependency == null) {
                "$dependencyId | missing"
            } else {
                "${dependency.id} | ${dependency.displayStatus.name} | ${dependency.title}"
            }
        }
    }

    private fun buildDocumentSummaries(workflow: SpecWorkflow): List<String> {
        return listOf(SpecPhase.SPECIFY, SpecPhase.DESIGN, SpecPhase.IMPLEMENT)
            .mapNotNull { phase ->
                val document = workflow.documents[phase] ?: return@mapNotNull null
                "${phase.outputFileName}: ${summarizeDocument(document.content)}"
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
            .take(MAX_DOCUMENT_SUMMARY_LINES)
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
            .filter { line ->
                line.startsWith("- ") ||
                    line.startsWith("* ") ||
                    line.matches(ORDERED_LIST_REGEX)
            }
            .map { line ->
                line.removePrefix("- ")
                    .removePrefix("* ")
                    .replace(ORDERED_LIST_REGEX, "")
                    .trim()
            }

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

    private fun buildRecentRuns(
        runs: List<TaskExecutionRun>,
        boundTaskId: String?,
    ): List<String> {
        if (runs.isEmpty()) {
            return emptyList()
        }
        val sortedRuns = runs.sortedWith(
            compareByDescending<TaskExecutionRun> { it.startedAt }
                .thenByDescending { it.runId },
        )
        val prioritizedRuns = if (boundTaskId == null) {
            sortedRuns
        } else {
            val boundRuns = sortedRuns.filter { run -> run.taskId == boundTaskId }
            val otherRuns = sortedRuns.filterNot { run -> run.taskId == boundTaskId }
            boundRuns + otherRuns
        }

        return prioritizedRuns
            .distinctBy(TaskExecutionRun::runId)
            .take(MAX_RECENT_RUNS)
            .map { run ->
                val summary = run.summary?.trim()?.takeIf(String::isNotBlank) ?: "No summary recorded."
                "${run.runId} | ${run.taskId} | ${run.status.name} | ${run.trigger.name} | $summary"
            }
    }

    internal data class WorkflowChatPromptContext(
        val workflowId: String,
        val workflowTitle: String,
        val currentPhase: SpecPhase,
        val currentStage: StageId,
        val focusedStage: StageId,
        val actionIntent: WorkflowChatActionIntent,
        val boundTaskId: String?,
        val boundTask: StructuredTask?,
        val dependencySummary: List<String>,
        val artifactSummaries: List<String>,
        val clarificationConclusions: List<String>,
        val recentRuns: List<String>,
    )

    companion object {
        private const val DOCUMENT_SUMMARY_CHAR_LIMIT = 320
        private const val MAX_DOCUMENT_SUMMARY_LINES = 6
        private const val MAX_CLARIFICATION_LINES = 12
        private const val MAX_RECENT_RUNS = 3
        private val ORDERED_LIST_REGEX = Regex("^\\d+[.)]\\s+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun getInstance(project: Project): WorkflowChatContextAssembler = project.service()
    }
}
