package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
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
 * - 表格 (| col | col |)
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
        val proseFontFamily = textPane.font?.family ?: Font.SANS_SERIF
        val baseFontSize = resolveBaseFontSize(textPane)
        if (containsMarkdownTable(markdown) && renderWithMarkdownEngine(textPane, markdown, proseFontFamily, baseFontSize)) {
            return
        }

        ensurePlainTextMode(textPane)
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)

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
                i = renderCodeBlock(doc, lines, i, baseFontSize)
                continue
            }

            val tableBlock = parseTableBlock(lines, i)
            if (tableBlock != null) {
                if (!firstBlock) insertNewline(doc)
                firstBlock = false
                renderTable(doc, tableBlock, proseFontFamily, baseFontSize)
                i = tableBlock.nextIndex
                continue
            }

            if (!firstBlock) insertNewline(doc)
            firstBlock = false

            when {
                headingMatch != null -> {
                    val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                    renderHeading(doc, headingMatch.groupValues[2], level, proseFontFamily, baseFontSize)
                }
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") ->
                    renderListItem(doc, line, ordered = false, proseFontFamily = proseFontFamily, baseFontSize = baseFontSize)
                ORDERED_LIST_REGEX.matches(trimmedLine) ->
                    renderListItem(doc, line, ordered = true, proseFontFamily = proseFontFamily, baseFontSize = baseFontSize)
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" ->
                    renderHorizontalRule(doc, baseFontSize)
                line.isBlank() -> { /* 空行，newline 已在上面插入 */ }
                else -> renderInlineMarkdown(doc, line, proseFontFamily, baseFontSize)
            }

            i++
        }
    }

    /**
     * 渲染代码块
     * @return 下一行的索引
     */
    private fun renderCodeBlock(doc: StyledDocument, lines: List<String>, startIndex: Int, baseFontSize: Int): Int {
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
            StyleConstants.setFontSize(langAttrs, (baseFontSize - 1).coerceAtLeast(MIN_INLINE_FONT_SIZE))
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
        StyleConstants.setFontSize(codeAttrs, baseFontSize)
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

    private fun parseTableBlock(lines: List<String>, startIndex: Int): TableBlock? {
        if (startIndex + 1 >= lines.size) return null
        val headerCells = splitTableCells(lines[startIndex])
        if (headerCells.size < MIN_TABLE_COLUMNS) return null

        val separatorCells = splitTableCells(lines[startIndex + 1])
        if (!isTableSeparatorRow(separatorCells)) return null

        val rows = mutableListOf(headerCells)
        var index = startIndex + 2
        while (index < lines.size) {
            val rowCells = splitTableCells(lines[index])
            if (rowCells.size < MIN_TABLE_COLUMNS) break
            rows.add(rowCells)
            index += 1
        }

        return TableBlock(
            rows = normalizeTableRows(rows),
            nextIndex = index,
        )
    }

    private fun splitTableCells(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.contains('|')) return emptyList()

        var content = trimmed
        if (content.startsWith('|')) {
            content = content.substring(1)
        }
        if (content.endsWith('|')) {
            content = content.dropLast(1)
        }
        if (!content.contains('|')) return emptyList()

        val cells = mutableListOf<String>()
        val cellBuilder = StringBuilder()
        var escaped = false
        for (ch in content) {
            if (escaped) {
                cellBuilder.append(ch)
                escaped = false
                continue
            }

            when (ch) {
                '\\' -> escaped = true
                '|' -> {
                    cells.add(cellBuilder.toString().trim())
                    cellBuilder.setLength(0)
                }
                else -> cellBuilder.append(ch)
            }
        }
        if (escaped) {
            cellBuilder.append('\\')
        }
        cells.add(cellBuilder.toString().trim())
        return cells
    }

    private fun isTableSeparatorRow(cells: List<String>): Boolean {
        if (cells.size < MIN_TABLE_COLUMNS) return false
        return cells.all { cell ->
            TABLE_SEPARATOR_CELL_REGEX.matches(cell.trim())
        }
    }

    private fun normalizeTableRows(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return emptyList()
        val columnCount = rows.maxOf { it.size }.coerceAtLeast(MIN_TABLE_COLUMNS)
        return rows.map { row ->
            List(columnCount) { index ->
                row.getOrNull(index).orEmpty()
            }
        }
    }

    private fun containsMarkdownTable(markdown: String): Boolean {
        val lines = markdown.lines()
        if (lines.size < 2) return false
        for (index in 0 until lines.lastIndex) {
            val headerCells = splitTableCells(lines[index])
            if (headerCells.size < MIN_TABLE_COLUMNS) continue
            val separatorCells = splitTableCells(lines[index + 1])
            if (isTableSeparatorRow(separatorCells)) {
                return true
            }
        }
        return false
    }

    private fun renderWithMarkdownEngine(
        textPane: JTextPane,
        markdown: String,
        proseFontFamily: String,
        baseFontSize: Int,
    ): Boolean {
        return runCatching {
            val flavour = GFMFlavourDescriptor(useSafeLinks = true)
            val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            val bodyHtml = HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
            val html = wrapMarkdownHtml(bodyHtml, proseFontFamily, baseFontSize)
            textPane.contentType = HTML_CONTENT_TYPE
            textPane.text = html
            textPane.caretPosition = 0
        }.isSuccess
    }

    private fun wrapMarkdownHtml(bodyHtml: String, proseFontFamily: String, baseFontSize: Int): String {
        val fontFamily = escapeCssFontFamily(proseFontFamily)
        val codeFontFamily = escapeCssFontFamily(CODE_FONT_FAMILY)
        val bodyFg = toCssColor(JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
        val tableBorder = toCssColor(JBColor(Color(208, 216, 228), Color(91, 101, 112)))
        val tableHeaderBg = toCssColor(JBColor(Color(244, 247, 252), Color(50, 56, 64)))
        val inlineCodeBg = toCssColor(JBColor(CODE_BG_LIGHT, CODE_BG_DARK))
        val inlineCodeFg = toCssColor(JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
        val blockCodeBg = toCssColor(JBColor(BLOCK_CODE_BG_LIGHT, BLOCK_CODE_BG_DARK))
        val blockCodeFg = toCssColor(JBColor(BLOCK_CODE_FG_LIGHT, BLOCK_CODE_FG_DARK))
        val css = """
            html, body { margin: 0; padding: 0; background: transparent; }
            body {
                font-family: '$fontFamily';
                font-size: ${baseFontSize}px;
                line-height: 1.58;
                color: $bodyFg;
            }
            p, ul, ol, table, pre, blockquote { margin: 0 0 8px 0; }
            ul, ol { padding-left: 20px; }
            table { border-collapse: collapse; width: 100%; }
            th, td {
                border: 1px solid $tableBorder;
                padding: 6px 8px;
                text-align: left;
                vertical-align: top;
            }
            th { background: $tableHeaderBg; font-weight: 600; }
            code {
                font-family: '$codeFontFamily';
                background: $inlineCodeBg;
                color: $inlineCodeFg;
                border-radius: 4px;
                padding: 1px 4px;
            }
            pre {
                font-family: '$codeFontFamily';
                background: $blockCodeBg;
                color: $blockCodeFg;
                border-radius: 8px;
                padding: 8px 10px;
                overflow-x: auto;
            }
            pre code {
                background: transparent;
                color: inherit;
                padding: 0;
            }
        """.trimIndent()
        return "<html><head><style>$css</style></head><body>$bodyHtml</body></html>"
    }

    private fun escapeCssFontFamily(fontFamily: String): String {
        return fontFamily.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun toCssColor(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private fun ensurePlainTextMode(textPane: JTextPane) {
        if (!textPane.contentType.equals(PLAIN_TEXT_CONTENT_TYPE, ignoreCase = true)) {
            textPane.contentType = PLAIN_TEXT_CONTENT_TYPE
        }
    }

    private fun renderTable(
        doc: StyledDocument,
        tableBlock: TableBlock,
        proseFontFamily: String,
        baseFontSize: Int,
    ) {
        val rows = tableBlock.rows
        if (rows.isEmpty()) return
        val header = rows.first().map(::normalizeTableCellForPipe)
        val body = rows.drop(1).map { row -> row.map(::normalizeTableCellForPipe) }

        val baseAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, proseFontFamily)
            StyleConstants.setFontSize(this, baseFontSize)
            StyleConstants.setForeground(this, JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
            StyleConstants.setLeftIndent(this, 4f)
            StyleConstants.setSpaceAbove(this, 1f)
            StyleConstants.setSpaceBelow(this, 1f)
        }
        val headerAttrs = SimpleAttributeSet(baseAttrs).apply {
            StyleConstants.setBold(this, true)
        }
        val separatorAttrs = SimpleAttributeSet(baseAttrs).apply {
            StyleConstants.setForeground(this, JBColor(CODE_LANG_FG_LIGHT, CODE_LANG_FG_DARK))
        }

        doc.insertString(doc.length, buildPipeRow(header), headerAttrs)
        insertNewline(doc)
        doc.insertString(
            doc.length,
            buildPipeRow(List(header.size) { "---" }),
            separatorAttrs,
        )
        body.forEach { row ->
            insertNewline(doc)
            doc.insertString(doc.length, buildPipeRow(row), baseAttrs)
        }
    }

    private fun buildPipeRow(cells: List<String>): String {
        return cells.joinToString(
            separator = " | ",
            prefix = "| ",
            postfix = " |",
        )
    }

    private fun normalizeTableCellForPipe(cell: String): String {
        return cell
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("|", "\\|")
            .trim()
    }

    private fun normalizeCodeBlockContent(raw: String): String {
        return raw
            .replace("\t", "    ")
            .trimEnd('\n', '\r')
    }

    /**
     * 渲染标题
     */
    private fun renderHeading(
        doc: StyledDocument,
        text: String,
        level: Int,
        proseFontFamily: String,
        baseFontSize: Int,
    ) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, true)
        val fontSize = when (level) {
            1 -> baseFontSize + 5
            2 -> baseFontSize + 3
            3 -> baseFontSize + 2
            4 -> baseFontSize + 1
            else -> baseFontSize
        }
        StyleConstants.setFontSize(attrs, fontSize)
        StyleConstants.setFontFamily(attrs, proseFontFamily)
        doc.insertString(doc.length, text, attrs)
    }

    /**
     * 渲染列表项
     */
    private fun renderListItem(
        doc: StyledDocument,
        line: String,
        ordered: Boolean,
        proseFontFamily: String,
        baseFontSize: Int,
    ) {
        val indent = line.length - line.trimStart().length
        val prefix = "  ".repeat(indent / 2)

        val bulletAttrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(bulletAttrs, proseFontFamily)
        StyleConstants.setFontSize(bulletAttrs, baseFontSize)

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
        renderInlineMarkdown(doc, content, proseFontFamily, baseFontSize)
    }

    /**
     * 渲染分隔线
     */
    private fun renderHorizontalRule(doc: StyledDocument, baseFontSize: Int) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, JBColor.GRAY)
        StyleConstants.setFontSize(attrs, (baseFontSize - 1).coerceAtLeast(MIN_INLINE_FONT_SIZE))
        doc.insertString(doc.length, "\u2500".repeat(40), attrs)
    }

    /**
     * 渲染行内 Markdown（粗体、斜体、行内代码）
     */
    private fun renderInlineMarkdown(doc: StyledDocument, text: String, proseFontFamily: String, baseFontSize: Int) {
        val tokens = tokenizeInline(text)
        for (token in tokens) {
            when (token) {
                is InlineToken.Bold -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setBold(attrs, true)
                    StyleConstants.setFontFamily(attrs, proseFontFamily)
                    StyleConstants.setFontSize(attrs, baseFontSize)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Italic -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setItalic(attrs, true)
                    StyleConstants.setFontFamily(attrs, proseFontFamily)
                    StyleConstants.setFontSize(attrs, baseFontSize)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.InlineCode -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setFontFamily(attrs, CODE_FONT_FAMILY)
                    StyleConstants.setFontSize(attrs, (baseFontSize - 1).coerceAtLeast(MIN_INLINE_FONT_SIZE))
                    StyleConstants.setBackground(attrs, JBColor(CODE_BG_LIGHT, CODE_BG_DARK))
                    StyleConstants.setForeground(attrs, JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Plain -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setFontFamily(attrs, proseFontFamily)
                    StyleConstants.setFontSize(attrs, baseFontSize)
                    doc.insertString(doc.length, token.text, attrs)
                }
            }
        }
    }

    private fun resolveBaseFontSize(textPane: JTextPane): Int {
        val raw = textPane.font?.size ?: DEFAULT_BASE_FONT_SIZE
        return raw.coerceIn(MIN_BASE_FONT_SIZE, MAX_BASE_FONT_SIZE)
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
    private val TABLE_SEPARATOR_CELL_REGEX = Regex("""^:?-{3,}:?$""")
    private const val MIN_TABLE_COLUMNS = 2
    private const val PLAIN_TEXT_CONTENT_TYPE = "text/plain"
    private const val HTML_CONTENT_TYPE = "text/html"
    private const val MIN_INLINE_FONT_SIZE = 9
    private const val DEFAULT_BASE_FONT_SIZE = 11
    private const val MIN_BASE_FONT_SIZE = 9
    private const val MAX_BASE_FONT_SIZE = 36

    private data class TableBlock(
        val rows: List<List<String>>,
        val nextIndex: Int,
    )

    private sealed class InlineToken {
        abstract val text: String
        data class Plain(override val text: String) : InlineToken()
        data class Bold(override val text: String) : InlineToken()
        data class Italic(override val text: String) : InlineToken()
        data class InlineCode(override val text: String) : InlineToken()
    }
}
