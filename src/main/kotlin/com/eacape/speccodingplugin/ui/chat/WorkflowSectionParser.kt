package com.eacape.speccodingplugin.ui.chat

/**
 * 解析助手输出中的 Plan/Execute/Verify 结构化区块。
 */
internal object WorkflowSectionParser {

    enum class SectionKind {
        PLAN,
        EXECUTE,
        VERIFY,
    }

    data class Section(
        val kind: SectionKind,
        val content: String,
    )

    data class ParseResult(
        val sections: List<Section>,
        val remainingText: String,
    )

    fun parse(content: String): ParseResult {
        if (content.isBlank()) {
            return ParseResult(
                sections = emptyList(),
                remainingText = "",
            )
        }

        val sections = mutableListOf<Section>()
        val remaining = StringBuilder()
        val lines = content.lines()
        var currentKind: SectionKind? = null
        val currentBuffer = StringBuilder()

        fun flushCurrent() {
            val kind = currentKind ?: return
            val body = currentBuffer.toString().trim()
            if (body.isNotBlank()) {
                sections += Section(kind = kind, content = body)
            }
            currentKind = null
            currentBuffer.clear()
        }

        lines.forEach { line ->
            val title = parseHeadingTitle(line)
            if (title != null) {
                val headingKind = mapHeadingToKind(title)
                if (headingKind != null) {
                    flushCurrent()
                    currentKind = headingKind
                    return@forEach
                }
                flushCurrent()
                remaining.appendLine(line)
                return@forEach
            }

            if (currentKind != null) {
                currentBuffer.appendLine(line)
            } else {
                remaining.appendLine(line)
            }
        }

        flushCurrent()

        return ParseResult(
            sections = sections,
            remainingText = remaining.toString().trimEnd(),
        )
    }

    private fun parseHeadingTitle(line: String): String? {
        val match = HEADING_REGEX.find(line.trim()) ?: return null
        return match.groupValues[1].trim()
    }

    private fun mapHeadingToKind(rawTitle: String): SectionKind? {
        val normalized = rawTitle
            .trim()
            .trimEnd(':', '：')
            .lowercase()

        return when {
            normalized in PLAN_TITLES -> SectionKind.PLAN
            normalized in EXECUTE_TITLES -> SectionKind.EXECUTE
            normalized in VERIFY_TITLES -> SectionKind.VERIFY
            else -> null
        }
    }

    private val HEADING_REGEX = Regex("""^##+\s+(.+)$""")
    private val PLAN_TITLES = setOf("plan", "planning", "计划", "规划")
    private val EXECUTE_TITLES = setOf("execute", "execution", "implement", "执行", "实施")
    private val VERIFY_TITLES = setOf("verify", "verification", "test", "验证", "测试")
}
