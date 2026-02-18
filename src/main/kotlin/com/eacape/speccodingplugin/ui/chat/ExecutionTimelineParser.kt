package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus

/**
 * 从助手输出中提取可视化执行信号（思考、读取、编辑、任务、验证）。
 * 当前版本基于文本约定解析，后续可平滑替换为结构化事件流。
 */
internal object ExecutionTimelineParser {

    enum class Kind {
        THINK,
        READ,
        EDIT,
        TASK,
        VERIFY,
        TOOL,
        OUTPUT,
    }

    enum class Status {
        RUNNING,
        DONE,
        ERROR,
        INFO,
    }

    data class TimelineItem(
        val kind: Kind,
        val status: Status,
        val detail: String,
    )

    fun parse(content: String): List<TimelineItem> {
        if (content.isBlank()) return emptyList()

        val itemsByKey = linkedMapOf<String, TimelineItem>()

        content.lines().forEach { rawLine ->
            val item = parseLine(rawLine) ?: return@forEach
            mergeItem(item, itemsByKey)
        }

        return itemsByKey.values.toList()
    }

    fun parseLine(rawLine: String): TimelineItem? {
        val line = normalizeLine(rawLine)
        if (line.isBlank()) return null

        val kind = detectKind(line) ?: return null
        val status = detectStatus(line)
        val detail = stripPrefix(line, kind).ifBlank { line }
        return TimelineItem(
            kind = kind,
            status = status,
            detail = detail,
        )
    }

    fun fromStructuredEvent(event: ChatStreamEvent): TimelineItem {
        return TimelineItem(
            kind = mapKind(event.kind),
            status = mapStatus(event.status),
            detail = event.detail.trim().ifBlank { event.kind.name.lowercase() },
        )
    }

    private fun normalizeLine(rawLine: String): String {
        return rawLine
            .trim()
            .removePrefix("-")
            .removePrefix("*")
            .trim()
    }

    private fun detectKind(line: String): Kind? {
        val normalized = line.lowercase()

        return when {
            normalized.startsWith("[thinking]") || normalized.startsWith("thinking") || line.startsWith("思考") -> Kind.THINK
            normalized.startsWith("[read]") || normalized.contains("read file") || line.contains("读取文件") || line.contains("批量读取文件") -> Kind.READ
            normalized.startsWith("[edit]") || normalized.contains("edit file") || normalized.contains("apply patch") || line.contains("编辑文件") || line.contains("修改文件") -> Kind.EDIT
            normalized.startsWith("[task]") || normalized.startsWith("task") || line.startsWith("任务") || line.startsWith("子任务") || TASK_PROGRESS_REGEX.containsMatchIn(line) -> Kind.TASK
            normalized.startsWith("[verify]") ||
                normalized.startsWith("verify") ||
                normalized.startsWith("test") ||
                line.startsWith("验证") ||
                line.startsWith("测试") ||
                line.contains("运行测试") ||
                line.contains("执行测试") ||
                normalized.contains("ran test") ||
                normalized.contains("tests passed") -> Kind.VERIFY
            normalized.startsWith("[tool]") ||
                normalized.startsWith("tool") ||
                line.startsWith("工具调用") ||
                line.startsWith("调用工具") -> Kind.TOOL
            normalized.startsWith("[output]") ||
                normalized.startsWith("output") ||
                normalized.startsWith("[stdout]") ||
                normalized.startsWith("[stderr]") ||
                line.startsWith("输出") ||
                line.startsWith("工具输出") -> Kind.OUTPUT
            else -> null
        }
    }

    private fun detectStatus(line: String): Status {
        val normalized = line.lowercase()

        val progress = TASK_PROGRESS_REGEX.find(line)
        if (progress != null) {
            val current = progress.groupValues[1].toIntOrNull()
            val total = progress.groupValues[2].toIntOrNull()
            if (current != null && total != null && total > 0) {
                return if (current >= total) Status.DONE else Status.RUNNING
            }
        }

        if (ERROR_KEYWORDS.any { keyword -> normalized.contains(keyword) } ||
            line.contains("失败") ||
            line.contains("错误")
        ) {
            return Status.ERROR
        }

        if (DONE_KEYWORDS.any { keyword -> normalized.contains(keyword) } ||
            line.contains("完成") ||
            line.contains("已完成")
        ) {
            return Status.DONE
        }

        if (RUNNING_KEYWORDS.any { keyword -> normalized.contains(keyword) } ||
            line.endsWith(">") ||
            line.contains("进行中") ||
            line.contains("处理中")
        ) {
            return Status.RUNNING
        }

        return Status.INFO
    }

    private fun stripPrefix(line: String, kind: Kind): String {
        val regex = when (kind) {
            Kind.THINK -> THINK_PREFIX_REGEX
            Kind.READ -> READ_PREFIX_REGEX
            Kind.EDIT -> EDIT_PREFIX_REGEX
            Kind.TASK -> TASK_PREFIX_REGEX
            Kind.VERIFY -> VERIFY_PREFIX_REGEX
            Kind.TOOL -> TOOL_PREFIX_REGEX
            Kind.OUTPUT -> OUTPUT_PREFIX_REGEX
        }
        return line.replaceFirst(regex, "").trim().ifBlank { line }
    }

    private fun mergeItem(item: TimelineItem, sink: MutableMap<String, TimelineItem>) {
        val key = logicalKey(item)
        val existing = sink[key]
        if (existing == null) {
            sink[key] = item
            return
        }

        sink[key] = when {
            statusPriority(item.status) > statusPriority(existing.status) -> item
            statusPriority(item.status) < statusPriority(existing.status) -> existing
            item.detail.length >= existing.detail.length -> item
            else -> existing
        }
    }

    private fun logicalKey(item: TimelineItem): String {
        val normalizedDetail = normalizeDetailForKey(item.kind, item.detail)
        return "${item.kind.name}:${normalizedDetail.ifBlank { "_" }}"
    }

    private fun normalizeDetailForKey(kind: Kind, detail: String): String {
        var normalized = detail.lowercase().trim()
        if (kind == Kind.TASK) {
            normalized = normalized.replace(TASK_PROGRESS_PREFIX_REGEX, "").trim()
        }
        normalized = normalized.replace(MERGE_NOISE_REGEX, "").trim()
        return normalized
    }

    private fun statusPriority(status: Status): Int {
        return when (status) {
            Status.ERROR -> 3
            Status.DONE -> 2
            Status.RUNNING -> 1
            Status.INFO -> 0
        }
    }

    private fun mapKind(kind: ChatTraceKind): Kind {
        return when (kind) {
            ChatTraceKind.THINK -> Kind.THINK
            ChatTraceKind.READ -> Kind.READ
            ChatTraceKind.EDIT -> Kind.EDIT
            ChatTraceKind.TASK -> Kind.TASK
            ChatTraceKind.VERIFY -> Kind.VERIFY
            ChatTraceKind.TOOL -> Kind.TOOL
            ChatTraceKind.OUTPUT -> Kind.OUTPUT
        }
    }

    private fun mapStatus(status: ChatTraceStatus): Status {
        return when (status) {
            ChatTraceStatus.RUNNING -> Status.RUNNING
            ChatTraceStatus.DONE -> Status.DONE
            ChatTraceStatus.ERROR -> Status.ERROR
            ChatTraceStatus.INFO -> Status.INFO
        }
    }

    private val TASK_PROGRESS_REGEX = Regex("""(\d+)\s*/\s*(\d+)""")
    private val TASK_PROGRESS_PREFIX_REGEX = Regex("""^\d+\s*/\s*\d+\s*""")
    private val MERGE_NOISE_REGEX = Regex("""\b(running|done|completed|finished|success|failed|error|进行中|已完成|完成|失败|错误)\b""")
    private val THINK_PREFIX_REGEX = Regex("""^(\[thinking\]|thinking|思考)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val READ_PREFIX_REGEX = Regex("""^(\[read\]|read\s+files?|读取文件|批量读取文件)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val EDIT_PREFIX_REGEX = Regex("""^(\[edit\]|edit\s+files?|apply\s+patch|编辑文件|修改文件)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val TASK_PREFIX_REGEX = Regex("""^(\[task\]|task|subtask|任务|子任务)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val VERIFY_PREFIX_REGEX = Regex("""^(\[verify\]|verify|test|验证|测试)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val TOOL_PREFIX_REGEX = Regex("""^(\[tool\]|tool|工具调用|调用工具)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val OUTPUT_PREFIX_REGEX = Regex("""^(\[output\]|output|\[stdout\]|\[stderr\]|输出|工具输出)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val DONE_KEYWORDS = listOf("done", "completed", "finished", "success")
    private val RUNNING_KEYWORDS = listOf("running", "progress", "processing")
    private val ERROR_KEYWORDS = listOf("error", "failed", "failure", "exception")
}
