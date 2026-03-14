package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.attachActiveExecutionRuns
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.Locale

sealed interface WorkflowChatTaskIntentResolution {
    data object NoMatch : WorkflowChatTaskIntentResolution

    data class Resolved(
        val workflowId: String,
        val actionIntent: WorkflowChatActionIntent,
        val task: StructuredTask,
        val matchSource: WorkflowChatTaskMatchSource,
    ) : WorkflowChatTaskIntentResolution

    data class Unresolved(
        val actionIntent: WorkflowChatActionIntent,
        val reason: WorkflowChatTaskIntentFailureReason,
        val referenceText: String? = null,
        val candidateTaskIds: List<String> = emptyList(),
    ) : WorkflowChatTaskIntentResolution
}

enum class WorkflowChatTaskMatchSource {
    EXPLICIT_TASK_ID,
    CURRENT_BINDING,
    NEXT_ACTIONABLE,
    TITLE_MATCH,
}

enum class WorkflowChatTaskIntentFailureReason {
    NO_ACTIVE_WORKFLOW,
    NO_TASKS,
    NO_CURRENT_TASK,
    NO_NEXT_TASK,
    TASK_NOT_FOUND,
    AMBIGUOUS_TASK,
}

@Service(Service.Level.PROJECT)
class WorkflowChatTaskIntentResolver(private val project: Project) {
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

    fun resolve(
        workflowId: String?,
        binding: WorkflowChatBinding?,
        userInput: String,
    ): WorkflowChatTaskIntentResolution {
        val rawInput = userInput.trim()
        if (rawInput.isBlank() || looksLikeQuestion(rawInput)) {
            return WorkflowChatTaskIntentResolution.NoMatch
        }

        val actionIntent = detectActionIntent(rawInput) ?: return WorkflowChatTaskIntentResolution.NoMatch
        val normalizedWorkflowId = workflowId?.trim()?.ifBlank { null }
        val explicitTaskId = extractExplicitTaskId(rawInput)
        val currentTaskCue = refersToCurrentTask(rawInput)
        val nextTaskCue = refersToNextTask(rawInput)
        val titleQuery = extractTaskTitleQuery(rawInput)
        val taskCuePresent = explicitTaskId != null ||
            currentTaskCue ||
            nextTaskCue ||
            TASK_WORD_REGEX.containsMatchIn(rawInput)

        if (normalizedWorkflowId == null) {
            return if (taskCuePresent) {
                WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.NO_ACTIVE_WORKFLOW,
                )
            } else {
                WorkflowChatTaskIntentResolution.NoMatch
            }
        }

        val workflow = storage.loadWorkflow(normalizedWorkflowId).getOrNull()
            ?: return WorkflowChatTaskIntentResolution.Unresolved(
                actionIntent = actionIntent,
                reason = WorkflowChatTaskIntentFailureReason.NO_ACTIVE_WORKFLOW,
            )
        val tasks = tasksService.parse(normalizedWorkflowId)
            .attachActiveExecutionRuns(workflow.taskExecutionRuns)
        if (tasks.isEmpty()) {
            return if (taskCuePresent) {
                WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.NO_TASKS,
                )
            } else {
                WorkflowChatTaskIntentResolution.NoMatch
            }
        }

        val tasksById = tasks.associateBy(StructuredTask::id)

        explicitTaskId?.let { taskId ->
            val task = tasksById[taskId]
            return if (task != null) {
                WorkflowChatTaskIntentResolution.Resolved(
                    workflowId = normalizedWorkflowId,
                    actionIntent = actionIntent,
                    task = task,
                    matchSource = WorkflowChatTaskMatchSource.EXPLICIT_TASK_ID,
                )
            } else {
                WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.TASK_NOT_FOUND,
                    referenceText = taskId,
                )
            }
        }

        if (currentTaskCue) {
            val boundTaskId = binding?.taskId?.trim()?.ifBlank { null }
                ?: return WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.NO_CURRENT_TASK,
                )
            val task = tasksById[boundTaskId]
                ?: return WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.TASK_NOT_FOUND,
                    referenceText = boundTaskId,
                )
            return WorkflowChatTaskIntentResolution.Resolved(
                workflowId = normalizedWorkflowId,
                actionIntent = actionIntent,
                task = task,
                matchSource = WorkflowChatTaskMatchSource.CURRENT_BINDING,
            )
        }

        if (nextTaskCue) {
            val nextTask = resolveNextActionableTask(tasks)
                ?: return WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.NO_NEXT_TASK,
                )
            return WorkflowChatTaskIntentResolution.Resolved(
                workflowId = normalizedWorkflowId,
                actionIntent = actionIntent,
                task = nextTask,
                matchSource = WorkflowChatTaskMatchSource.NEXT_ACTIONABLE,
            )
        }

        if (titleQuery.isBlank()) {
            return if (taskCuePresent) {
                WorkflowChatTaskIntentResolution.Unresolved(
                    actionIntent = actionIntent,
                    reason = WorkflowChatTaskIntentFailureReason.TASK_NOT_FOUND,
                    referenceText = rawInput,
                )
            } else {
                WorkflowChatTaskIntentResolution.NoMatch
            }
        }

        val titleMatches = matchTasksByTitle(titleQuery, tasks)
        return when (titleMatches.size) {
            0 -> {
                if (taskCuePresent) {
                    WorkflowChatTaskIntentResolution.Unresolved(
                        actionIntent = actionIntent,
                        reason = WorkflowChatTaskIntentFailureReason.TASK_NOT_FOUND,
                        referenceText = titleQuery,
                    )
                } else {
                    WorkflowChatTaskIntentResolution.NoMatch
                }
            }

            1 -> WorkflowChatTaskIntentResolution.Resolved(
                workflowId = normalizedWorkflowId,
                actionIntent = actionIntent,
                task = titleMatches.single(),
                matchSource = WorkflowChatTaskMatchSource.TITLE_MATCH,
            )

            else -> WorkflowChatTaskIntentResolution.Unresolved(
                actionIntent = actionIntent,
                reason = WorkflowChatTaskIntentFailureReason.AMBIGUOUS_TASK,
                referenceText = titleQuery,
                candidateTaskIds = titleMatches.map(StructuredTask::id),
            )
        }
    }

    private fun detectActionIntent(rawInput: String): WorkflowChatActionIntent? {
        val normalized = rawInput.lowercase(Locale.ROOT)
        return when {
            RETRY_KEYWORDS.any(normalized::contains) -> WorkflowChatActionIntent.RETRY_TASK
            COMPLETE_KEYWORDS.any(normalized::contains) -> WorkflowChatActionIntent.COMPLETE_TASK
            EXECUTE_KEYWORDS.any(normalized::contains) -> WorkflowChatActionIntent.EXECUTE_TASK
            else -> null
        }
    }

    private fun extractExplicitTaskId(rawInput: String): String? {
        EXPLICIT_TASK_ID_PATTERNS.forEach { pattern ->
            val match = pattern.find(rawInput) ?: return@forEach
            val digits = match.groupValues.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
            if (digits in 1..MAX_TASK_SEQUENCE) {
                return "T-${digits.toString().padStart(3, '0')}"
            }
        }
        return null
    }

    private fun refersToCurrentTask(rawInput: String): Boolean {
        val normalized = rawInput.lowercase(Locale.ROOT)
        return CURRENT_TASK_CUES.any(normalized::contains)
    }

    private fun refersToNextTask(rawInput: String): Boolean {
        val normalized = rawInput.lowercase(Locale.ROOT)
        return NEXT_TASK_CUES.any(normalized::contains)
    }

    private fun extractTaskTitleQuery(rawInput: String): String {
        var normalized = normalizeSearchToken(rawInput)
        NORMALIZED_TITLE_STOPWORDS.forEach { stopword ->
            if (stopword.isNotEmpty()) {
                normalized = normalized.replace(stopword, "")
            }
        }
        return normalized.trim()
    }

    private fun matchTasksByTitle(query: String, tasks: List<StructuredTask>): List<StructuredTask> {
        if (query.length < MIN_TITLE_QUERY_LENGTH) {
            return emptyList()
        }
        val matches = tasks.filter { task ->
            val normalizedTitle = normalizeSearchToken(task.title)
            normalizedTitle.contains(query) || query.contains(normalizedTitle)
        }
        if (matches.size <= 1) {
            return matches
        }
        val exactMatches = matches.filter { task -> normalizeSearchToken(task.title) == query }
        if (exactMatches.isNotEmpty()) {
            return exactMatches
        }
        val prefixMatches = matches.filter { task -> normalizeSearchToken(task.title).startsWith(query) }
        if (prefixMatches.isNotEmpty()) {
            return prefixMatches
        }
        return matches.sortedBy(StructuredTask::id)
    }

    private fun resolveNextActionableTask(tasks: List<StructuredTask>): StructuredTask? {
        val tasksById = tasks.associateBy(StructuredTask::id)
        return tasks.firstOrNull { task ->
            task.status == TaskStatus.PENDING && dependenciesCompleted(task, tasksById)
        } ?: tasks.firstOrNull { task ->
            task.status == TaskStatus.BLOCKED && dependenciesCompleted(task, tasksById)
        }
    }

    private fun dependenciesCompleted(
        task: StructuredTask,
        tasksById: Map<String, StructuredTask>,
    ): Boolean {
        return task.dependsOn.all { dependencyId ->
            tasksById[dependencyId]?.status == TaskStatus.COMPLETED
        }
    }

    private fun looksLikeQuestion(rawInput: String): Boolean {
        val normalized = rawInput.lowercase(Locale.ROOT)
        return QUESTION_CUES.any(normalized::contains)
    }

    private fun normalizeSearchToken(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(EXPLICIT_TASK_ID_REPLACEMENT_REGEX, "")
            .replace(NON_SEARCH_TOKEN_REGEX, "")
    }

    companion object {
        private const val MAX_TASK_SEQUENCE = 999
        private const val MIN_TITLE_QUERY_LENGTH = 2

        private val EXPLICIT_TASK_ID_PATTERNS = listOf(
            Regex("""(?i)\bT[-_\s]*0*(\d{1,3})\b"""),
            Regex("""(?i)\bTask[-_\s]*0*(\d{1,3})\b"""),
            Regex("""任务\s*0*(\d{1,3})"""),
        )
        private val EXPLICIT_TASK_ID_REPLACEMENT_REGEX = Regex("""(?i)(?:\bT[-_\s]*0*\d{1,3}\b|\bTask[-_\s]*0*\d{1,3}\b|任务\s*0*\d{1,3})""")
        private val TASK_WORD_REGEX = Regex("""(?i)\btask\b|任务""")
        private val NON_SEARCH_TOKEN_REGEX = Regex("""[^0-9a-z\u4e00-\u9fff]+""")
        private val EXECUTE_KEYWORDS = listOf(
            "开发",
            "实现",
            "执行",
            "开始",
            "继续",
            "处理",
            "进行",
            "推进",
            "implement",
            "execute",
            "start",
            "continue",
            "work on",
            "pick up",
        )
        private val RETRY_KEYWORDS = listOf(
            "重试",
            "重新执行",
            "再试",
            "retry",
            "rerun",
            "re-run",
            "try again",
        )
        private val COMPLETE_KEYWORDS = listOf(
            "标记完成",
            "完成",
            "收口",
            "收尾",
            "complete",
            "finish",
            "mark complete",
            "mark done",
            "done with",
        )
        private val CURRENT_TASK_CUES = listOf(
            "当前任务",
            "这个任务",
            "该任务",
            "this task",
            "current task",
            "bound task",
        )
        private val NEXT_TASK_CUES = listOf(
            "下一个任务",
            "下个任务",
            "下一项任务",
            "next task",
            "next one",
            "next work item",
        )
        private val QUESTION_CUES = listOf(
            "?",
            "？",
            "吗",
            "么",
            "是否",
            "如何",
            "怎么",
            "为什么",
            "what ",
            "how ",
            "why ",
            "can ",
            "should ",
            "could ",
        )
        private val NORMALIZED_TITLE_STOPWORDS = listOf(
            "markcomplete",
            "markdone",
            "donewith",
            "tryagain",
            "workon",
            "pickup",
            "rerun",
            "retry",
            "re-run",
            "execute",
            "continue",
            "implement",
            "complete",
            "finish",
            "start",
            "nextone",
            "nexttask",
            "nextworkitem",
            "currenttask",
            "boundtask",
            "thistask",
            "please",
            "当前任务",
            "这个任务",
            "该任务",
            "下一个任务",
            "下个任务",
            "下一项任务",
            "重新执行",
            "标记完成",
            "帮我",
            "麻烦",
            "请帮我",
            "请",
            "完成",
            "重试",
            "开发",
            "实现",
            "执行",
            "开始",
            "继续",
            "处理",
            "进行",
            "推进",
            "任务",
            "task",
            "当前",
            "这个",
            "下一个",
            "下个",
            "一下",
            "一下子",
            "的",
            "把",
            "将",
        ).map { stopword ->
            stopword
                .lowercase(Locale.ROOT)
                .replace(NON_SEARCH_TOKEN_REGEX, "")
        }.sortedByDescending(String::length)

        fun getInstance(project: Project): WorkflowChatTaskIntentResolver = project.service()
    }
}
