package com.eacape.speccodingplugin.ui.chat

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
    }

    enum class Status {
        RUNNING,
        DONE,
        INFO,
    }

    data class TimelineItem(
        val kind: Kind,
        val status: Status,
        val detail: String,
    )

    fun parse(content: String): List<TimelineItem> {
        if (content.isBlank()) return emptyList()

        val items = mutableListOf<TimelineItem>()
        val dedupeKeys = linkedSetOf<String>()

        content.lines().forEach { rawLine ->
            val line = normalizeLine(rawLine)
            if (line.isBlank()) return@forEach

            val kind = detectKind(line) ?: return@forEach
            val status = detectStatus(line)
            val detail = stripPrefix(line, kind).ifBlank { line }
            val key = "${kind.name}:${status.name}:$detail"
            if (dedupeKeys.add(key)) {
                items += TimelineItem(
                    kind = kind,
                    status = status,
                    detail = detail,
                )
            }
        }

        return items
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
        }
        return line.replaceFirst(regex, "").trim().ifBlank { line }
    }

    private val TASK_PROGRESS_REGEX = Regex("""(\d+)\s*/\s*(\d+)""")
    private val THINK_PREFIX_REGEX = Regex("""^(\[thinking\]|thinking|思考)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val READ_PREFIX_REGEX = Regex("""^(\[read\]|read\s+files?|读取文件|批量读取文件)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val EDIT_PREFIX_REGEX = Regex("""^(\[edit\]|edit\s+files?|apply\s+patch|编辑文件|修改文件)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val TASK_PREFIX_REGEX = Regex("""^(\[task\]|task|subtask|任务|子任务)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val VERIFY_PREFIX_REGEX = Regex("""^(\[verify\]|verify|test|验证|测试)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val DONE_KEYWORDS = listOf("done", "completed", "finished", "success")
    private val RUNNING_KEYWORDS = listOf("running", "progress", "processing")
}
