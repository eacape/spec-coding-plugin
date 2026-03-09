package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecTasksService(private val project: Project) {
    private val artifactService: SpecArtifactService by lazy { SpecArtifactService(project) }
    private val storage: SpecStorage by lazy { SpecStorage.getInstance(project) }

    data class TaskSection(
        val entry: SpecTaskMarkdownParser.ParsedTaskEntry,
        val sourceOrder: Int,
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val metadataFenceStartOffsetInSection: Int,
        val metadataFenceEndOffsetExclusiveInSection: Int,
        val metadataContentStartOffsetInSection: Int?,
        val metadataContentEndOffsetExclusiveInSection: Int?,
        val sectionMarkdown: String,
        val bodyMarkdown: String,
    ) {
        val task: StructuredTask?
            get() = entry.toStructuredTaskOrNull()
    }

    data class ParsedTasksDocument(
        val normalizedMarkdown: String,
        val preambleMarkdown: String,
        val taskSectionsInSourceOrder: List<TaskSection>,
        val trailingMarkdown: String,
        val issues: List<SpecTaskMarkdownParser.ParseIssue>,
    ) {
        val taskSectionsById: List<TaskSection>
            get() = taskSectionsInSourceOrder.sortedBy { it.entry.id }

        val tasksById: List<StructuredTask>
            get() = taskSectionsById.mapNotNull(TaskSection::task)

        fun renderStable(): String {
            if (taskSectionsInSourceOrder.isEmpty()) {
                return normalizedMarkdown
            }
            return buildString {
                append(preambleMarkdown)
                taskSectionsById.forEach { section ->
                    append(section.sectionMarkdown)
                }
                append(trailingMarkdown)
            }
        }
    }

    fun parseDocument(markdown: String): ParsedTasksDocument {
        val parsedMarkdown = SpecMarkdownAstParser.parse(markdown)
        val parsedTasks = SpecTaskMarkdownParser.parse(parsedMarkdown.normalizedMarkdown)
        if (parsedTasks.tasks.isEmpty()) {
            return ParsedTasksDocument(
                normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
                preambleMarkdown = parsedMarkdown.normalizedMarkdown,
                taskSectionsInSourceOrder = emptyList(),
                trailingMarkdown = "",
                issues = parsedTasks.issues,
            )
        }

        val lineTable = buildLineTable(parsedMarkdown.normalizedMarkdown)
        val codeFenceLineRanges = parsedMarkdown.codeFences.map { fence ->
            fence.location.startLine..fence.location.endLine
        }
        val headings = collectHeadings(
            normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
            codeFenceLineRanges = codeFenceLineRanges,
        )
        val specTaskFencesByStartLine = parsedMarkdown.codeFences
            .filter { fence ->
                fence.language.equals(SPEC_TASK_LANGUAGE, ignoreCase = true)
            }
            .associateBy { fence -> fence.location.startLine }

        val taskSectionsInSourceOrder = parsedTasks.tasks.mapIndexed { sourceOrder, taskEntry ->
            val startOffset = lineTable.startOffsets.getOrElse(taskEntry.headingLine - 1) { 0 }
            val endOffsetExclusive = nextSectionStartOffset(
                taskEntry = taskEntry,
                headings = headings,
                lineTable = lineTable,
                fallbackEndOffset = parsedMarkdown.normalizedMarkdown.length,
            )
            val metadataFence = specTaskFencesByStartLine[taskEntry.metadataLocation.startLine]
            val bodyStartOffset = metadataFence?.location?.endOffsetExclusive ?: startOffset
            TaskSection(
                entry = taskEntry,
                sourceOrder = sourceOrder,
                startOffset = startOffset,
                endOffsetExclusive = endOffsetExclusive,
                metadataFenceStartOffsetInSection = metadataFence
                    ?.location
                    ?.startOffset
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset)
                    ?: 0,
                metadataFenceEndOffsetExclusiveInSection = metadataFence
                    ?.location
                    ?.endOffsetExclusive
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset)
                    ?: 0,
                metadataContentStartOffsetInSection = metadataFence
                    ?.contentLocation
                    ?.startOffset
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset),
                metadataContentEndOffsetExclusiveInSection = metadataFence
                    ?.contentLocation
                    ?.endOffsetExclusive
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset),
                sectionMarkdown = parsedMarkdown.normalizedMarkdown.substring(startOffset, endOffsetExclusive),
                bodyMarkdown = parsedMarkdown.normalizedMarkdown.substring(
                    startIndex = bodyStartOffset.coerceAtMost(endOffsetExclusive),
                    endIndex = endOffsetExclusive,
                ),
            )
        }

        return ParsedTasksDocument(
            normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
            preambleMarkdown = parsedMarkdown.normalizedMarkdown.substring(0, taskSectionsInSourceOrder.first().startOffset),
            taskSectionsInSourceOrder = taskSectionsInSourceOrder,
            trailingMarkdown = parsedMarkdown.normalizedMarkdown.substring(taskSectionsInSourceOrder.last().endOffsetExclusive),
            issues = parsedTasks.issues,
        )
    }

    fun readTasksDocument(workflowId: String): ParsedTasksDocument? {
        return artifactService.readArtifact(workflowId, StageId.TASKS)?.let(::parseDocument)
    }

    fun parse(workflowId: String): List<StructuredTask> {
        return readTasksDocument(workflowId)?.tasksById.orEmpty()
    }

    fun stabilizeOutput(markdown: String): String {
        return parseDocument(markdown).renderStable()
    }

    fun stabilizeTaskArtifact(workflowId: String): ParsedTasksDocument? {
        val parsedDocument = readTasksDocument(workflowId) ?: return null
        val stableMarkdown = parsedDocument.renderStable()
        if (stableMarkdown != parsedDocument.normalizedMarkdown) {
            artifactService.writeArtifact(workflowId, StageId.TASKS, stableMarkdown)
        }
        return if (stableMarkdown == parsedDocument.normalizedMarkdown) {
            parsedDocument
        } else {
            parseDocument(stableMarkdown)
        }
    }

    fun addTask(
        workflowId: String,
        title: String,
        priority: TaskPriority,
        dependsOn: List<String> = emptyList(),
    ): StructuredTask {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "title cannot be blank." }

        val editableDocument = loadEditableDocument(workflowId)
        val nextTaskId = allocateNextTaskId(editableDocument.tasksById)
        val task = StructuredTask(
            id = nextTaskId,
            title = normalizedTitle,
            status = TaskStatus.PENDING,
            priority = priority,
            dependsOn = dependsOn
                .map(String::trim)
                .filter(String::isNotEmpty),
            relatedFiles = emptyList(),
            verificationResult = null,
        )

        val updatedMarkdown = if (editableDocument.taskSectionsInSourceOrder.isEmpty()) {
            insertTaskSection(
                normalizedMarkdown = editableDocument.normalizedMarkdown,
                renderedTaskSection = renderTaskSection(task),
            )
        } else {
            buildString {
                append(editableDocument.preambleMarkdown)
                editableDocument.taskSectionsInSourceOrder.forEach { section ->
                    append(section.sectionMarkdown)
                }
                append(renderTaskSection(task))
                append(editableDocument.trailingMarkdown)
            }
        }

        val stableMarkdown = stabilizeOutput(updatedMarkdown)
        artifactService.writeArtifact(workflowId, StageId.TASKS, stableMarkdown)
        return parseDocument(stableMarkdown)
            .tasksById
            .first { existingTask -> existingTask.id == nextTaskId }
    }

    fun removeTask(workflowId: String, taskId: String) {
        val normalizedTaskId = normalizeTaskId(taskId)

        val editableDocument = loadEditableDocument(workflowId)
        val remainingSections = editableDocument.taskSectionsInSourceOrder
            .filterNot { section -> section.entry.id == normalizedTaskId }
        if (remainingSections.size == editableDocument.taskSectionsInSourceOrder.size) {
            throw MissingStructuredTaskError(normalizedTaskId)
        }

        val updatedMarkdown = buildString {
            append(editableDocument.preambleMarkdown)
            remainingSections.forEach { section ->
                append(section.sectionMarkdown)
            }
            append(editableDocument.trailingMarkdown)
        }
        artifactService.writeArtifact(
            workflowId,
            StageId.TASKS,
            stabilizeOutput(updatedMarkdown),
        )
    }

    fun transitionStatus(
        workflowId: String,
        taskId: String,
        to: TaskStatus,
        reason: String? = null,
    ) {
        val normalizedTaskId = normalizeTaskId(taskId)
        val normalizedReason = reason
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val editableDocument = loadEditableDocument(workflowId)
        val targetSection = editableDocument.taskSectionsInSourceOrder
            .firstOrNull { section -> section.entry.id == normalizedTaskId }
            ?: throw MissingStructuredTaskError(normalizedTaskId)
        val targetTask = targetSection.task
            ?: throw InvalidTasksArtifactEditError("task $normalizedTaskId is missing required spec-task metadata")
        if (!targetTask.status.canTransitionTo(to)) {
            throw InvalidTaskStateTransitionError(normalizedTaskId, targetTask.status, to)
        }

        val updatedTask = targetTask.copy(status = to)
        val updatedMarkdown = buildString {
            append(editableDocument.preambleMarkdown)
            editableDocument.taskSectionsInSourceOrder.forEach { section ->
                append(
                    if (section.entry.id == normalizedTaskId) {
                        renderUpdatedTaskSection(section, updatedTask)
                    } else {
                        section.sectionMarkdown
                    },
                )
            }
            append(editableDocument.trailingMarkdown)
        }
        artifactService.writeArtifact(workflowId, StageId.TASKS, updatedMarkdown)

        val details = linkedMapOf(
            "taskId" to normalizedTaskId,
            "title" to targetTask.title,
            "fromStatus" to targetTask.status.name,
            "toStatus" to to.name,
        )
        normalizedReason?.let { trimmedReason ->
            details["reason"] = trimmedReason
        }
        storage.appendAuditEvent(
            workflowId = workflowId,
            eventType = SpecAuditEventType.TASK_STATUS_CHANGED,
            details = details,
        ).getOrThrow()
    }

    private fun nextSectionStartOffset(
        taskEntry: SpecTaskMarkdownParser.ParsedTaskEntry,
        headings: List<HeadingBoundary>,
        lineTable: LineTable,
        fallbackEndOffset: Int,
    ): Int {
        val nextBoundaryLine = headings.firstOrNull { heading ->
            heading.lineNumber > taskEntry.headingLine &&
                (heading.level < TASK_HEADING_LEVEL || heading.isCanonicalTaskHeading)
        }?.lineNumber
        return if (nextBoundaryLine == null) {
            fallbackEndOffset
        } else {
            lineTable.startOffsets[nextBoundaryLine - 1]
        }
    }

    private fun collectHeadings(
        normalizedMarkdown: String,
        codeFenceLineRanges: List<IntRange>,
    ): List<HeadingBoundary> {
        return buildLineTable(normalizedMarkdown).lines.mapIndexedNotNull { index, line ->
            val lineNumber = index + 1
            if (codeFenceLineRanges.any { range -> lineNumber in range }) {
                return@mapIndexedNotNull null
            }
            val match = HEADING_REGEX.matchEntire(line) ?: return@mapIndexedNotNull null
            HeadingBoundary(
                lineNumber = lineNumber,
                level = match.groupValues[1].length,
                isCanonicalTaskHeading = CANONICAL_TASK_HEADING_REGEX.matches(line),
            )
        }
    }

    private fun buildLineTable(markdown: String): LineTable {
        if (markdown.isEmpty()) {
            return LineTable(lines = emptyList(), startOffsets = intArrayOf(0))
        }

        val lines = mutableListOf<String>()
        val startOffsets = mutableListOf(0)
        var lineStart = 0
        markdown.forEachIndexed { index, char ->
            if (char != '\n') {
                return@forEachIndexed
            }
            lines += markdown.substring(lineStart, index)
            lineStart = index + 1
            startOffsets += lineStart
        }
        lines += markdown.substring(lineStart)

        return LineTable(
            lines = lines,
            startOffsets = startOffsets.toIntArray(),
        )
    }

    private fun loadEditableDocument(workflowId: String): ParsedTasksDocument {
        val markdown = artifactService.readArtifact(workflowId, StageId.TASKS)
            ?.takeIf { content -> content.isNotBlank() }
            ?: EMPTY_TASKS_DOCUMENT
        val parsedDocument = parseDocument(markdown)
        if (parsedDocument.issues.isNotEmpty()) {
            val details = parsedDocument.issues
                .take(MAX_EDIT_ISSUES_TO_REPORT)
                .joinToString(separator = "; ") { issue ->
                    "line ${issue.line}: ${issue.message}"
                }
            throw InvalidTasksArtifactEditError(details)
        }

        parsedDocument.tasksById
            .groupingBy(StructuredTask::id)
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }
            ?.let { (duplicateTaskId, _) ->
                throw DuplicateTaskIdError(duplicateTaskId)
            }

        return parsedDocument
    }

    private fun allocateNextTaskId(tasks: List<StructuredTask>): String {
        val currentMaxSequence = tasks
            .maxOfOrNull { task -> task.id.removePrefix(TASK_ID_PREFIX).toInt() }
            ?: 0
        val nextSequence = currentMaxSequence + 1
        require(nextSequence <= MAX_TASK_SEQUENCE) {
            "Task id limit exceeded: ${TASK_ID_PREFIX}${nextSequence.toString().padStart(3, '0')}"
        }
        return TASK_ID_PREFIX + nextSequence.toString().padStart(3, '0')
    }

    private fun normalizeTaskId(taskId: String): String {
        val normalizedTaskId = taskId.trim().uppercase()
        require(TASK_ID_REGEX.matches(normalizedTaskId)) {
            "taskId must use the format T-001."
        }
        return normalizedTaskId
    }

    private fun renderUpdatedTaskSection(section: TaskSection, task: StructuredTask): String {
        val updatedMetadata = renderTaskMetadata(task).removeSuffix("\n")
        val metadataContentStart = section.metadataContentStartOffsetInSection
        val metadataContentEndExclusive = section.metadataContentEndOffsetExclusiveInSection
        if (metadataContentStart != null && metadataContentEndExclusive != null) {
            return buildString {
                append(section.sectionMarkdown.substring(0, metadataContentStart))
                append(updatedMetadata)
                append(section.sectionMarkdown.substring(metadataContentEndExclusive))
            }
        }

        return buildString {
            append(section.sectionMarkdown.substring(0, section.metadataFenceStartOffsetInSection))
            append(renderTaskMetadataFence(task))
            append(section.sectionMarkdown.substring(section.metadataFenceEndOffsetExclusiveInSection))
        }
    }

    private fun insertTaskSection(
        normalizedMarkdown: String,
        renderedTaskSection: String,
    ): String {
        val insertionBase = normalizedMarkdown.takeIf { markdown -> markdown.isNotBlank() } ?: EMPTY_TASKS_DOCUMENT
        val lineTable = buildLineTable(insertionBase)
        val insertionLine = findEmptyTaskInsertionLine(lineTable.lines)
        val insertionOffset = insertionLine
            ?.let { line -> lineTable.startOffsets[line - 1] }
            ?: insertionBase.length
        val prefix = insertionBase.substring(0, insertionOffset)
        val suffix = insertionBase.substring(insertionOffset)

        return buildString {
            append(prefix)
            appendTaskSectionSeparator(prefix)
            append(renderedTaskSection)
            append(suffix)
        }
    }

    private fun StringBuilder.appendTaskSectionSeparator(prefix: String) {
        if (prefix.isEmpty()) {
            return
        }
        if (!prefix.endsWith("\n")) {
            append('\n')
        }
        if (!prefix.endsWith("\n\n")) {
            append('\n')
        }
    }

    private fun findEmptyTaskInsertionLine(lines: List<String>): Int? {
        val taskListHeadingIndex = lines.indexOfFirst { line ->
            TASK_LIST_HEADING_REGEX.matches(line)
        }
        if (taskListHeadingIndex == -1) {
            return null
        }
        for (index in taskListHeadingIndex + 1 until lines.size) {
            val match = HEADING_REGEX.matchEntire(lines[index]) ?: continue
            if (match.groupValues[1].length <= TASK_LIST_HEADING_LEVEL) {
                return index + 1
            }
        }
        return null
    }

    private fun renderTaskSection(task: StructuredTask): String {
        return buildString {
            append("### ${task.id}: ${task.title}\n")
            append(renderTaskMetadataFence(task))
            append('\n')
        }
    }

    private fun renderTaskMetadataFence(task: StructuredTask): String {
        return buildString {
            append("```")
            append(SPEC_TASK_LANGUAGE)
            append('\n')
            append(renderTaskMetadata(task))
            append("```\n")
        }
    }

    private fun renderTaskMetadata(task: StructuredTask): String {
        return buildString {
            append("status: ${task.status}\n")
            append("priority: ${task.priority}\n")
            append(renderStringListField("dependsOn", task.dependsOn))
            append(renderStringListField("relatedFiles", task.relatedFiles))
            append("verificationResult: ")
            if (task.verificationResult == null) {
                append("null\n")
            } else {
                append('\n')
                append("  conclusion: ${task.verificationResult.conclusion}\n")
                append("  runId: ${task.verificationResult.runId}\n")
                append("  summary: ${task.verificationResult.summary}\n")
                append("  at: ${task.verificationResult.at}\n")
            }
        }
    }

    private fun renderStringListField(fieldName: String, values: List<String>): String {
        if (values.isEmpty()) {
            return "$fieldName: []\n"
        }
        return buildString {
            append("$fieldName:\n")
            values.forEach { value ->
                append("  - ")
                append(value)
                append('\n')
            }
        }
    }

    private data class LineTable(
        val lines: List<String>,
        val startOffsets: IntArray,
    )

    private data class HeadingBoundary(
        val lineNumber: Int,
        val level: Int,
        val isCanonicalTaskHeading: Boolean,
    )

    companion object {
        private const val SPEC_TASK_LANGUAGE = "spec-task"
        private const val TASK_HEADING_LEVEL = 3
        private const val TASK_LIST_HEADING_LEVEL = 2
        private const val TASK_ID_PREFIX = "T-"
        private const val MAX_TASK_SEQUENCE = 999
        private const val MAX_EDIT_ISSUES_TO_REPORT = 3
        private const val EMPTY_TASKS_DOCUMENT = "# Implement Document\n\n## Task List\n"
        private val HEADING_REGEX = Regex("""^\s{0,3}(#{1,6})\s+.*$""")
        private val CANONICAL_TASK_HEADING_REGEX = Regex("""^\s{0,3}###\s+T-\d{3}:\s+.+$""")
        private val TASK_ID_REGEX = Regex("""^T-\d{3}$""")
        private val TASK_LIST_HEADING_REGEX = Regex("""^\s{0,3}##\s+(Task\s+List|任务列表)\s*$""")

        fun getInstance(project: Project): SpecTasksService = project.service()
    }
}
