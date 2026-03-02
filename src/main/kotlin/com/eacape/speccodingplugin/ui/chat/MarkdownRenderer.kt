package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * 基础 Markdown 渲染器
 * 将 Markdown 文本渲染到 JTextPane 的 StyledDocument 中
 *
 * 支持的语法：
 * - 代码块 (```language ... ```)
 * - 行内代码 (`code`)
 * - 粗体 (**text**)
 * - 斜体 (*text*)
 * - 标题 (# ~ ######)
 * - 无序列表 (- item)
 * - 有序列表 (1. item)
 * - 分隔线 (---)
 */
object MarkdownRenderer {

    private const val CODE_FONT_FAMILY = "JetBrains Mono"
    private val CODE_BG_LIGHT = Color(238, 242, 248)
    private val CODE_BG_DARK = Color(52, 57, 66)
    private val CODE_FG_LIGHT = Color(86, 97, 118)
    private val CODE_FG_DARK = Color(197, 207, 224)
    private val BLOCK_CODE_BG_LIGHT = Color(241, 245, 251)
    private val BLOCK_CODE_BG_DARK = Color(38, 44, 52)
    private val BLOCK_CODE_FG_LIGHT = Color(44, 52, 63)
    private val BLOCK_CODE_FG_DARK = Color(216, 223, 234)
    private val CODE_LANG_FG_LIGHT = Color(104, 120, 142)
    private val CODE_LANG_FG_DARK = Color(148, 164, 187)

    fun render(textPane: JTextPane, markdown: String) {
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)
        val proseFontFamily = textPane.font?.family ?: Font.SANS_SERIF

        val lines = markdown.lines()
        var i = 0
        var firstBlock = true

        while (i < lines.size) {
            val line = lines[i]
            val trimmedLine = line.trimStart()
            val headingMatch = HEADING_REGEX.matchEntire(line)

            // 检查代码块开始
            if (trimmedLine.startsWith("```")) {
                if (!firstBlock) insertNewline(doc)
                firstBlock = false
                i = renderCodeBlock(doc, lines, i)
                continue
            }

            if (!firstBlock) insertNewline(doc)
            firstBlock = false

            when {
                headingMatch != null -> {
                    val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                    renderHeading(doc, headingMatch.groupValues[2], level, proseFontFamily)
                }
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") ->
                    renderListItem(doc, line, ordered = false, proseFontFamily = proseFontFamily)
                ORDERED_LIST_REGEX.matches(trimmedLine) ->
                    renderListItem(doc, line, ordered = true, proseFontFamily = proseFontFamily)
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" ->
                    renderHorizontalRule(doc)
                line.isBlank() -> { /* 空行，newline 已在上面插入 */ }
                else -> renderInlineMarkdown(doc, line, proseFontFamily)
            }

            i++
        }
    }

    /**
     * 渲染代码块
     * @return 下一行的索引
     */
    private fun renderCodeBlock(doc: StyledDocument, lines: List<String>, startIndex: Int): Int {
        val firstLine = lines[startIndex].trimStart()
        val language = firstLine.removePrefix("```").trim()

        val codeLines = mutableListOf<String>()
        var i = startIndex + 1
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("```")) {
                i++
                break
            }
            codeLines.add(lines[i])
            i++
        }

        val code = normalizeCodeBlockContent(codeLines.joinToString("\n"))

        // 语言标签
        if (language.isNotEmpty()) {
            val langAttrs = SimpleAttributeSet()
            StyleConstants.setFontSize(langAttrs, 10)
            StyleConstants.setForeground(langAttrs, JBColor(CODE_LANG_FG_LIGHT, CODE_LANG_FG_DARK))
            StyleConstants.setSpaceAbove(langAttrs, 2f)
            doc.insertString(
                doc.length,
                SpecCodingBundle.message("chat.markdown.code.languageTag", language) + "\n",
                langAttrs,
            )
        }

        // 代码内容
        val codeAttrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(codeAttrs, CODE_FONT_FAMILY)
        StyleConstants.setFontSize(codeAttrs, 11)
        StyleConstants.setBackground(codeAttrs, JBColor(BLOCK_CODE_BG_LIGHT, BLOCK_CODE_BG_DARK))
        StyleConstants.setForeground(codeAttrs, JBColor(BLOCK_CODE_FG_LIGHT, BLOCK_CODE_FG_DARK))
        StyleConstants.setLeftIndent(codeAttrs, 10f)
        StyleConstants.setRightIndent(codeAttrs, 10f)
        StyleConstants.setLineSpacing(codeAttrs, 0.08f)
        StyleConstants.setSpaceAbove(codeAttrs, if (language.isEmpty()) 2f else 1f)
        StyleConstants.setSpaceBelow(codeAttrs, 4f)
        val codeText = if (code.isEmpty()) "\n" else "$code\n"
        doc.insertString(doc.length, codeText, codeAttrs)

        return i
    }

    private fun normalizeCodeBlockContent(raw: String): String {
        return raw
            .replace("\t", "    ")
            .trimEnd('\n', '\r')
    }

    /**
     * 渲染标题
     */
    private fun renderHeading(doc: StyledDocument, text: String, level: Int, proseFontFamily: String) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, true)
        val fontSize = when (level) {
            1 -> 16
            2 -> 14
            3 -> 13
            4 -> 12
            else -> 11
        }
        StyleConstants.setFontSize(attrs, fontSize)
        StyleConstants.setFontFamily(attrs, proseFontFamily)
        doc.insertString(doc.length, text, attrs)
    }

    /**
     * 渲染列表项
     */
    private fun renderListItem(doc: StyledDocument, line: String, ordered: Boolean, proseFontFamily: String) {
        val indent = line.length - line.trimStart().length
        val prefix = "  ".repeat(indent / 2)

        val bulletAttrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(bulletAttrs, proseFontFamily)
        StyleConstants.setFontSize(bulletAttrs, 11)

        val trimmed = line.trimStart()
        val bullet = if (ordered) {
            val num = trimmed.substringBefore(".")
            "$prefix$num. "
        } else {
            "$prefix  \u2022 "
        }

        doc.insertString(doc.length, bullet, bulletAttrs)

        // 列表项内容（支持行内 Markdown）
        val content = if (ordered) {
            trimmed.substringAfter(". ", "")
        } else {
            trimmed.removePrefix("- ").removePrefix("* ")
        }
        renderInlineMarkdown(doc, content, proseFontFamily)
    }

    /**
     * 渲染分隔线
     */
    private fun renderHorizontalRule(doc: StyledDocument) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, JBColor.GRAY)
        StyleConstants.setFontSize(attrs, 10)
        doc.insertString(doc.length, "\u2500".repeat(40), attrs)
    }

    /**
     * 渲染行内 Markdown（粗体、斜体、行内代码）
     */
    private fun renderInlineMarkdown(doc: StyledDocument, text: String, proseFontFamily: String) {
        val tokens = tokenizeInline(text)
        for (token in tokens) {
            when (token) {
                is InlineToken.Bold -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setBold(attrs, true)
                    StyleConstants.setFontFamily(attrs, proseFontFamily)
                    StyleConstants.setFontSize(attrs, 11)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Italic -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setItalic(attrs, true)
                    StyleConstants.setFontFamily(attrs, proseFontFamily)
                    StyleConstants.setFontSize(attrs, 11)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.InlineCode -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setFontFamily(attrs, CODE_FONT_FAMILY)
                    StyleConstants.setFontSize(attrs, 10)
                    StyleConstants.setBackground(attrs, JBColor(CODE_BG_LIGHT, CODE_BG_DARK))
                    StyleConstants.setForeground(attrs, JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Plain -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setFontFamily(attrs, proseFontFamily)
                    StyleConstants.setFontSize(attrs, 11)
                    doc.insertString(doc.length, token.text, attrs)
                }
            }
        }
    }

    private fun insertNewline(doc: StyledDocument) {
        doc.insertString(doc.length, "\n", SimpleAttributeSet())
    }

    /**
     * 将行内文本分词为 Token 列表
     */
    private fun tokenizeInline(text: String): List<InlineToken> {
        val tokens = mutableListOf<InlineToken>()
        var pos = 0

        while (pos < text.length) {
            // 行内代码 `...`
            if (text[pos] == '`') {
                val end = text.indexOf('`', pos + 1)
                if (end > pos) {
                    tokens.add(InlineToken.InlineCode(text.substring(pos + 1, end)))
                    pos = end + 1
                    continue
                }
            }

            // 粗体 **...**
            if (pos + 1 < text.length && text[pos] == '*' && text[pos + 1] == '*') {
                val end = text.indexOf("**", pos + 2)
                if (end > pos) {
                    tokens.add(InlineToken.Bold(text.substring(pos + 2, end)))
                    pos = end + 2
                    continue
                }
            }

            // 斜体 *...*
            if (text[pos] == '*') {
                val end = text.indexOf('*', pos + 1)
                if (end > pos && !(pos + 1 < text.length && text[pos + 1] == '*')) {
                    tokens.add(InlineToken.Italic(text.substring(pos + 1, end)))
                    pos = end + 1
                    continue
                }
            }

            // 普通文本：收集到下一个特殊字符
            val nextSpecial = findNextSpecial(text, pos + 1)
            tokens.add(InlineToken.Plain(text.substring(pos, nextSpecial)))
            pos = nextSpecial
        }

        return tokens
    }

    private fun findNextSpecial(text: String, from: Int): Int {
        for (i in from until text.length) {
            if (text[i] == '`' || text[i] == '*') return i
        }
        return text.length
    }

    private val HEADING_REGEX = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val ORDERED_LIST_REGEX = Regex("""^\d+\.\s.*$""")

    private sealed class InlineToken {
        abstract val text: String
        data class Plain(override val text: String) : InlineToken()
        data class Bold(override val text: String) : InlineToken()
        data class Italic(override val text: String) : InlineToken()
        data class InlineCode(override val text: String) : InlineToken()
    }
}
