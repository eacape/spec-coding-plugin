package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecTasksService(private val project: Project) {
    private val artifactService: SpecArtifactService by lazy { SpecArtifactService(project) }

    data class TaskSection(
        val entry: SpecTaskMarkdownParser.ParsedTaskEntry,
        val sourceOrder: Int,
        val startOffset: Int,
        val endOffsetExclusive: Int,
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
        private val HEADING_REGEX = Regex("""^\s{0,3}(#{1,6})\s+.*$""")
        private val CANONICAL_TASK_HEADING_REGEX = Regex("""^\s{0,3}###\s+T-\d{3}:\s+.+$""")

        fun getInstance(project: Project): SpecTasksService = project.service()
    }
}
