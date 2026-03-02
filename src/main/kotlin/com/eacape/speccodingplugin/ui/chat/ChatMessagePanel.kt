package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.Timer
import javax.swing.border.AbstractBorder
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * 单条聊天消息的 UI 组件
 */
open class ChatMessagePanel(
    val role: MessageRole,
    initialContent: String = "",
    private val onDelete: ((ChatMessagePanel) -> Unit)? = null,
    private val onRegenerate: ((ChatMessagePanel) -> Unit)? = null,
    private var onContinue: ((ChatMessagePanel) -> Unit)? = null,
    private val onWorkflowFileOpen: ((WorkflowQuickActionParser.FileAction) -> Unit)? = null,
    private val onWorkflowCommandExecute: ((String) -> Unit)? = null,
    private var workflowSectionsEnabled: Boolean = true,
) : JPanel(BorderLayout()) {

    private val contentPane = JTextPane()
    private val contentHost = JPanel(BorderLayout())
    private val contentBuilder = StringBuilder()
    private val traceAssembler = StreamingTraceAssembler()
    private val codeBlocks = mutableListOf<String>()
    private val expandedVerboseEntries = mutableSetOf<String>()
    private val workflowSectionExpanded = mutableMapOf<WorkflowSectionParser.SectionKind, Boolean>()
    private var buttonPanel: JPanel? = null
    private var traceExpanded = false
    private var outputExpanded = false
    private var outputFilterLevel = OutputFilterLevel.KEY
    private var messageFinished = false
    private var contentVersion = 0
    private var traceVersion = 0
    private var cachedAssistantAnswerVersion = -1
    private var cachedAssistantAnswerContent = ""
    private var cachedTraceSnapshotContentVersion = -1
    private var cachedTraceSnapshotTraceVersion = -1
    private var cachedTraceSnapshotIncludeRawContent = true
    private var cachedTraceSnapshot: StreamingTraceAssembler.TraceSnapshot? = null

    enum class MessageRole {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0, 10, 0)

        // 内容区域
        configureReadableTextPane(contentPane)
        contentPane.border = JBUI.Borders.empty(8, 10)
        contentPane.background = getBackgroundColor()

        contentHost.isOpaque = false
        contentHost.add(contentPane, BorderLayout.CENTER)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = getBackgroundColor()
        wrapper.border = buildRoundedContainerBorder(
            lineColor = messageCardBorderColor(),
            arc = 12,
            padding = JBUI.insets(8, 10, 7, 10),
        )
        wrapper.add(contentHost, BorderLayout.CENTER)

        add(wrapper, BorderLayout.CENTER)

        if (initialContent.isNotEmpty()) {
            appendContent(initialContent)
        }
    }

    /**
     * 追加内容（用于流式渲染）
     */
    fun appendContent(text: String) {
        appendStreamContent(text = text)
    }

    fun appendStreamContent(text: String, event: ChatStreamEvent? = null) {
        if (event == null) {
            appendStreamContent(text = text, events = emptyList())
            return
        }
        appendStreamContent(text = text, events = listOf(event))
    }

    fun appendStreamContent(text: String, events: List<ChatStreamEvent>) {
        if (role == MessageRole.ASSISTANT && events.isNotEmpty()) {
            events.forEach { event ->
                traceAssembler.onStructuredEvent(event)
            }
            traceVersion += 1
        }
        if (text.isNotEmpty()) {
            contentBuilder.append(text)
            contentVersion += 1
        }
        renderContent()
    }

    fun appendStreamEvent(event: ChatStreamEvent) {
        appendStreamContent(text = "", event = event)
    }

    /**
     * 获取消息的原始文本内容
     */
    fun getContent(): String = contentBuilder.toString()

    open fun updateContinueAction(onContinue: ((ChatMessagePanel) -> Unit)?) {
        if (this.onContinue === onContinue) {
            return
        }
        this.onContinue = onContinue
        if (messageFinished) {
            addActionButtons()
        }
    }

    fun setWorkflowSectionsEnabled(enabled: Boolean) {
        if (workflowSectionsEnabled == enabled) return
        workflowSectionsEnabled = enabled
        renderContent(structured = messageFinished)
    }

    /**
     * 完成消息（流式结束后调用）
     */
    fun finishMessage() {
        messageFinished = true
        traceAssembler.markRunningItemsDone()
        traceVersion += 1
        renderContent(structured = true)
        extractCodeBlocks()
        addActionButtons()
    }

    private fun renderContent(structured: Boolean = false) {
        val useStructured = (structured || messageFinished) && workflowSectionsEnabled
        val content = contentBuilder.toString()
        if (role == MessageRole.ASSISTANT) {
            val traceSnapshot = resolveTraceSnapshot(content)
            if (traceSnapshot.hasTrace) {
                val answerContent = resolveAssistantAnswerContent(content)
                renderAssistantTraceContent(answerContent, traceSnapshot, useStructured)
            } else if (useStructured) {
                contentHost.removeAll()
                contentHost.add(createAssistantAnswerComponent(content, structured = true), BorderLayout.CENTER)
            } else {
                contentHost.removeAll()
                contentHost.add(contentPane, BorderLayout.CENTER)
                MarkdownRenderer.render(contentPane, content)
            }
        } else {
            contentHost.removeAll()
            contentHost.add(contentPane, BorderLayout.CENTER)
            val doc = contentPane.styledDocument
            if (role == MessageRole.USER) {
                renderUserPromptAwareContent(doc, content)
            } else {
                doc.remove(0, doc.length)
                val attrs = SimpleAttributeSet()
                StyleConstants.setFontFamily(attrs, "Monospaced")
                StyleConstants.setFontSize(attrs, 13)
                doc.insertString(0, content, attrs)
            }
        }
        revalidate()
        repaint()
    }

    private fun resolveAssistantAnswerContent(content: String): String {
        if (cachedAssistantAnswerVersion == contentVersion) {
            return cachedAssistantAnswerContent
        }
        val extracted = extractAssistantAnswerContent(content)
        cachedAssistantAnswerContent = extracted
        cachedAssistantAnswerVersion = contentVersion
        return extracted
    }

    private fun resolveTraceSnapshot(content: String): StreamingTraceAssembler.TraceSnapshot {
        val preferStructuredOnly = !messageFinished &&
            content.length >= TRACE_RAW_PARSE_SKIP_THRESHOLD &&
            traceAssembler.hasStructuredItems()
        val includeRawContent = !preferStructuredOnly

        val cached = cachedTraceSnapshot
        if (
            cached != null &&
            cachedTraceSnapshotContentVersion == contentVersion &&
            cachedTraceSnapshotTraceVersion == traceVersion &&
            cachedTraceSnapshotIncludeRawContent == includeRawContent
        ) {
            return cached
        }

        val snapshot = traceAssembler.snapshot(
            content = content,
            includeRawContent = includeRawContent,
        )
        cachedTraceSnapshot = snapshot
        cachedTraceSnapshotContentVersion = contentVersion
        cachedTraceSnapshotTraceVersion = traceVersion
        cachedTraceSnapshotIncludeRawContent = includeRawContent
        return snapshot
    }

    private fun renderUserPromptAwareContent(doc: StyledDocument, content: String) {
        doc.remove(0, doc.length)

        val baseAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, 13)
        }
        doc.insertString(0, content, baseAttrs)

        val promptAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, 13)
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, PROMPT_REFERENCE_FG)
            StyleConstants.setBackground(this, PROMPT_REFERENCE_BG)
        }
        PROMPT_REFERENCE_TOKEN_REGEX.findAll(content).forEach { match ->
            doc.setCharacterAttributes(
                match.range.first,
                match.value.length,
                promptAttrs,
                true,
            )
        }
    }

    private fun renderAssistantTraceContent(
        answerContent: String,
        traceSnapshot: StreamingTraceAssembler.TraceSnapshot,
        structured: Boolean,
    ) {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false
        container.border = JBUI.Borders.empty(6, 6, 2, 6)

        val processItems = traceSnapshot.items.filter { it.kind != ExecutionTimelineParser.Kind.OUTPUT }
        if (processItems.isNotEmpty()) {
            container.add(createTracePanel(processItems))
        }

        val outputItems = traceSnapshot.items.filter { it.kind == ExecutionTimelineParser.Kind.OUTPUT }
        if (outputItems.isNotEmpty()) {
            container.add(createOutputPanel(outputItems))
        }

        if (answerContent.isNotBlank()) {
            container.add(createAssistantAnswerComponent(answerContent, structured))
        }

        contentHost.removeAll()
        contentHost.add(container, BorderLayout.CENTER)
    }

    private fun createAssistantAnswerComponent(content: String, structured: Boolean): JPanel {
        if (!workflowSectionsEnabled) {
            return createMarkdownContainer(stripWorkflowSectionHeadings(content))
        }
        if (!structured) {
            return createMarkdownContainer(content)
        }

        val parseResult = WorkflowSectionParser.parse(content)
        if (parseResult.sections.isEmpty()) {
            return createMarkdownContainer(content)
        }

        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false
        container.border = JBUI.Borders.empty(8)

        if (parseResult.remainingText.isNotBlank()) {
            container.add(createMarkdownContainer(parseResult.remainingText))
        }

        parseResult.sections.forEach { section ->
            val title = when (section.kind) {
                WorkflowSectionParser.SectionKind.PLAN -> SpecCodingBundle.message("chat.workflow.section.plan")
                WorkflowSectionParser.SectionKind.EXECUTE -> SpecCodingBundle.message("chat.workflow.section.execute")
                WorkflowSectionParser.SectionKind.VERIFY -> SpecCodingBundle.message("chat.workflow.section.verify")
            }
            val defaultExpanded = section.kind != WorkflowSectionParser.SectionKind.EXECUTE
            val expanded = workflowSectionExpanded.getOrPut(section.kind) { defaultExpanded }

            val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
            header.isOpaque = false
            header.border = JBUI.Borders.emptyTop(6)

            val titleLabel = JBLabel(title)
            titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
            header.add(titleLabel)

            val toggleButton = JButton(
                SpecCodingBundle.message(
                    if (expanded) "chat.workflow.toggle.collapse" else "chat.workflow.toggle.expand"
                )
            )
            styleInlineActionButton(toggleButton)
            toggleButton.addActionListener {
                workflowSectionExpanded[section.kind] = !expanded
                renderContent(structured = true)
            }
            header.add(toggleButton)

            container.add(header)
            if (expanded) {
                container.add(createMarkdownContainer(section.content))
            }
        }
        return container
    }

    private fun stripWorkflowSectionHeadings(content: String): String {
        val parseResult = WorkflowSectionParser.parse(content)
        val normalized = if (parseResult.sections.isEmpty()) {
            content
        } else {
            buildString {
                if (parseResult.remainingText.isNotBlank()) {
                    append(parseResult.remainingText.trim())
                }
                parseResult.sections.forEach { section ->
                    val sectionContent = section.content.trim()
                    if (sectionContent.isBlank()) return@forEach
                    if (isNotEmpty()) appendLine().appendLine()
                    append(sectionContent)
                }
            }
        }
        return stripLooseWorkflowHeadingLines(normalized)
    }

    private fun stripLooseWorkflowHeadingLines(content: String): String {
        if (content.isBlank()) return content
        var inCodeFence = false
        val kept = mutableListOf<String>()
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                kept += line
                return@forEach
            }
            if (!inCodeFence && isWorkflowHeadingLine(trimmed)) {
                return@forEach
            }
            kept += line
        }
        return kept.joinToString("\n").trimEnd()
    }

    private fun isWorkflowHeadingLine(trimmedLine: String): Boolean {
        if (trimmedLine.isBlank()) return false
        var normalized = trimmedLine
            .replace(Regex("^#+\\s*"), "")
            .replace(Regex("^[-*+]\\s+"), "")
            .replace(Regex("^\\d+[.)]\\s+"), "")
            .trim()
            .removePrefix("**")
            .removeSuffix("**")
            .removePrefix("__")
            .removeSuffix("__")
            .removePrefix("`")
            .removeSuffix("`")
            .trim()
            .trimEnd(':', '：')
            .trim()
        if (normalized.isBlank()) return false
        normalized = normalized.lowercase(Locale.ROOT)
        return normalized in WORKFLOW_HEADING_TITLES
    }

    private fun createTracePanel(items: List<StreamingTraceAssembler.TraceItem>): JPanel {
        val displayItems = mergeTraceItemsForDisplay(items)
            .takeLast(MAX_TIMELINE_VISIBLE_ITEMS)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = traceCardBackgroundColor()
        wrapper.border = buildRoundedContainerBorder(
            lineColor = traceCardBorderColor(),
            arc = 12,
            padding = JBUI.insets(8, 10, 8, 10),
        )

        val summaryBar = JPanel(BorderLayout())
        summaryBar.isOpaque = false

        val summaryLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        summaryLeft.isOpaque = false

        val running = items.any { it.status == ExecutionTimelineParser.Status.RUNNING }
        if (running) {
            summaryLeft.add(
                createRunningIndicator(
                    color = statusColor(ExecutionTimelineParser.Status.RUNNING),
                    size = 11,
                    tooltip = statusLabel(ExecutionTimelineParser.Status.RUNNING),
                )
            )
        } else {
            val summaryIcon = JBLabel("●")
            summaryIcon.foreground = kindColor(ExecutionTimelineParser.Kind.TASK)
            summaryIcon.font = summaryIcon.font.deriveFont(11f)
            summaryLeft.add(summaryIcon)
        }

        val summaryLabel = JBLabel(SpecCodingBundle.message("chat.timeline.summary.label"))
        summaryLabel.font = summaryLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        summaryLeft.add(summaryLabel)

        val readCount = items.count { it.kind == ExecutionTimelineParser.Kind.READ }
        val editCount = items.count { it.kind == ExecutionTimelineParser.Kind.EDIT }
        val verifyCount = items.count { it.kind == ExecutionTimelineParser.Kind.VERIFY }
        summaryLeft.add(createSummaryBadge("${kindLabel(ExecutionTimelineParser.Kind.READ)} $readCount"))
        summaryLeft.add(createSummaryBadge("${kindLabel(ExecutionTimelineParser.Kind.EDIT)} $editCount"))
        summaryLeft.add(createSummaryBadge("${kindLabel(ExecutionTimelineParser.Kind.VERIFY)} $verifyCount"))
        summaryBar.add(summaryLeft, BorderLayout.CENTER)

        val toggleButton = JButton(
            SpecCodingBundle.message(
                if (traceExpanded) "chat.timeline.toggle.collapse" else "chat.timeline.toggle.expand"
            )
        )
        styleInlineActionButton(toggleButton)
        toggleButton.addActionListener {
            traceExpanded = !traceExpanded
            renderContent()
        }
        summaryBar.add(toggleButton, BorderLayout.EAST)

        wrapper.add(summaryBar, BorderLayout.NORTH)

        if (traceExpanded) {
            val listPanel = JPanel()
            listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
            listPanel.isOpaque = false
            listPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(traceSectionDividerColor(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(8),
            )

            displayItems.forEach { item ->
                listPanel.add(createTraceItemRow(item))
            }
            wrapper.add(listPanel, BorderLayout.CENTER)
        }

        val container = JPanel(BorderLayout())
        container.isOpaque = false
        container.border = JBUI.Borders.emptyBottom(6)
        container.add(wrapper, BorderLayout.CENTER)
        return container
    }

    private fun createOutputPanel(items: List<StreamingTraceAssembler.TraceItem>): JPanel {
        val mergedOutput = mergeOutputItemsForDisplay(
            items = items.takeLast(MAX_TIMELINE_VISIBLE_ITEMS),
            filterLevel = outputFilterLevel,
            charBudget = if (outputExpanded) null else OUTPUT_COLLAPSED_CHAR_BUDGET,
        )
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = outputCardBackgroundColor()
        wrapper.border = buildRoundedContainerBorder(
            lineColor = outputCardBorderColor(),
            arc = 12,
            padding = JBUI.insets(8, 10, 8, 10),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        left.isOpaque = false

        val running = mergedOutput.status == ExecutionTimelineParser.Status.RUNNING
        if (running) {
            left.add(
                createRunningIndicator(
                    color = statusColor(ExecutionTimelineParser.Status.RUNNING),
                    size = 11,
                    tooltip = statusLabel(ExecutionTimelineParser.Status.RUNNING),
                )
            )
        } else {
            val icon = JBLabel("▤")
            icon.foreground = kindColor(ExecutionTimelineParser.Kind.OUTPUT)
            icon.font = icon.font.deriveFont(12f)
            left.add(icon)
        }

        val title = JBLabel(SpecCodingBundle.message("chat.timeline.kind.output"))
        title.font = title.font.deriveFont(java.awt.Font.BOLD, 12f)
        left.add(title)

        left.add(createSummaryBadge(items.size.toString()))
        header.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        right.isOpaque = false

        val filterButton = JButton(
            SpecCodingBundle.message(
                "chat.timeline.output.filter.toggle",
                outputFilterLabel(outputFilterLevel),
            )
        )
        styleInlineActionButton(filterButton)
        filterButton.addActionListener {
            outputFilterLevel = outputFilterLevel.next()
            renderContent()
        }
        right.add(filterButton)

        val toggleButton = JButton(
            SpecCodingBundle.message(
                if (outputExpanded) "chat.timeline.toggle.collapse" else "chat.timeline.toggle.expand"
            )
        )
        styleInlineActionButton(toggleButton)
        toggleButton.addActionListener {
            outputExpanded = !outputExpanded
            renderContent()
        }
        right.add(toggleButton)
        header.add(right, BorderLayout.EAST)

        wrapper.add(header, BorderLayout.NORTH)

        if (outputExpanded) {
            val detailHost = JPanel(BorderLayout())
            detailHost.isOpaque = false
            detailHost.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(outputSectionDividerColor(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(8),
            )
            detailHost.add(
                createTraceDetailBlock(
                    StreamingTraceAssembler.TraceItem(
                        kind = ExecutionTimelineParser.Kind.OUTPUT,
                        status = mergedOutput.status,
                        detail = mergedOutput.detail,
                        fileAction = null,
                        isVerbose = true,
                    ),
                    forceVerbose = true,
                    showVerboseToggle = false,
                    alwaysExpanded = true,
                ),
                BorderLayout.CENTER
            )
            wrapper.add(detailHost, BorderLayout.CENTER)
        } else {
            val previewHost = JPanel(BorderLayout())
            previewHost.isOpaque = false
            previewHost.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(outputSectionDividerColor(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6),
            )
            previewHost.add(
                createTraceDetailBlock(
                    StreamingTraceAssembler.TraceItem(
                        kind = ExecutionTimelineParser.Kind.OUTPUT,
                        status = mergedOutput.status,
                        detail = mergedOutput.detail,
                        fileAction = null,
                        isVerbose = true,
                    ),
                    forceVerbose = true,
                    showVerboseToggle = false,
                ),
                BorderLayout.CENTER
            )
            wrapper.add(previewHost, BorderLayout.CENTER)
        }

        val container = JPanel(BorderLayout())
        container.isOpaque = false
        container.border = JBUI.Borders.emptyBottom(6)
        container.add(wrapper, BorderLayout.CENTER)
        return container
    }

    private fun createTraceItemRow(item: TraceDisplayItem): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = traceRowBackgroundColor()
        row.border = buildRoundedContainerBorder(
            lineColor = traceRowBorderColor(),
            arc = 10,
            padding = JBUI.insets(6, 8, 6, 8),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        headerLeft.isOpaque = false

        val icon = JBLabel(kindGlyph(item.kind))
        icon.foreground = kindColor(item.kind)
        icon.font = icon.font.deriveFont(12f)
        headerLeft.add(icon)

        val kindLabel = JBLabel(
            "${kindLabel(item.kind)} · ${statusLabel(item.status)}"
        )
        kindLabel.font = kindLabel.font.deriveFont(java.awt.Font.BOLD, 11f)
        kindLabel.foreground = statusColor(item.status)
        headerLeft.add(kindLabel)

        if (item.mergedCount > 1) {
            headerLeft.add(createSummaryBadge("x${item.mergedCount}"))
        }

        if (item.fileAction != null && onWorkflowFileOpen != null) {
            val openBtn = JButton(
                SpecCodingBundle.message(
                    "chat.workflow.action.openFile",
                    abbreviateForActionButton(item.fileAction.displayPath),
                )
            )
            styleInlineActionButton(openBtn)
            openBtn.toolTipText = SpecCodingBundle.message("chat.workflow.action.openFile.tooltip", item.fileAction.displayPath)
            openBtn.addActionListener {
                onWorkflowFileOpen.invoke(item.fileAction)
            }
            headerLeft.add(openBtn)
        }
        header.add(headerLeft, BorderLayout.CENTER)

        if (item.status == ExecutionTimelineParser.Status.RUNNING) {
            header.add(
                createRunningIndicator(
                    color = statusColor(item.status),
                    size = 10,
                    tooltip = statusLabel(item.status),
                ),
                BorderLayout.EAST
            )
        } else {
            val statusDot = JBLabel("●")
            statusDot.foreground = statusColor(item.status)
            statusDot.font = statusDot.font.deriveFont(10f)
            statusDot.toolTipText = statusLabel(item.status)
            header.add(statusDot, BorderLayout.EAST)
        }

        row.add(header, BorderLayout.NORTH)
        row.add(
            createTraceDetailBlock(
                StreamingTraceAssembler.TraceItem(
                    kind = item.kind,
                    status = item.status,
                    detail = item.detail,
                    fileAction = item.fileAction,
                    isVerbose = item.isVerbose,
                )
            ),
            BorderLayout.CENTER
        )
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(row, BorderLayout.CENTER)
        }
    }

    private fun createTraceDetailBlock(
        item: StreamingTraceAssembler.TraceItem,
        forceVerbose: Boolean = false,
        showVerboseToggle: Boolean = true,
        alwaysExpanded: Boolean = false,
    ): JPanel {
        val block = JPanel(BorderLayout())
        block.isOpaque = false
        block.border = JBUI.Borders.empty(4, 2, 2, 0)

        val key = entryKey(item)
        val verbose = forceVerbose || item.isVerbose
        val previewLength = if (forceVerbose) TRACE_OUTPUT_PREVIEW_LENGTH else TRACE_DETAIL_PREVIEW_LENGTH
        val hasOverflow = item.detail.length > previewLength
        val collapsed = !alwaysExpanded && verbose && key !in expandedVerboseEntries
        val markdownLike = looksLikeMarkdown(item.detail, scanLimit = MARKDOWN_DETECTION_SCAN_LIMIT)
        val previewText = if (markdownLike) {
            toMarkdownPreview(item.detail, previewLength)
        } else {
            toPreview(item.detail, previewLength)
        }
        val visibleText = if (alwaysExpanded) {
            item.detail
        } else if (collapsed) {
            previewText
        } else if (!verbose && hasOverflow) {
            previewText
        } else {
            item.detail
        }

        val detailPane = JTextPane()
        configureReadableTextPane(detailPane)
        detailPane.border = JBUI.Borders.empty()
        renderTraceDetail(detailPane, visibleText)

        val detailHost = JPanel(BorderLayout())
        detailHost.isOpaque = true
        detailHost.background = traceDetailBackgroundColor()
        detailHost.border = buildRoundedContainerBorder(
            lineColor = traceDetailBorderColor(),
            arc = 8,
            padding = JBUI.insets(4, 8, 4, 8),
        )
        detailHost.add(detailPane, BorderLayout.CENTER)
        block.add(detailHost, BorderLayout.CENTER)

        if (verbose && hasOverflow && showVerboseToggle) {
            val toggleText = if (collapsed) {
                SpecCodingBundle.message("chat.workflow.toggle.expand")
            } else {
                SpecCodingBundle.message("chat.workflow.toggle.collapse")
            }
            val toggleBtn = JButton(toggleText)
            styleInlineActionButton(toggleBtn)
            toggleBtn.addActionListener {
                if (collapsed) {
                    expandedVerboseEntries += key
                } else {
                    expandedVerboseEntries -= key
                }
                renderContent()
            }
            val controls = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4))
            controls.isOpaque = false
            controls.add(toggleBtn)
            block.add(controls, BorderLayout.SOUTH)
        }

        return block
    }

    private fun createMarkdownContainer(content: String): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        val segments = splitMarkdownSegments(content)
        if (segments.isEmpty()) {
            panel.add(createMarkdownPane(content))
        } else {
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    panel.add(JPanel().apply {
                        isOpaque = false
                        preferredSize = Dimension(0, JBUI.scale(MARKDOWN_SEGMENT_GAP))
                        minimumSize = preferredSize
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    })
                }
                when (segment) {
                    is MarkdownSegment.Markdown -> panel.add(createMarkdownPane(segment.text))
                    is MarkdownSegment.Code -> panel.add(createCodeBlockCard(segment.language, segment.code))
                }
            }
        }

        val commands = WorkflowQuickActionParser.parse(content)
            .commands
            .take(MAX_COMMAND_ACTIONS)
        if (commands.isNotEmpty()) {
            panel.add(createInlineCommandActions(commands))
        }

        return panel
    }

    private fun createCodeBlockCard(language: String, code: String): JPanel {
        val normalizedLanguage = language.substringBefore(' ').trim().ifBlank { "text" }
        val normalizedCode = code.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        val displayCode = if (normalizedCode.isBlank()) " " else normalizedCode

        val card = JPanel(BorderLayout())
        card.isOpaque = true
        card.background = CODE_CARD_BG
        card.border = buildRoundedContainerBorder(
            lineColor = CODE_CARD_BORDER,
            arc = 10,
            padding = JBUI.insets(6, 8, 6, 8),
        )

        val codeArea = JTextArea(displayCode).apply {
            isEditable = false
            isFocusable = true
            lineWrap = false
            wrapStyleWord = false
            tabSize = 4
            border = JBUI.Borders.empty(4, 2, 4, 2)
            font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, JBUI.scale(12))
            background = CODE_CARD_CODE_BG
            foreground = CODE_CARD_CODE_FG
            caretColor = foreground
        }

        val scrollPane = JScrollPane(codeArea).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.background = CODE_CARD_CODE_BG
            isOpaque = false
        }

        val lineCount = normalizedCode.lineSequence().count().coerceAtLeast(1)
        val lineHeight = codeArea.getFontMetrics(codeArea.font).height
        val hasCollapseToggle = lineCount > CODE_CARD_COLLAPSE_THRESHOLD_LINES
        var expanded = !hasCollapseToggle

        val header = JPanel(BorderLayout(JBUI.scale(6), 0))
        header.isOpaque = false
        header.border = JBUI.Borders.emptyBottom(4)

        val languageLabel = JBLabel(SpecCodingBundle.message("chat.markdown.code.languageTag", normalizedLanguage))
        languageLabel.font = JBUI.Fonts.smallFont()
        languageLabel.foreground = CODE_CARD_META_FG
        header.add(languageLabel, BorderLayout.WEST)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
        }

        val copyButton = JButton()
        styleIconActionButton(
            button = copyButton,
            icon = AllIcons.Actions.Copy,
            tooltip = SpecCodingBundle.message("chat.message.copy.code"),
        )
        copyButton.addActionListener {
            val copied = copyToClipboard(normalizedCode)
            showCopyFeedback(copyButton, copied = copied, iconOnly = true)
        }

        val toggleButton = if (hasCollapseToggle) {
            JButton().apply {
                styleInlineActionButton(this)
                foreground = CODE_CARD_META_FG
            }
        } else {
            null
        }

        fun applyExpandState() {
            val visibleLines = when {
                !hasCollapseToggle -> lineCount.coerceIn(1, CODE_CARD_MAX_VISIBLE_LINES)
                expanded -> lineCount.coerceIn(CODE_CARD_MIN_VISIBLE_LINES, CODE_CARD_MAX_VISIBLE_LINES)
                else -> lineCount.coerceIn(1, CODE_CARD_COLLAPSED_VISIBLE_LINES)
            }
            val preferredHeight = lineHeight * visibleLines + JBUI.scale(12)
            scrollPane.preferredSize = Dimension(0, preferredHeight)
            toggleButton?.let { btn ->
                val textKey = if (expanded) {
                    "chat.message.code.collapse"
                } else {
                    "chat.message.code.expand"
                }
                val text = SpecCodingBundle.message(textKey)
                btn.text = text
                btn.toolTipText = text
                btn.accessibleContext.accessibleName = text
            }
            card.revalidate()
            card.repaint()
        }

        toggleButton?.let { btn ->
            btn.addActionListener {
                expanded = !expanded
                applyExpandState()
            }
            actionsPanel.add(btn)
        }
        actionsPanel.add(copyButton)
        header.add(actionsPanel, BorderLayout.EAST)
        applyExpandState()

        card.add(header, BorderLayout.NORTH)
        card.add(scrollPane, BorderLayout.CENTER)
        return card
    }

    private fun splitMarkdownSegments(content: String): List<MarkdownSegment> {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()

        val segments = mutableListOf<MarkdownSegment>()
        val textBuffer = StringBuilder()
        val lines = normalized.split('\n')
        var index = 0

        fun appendTextLine(line: String) {
            if (textBuffer.isNotEmpty()) textBuffer.append('\n')
            textBuffer.append(line)
        }

        fun flushText() {
            val text = textBuffer.toString()
            if (text.isNotBlank()) {
                segments += MarkdownSegment.Markdown(text)
            }
            textBuffer.setLength(0)
        }

        while (index < lines.size) {
            val line = lines[index]
            val language = parseFenceLanguage(line)
            if (language == null) {
                appendTextLine(line)
                index += 1
                continue
            }

            val codeLines = mutableListOf<String>()
            var cursor = index + 1
            var closed = false
            while (cursor < lines.size) {
                val candidate = lines[cursor]
                if (isFenceEndLine(candidate)) {
                    closed = true
                    break
                }
                codeLines += candidate
                cursor += 1
            }

            if (!closed) {
                appendTextLine(line)
                codeLines.forEach(::appendTextLine)
                break
            }

            flushText()
            segments += MarkdownSegment.Code(language = language, code = codeLines.joinToString("\n"))
            index = cursor + 1
        }

        flushText()
        return if (segments.any { it is MarkdownSegment.Code }) segments else emptyList()
    }

    private fun parseFenceLanguage(line: String): String? {
        if (!line.startsWith("```")) return null
        if (line.length > 3 && line[3] == '`') return null
        val suffix = line.substring(3)
        if (suffix.contains('`')) return null
        return suffix.trim()
    }

    private fun isFenceEndLine(line: String): Boolean {
        return line.trimEnd() == "```"
    }

    private fun createInlineCommandActions(commands: List<String>): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4, 0, 0, 0)

        val label = JBLabel("${SpecCodingBundle.message("chat.workflow.action.commandsLabel")}:")
        label.foreground = JBColor.GRAY
        label.font = label.font.deriveFont(11f)
        panel.add(label)

        commands.forEach { command ->
            panel.add(createWorkflowCommandRunButton(command, inline = true))
        }
        return panel
    }

    private fun createMarkdownPane(content: String): JTextPane {
        val pane = JTextPane()
        configureReadableTextPane(pane)
        pane.border = JBUI.Borders.emptyTop(2)
        MarkdownRenderer.render(pane, content)
        return pane
    }

    private fun configureReadableTextPane(pane: JTextPane) {
        pane.isEditable = false
        pane.isOpaque = false
        pane.isFocusable = true
        pane.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
    }

    private fun renderTraceDetail(pane: JTextPane, content: String) {
        if (looksLikeMarkdown(content, scanLimit = MARKDOWN_DETECTION_SCAN_LIMIT)) {
            MarkdownRenderer.render(pane, content)
            return
        }
        val doc = pane.styledDocument
        doc.remove(0, doc.length)
        val attrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(attrs, "Monospaced")
        StyleConstants.setFontSize(attrs, 12)
        doc.insertString(0, content, attrs)
    }

    private fun looksLikeMarkdown(content: String, scanLimit: Int = Int.MAX_VALUE): Boolean {
        val markerSample = if (scanLimit == Int.MAX_VALUE) {
            content
        } else {
            content.take(scanLimit.coerceAtLeast(0))
        }
        if (markerSample.contains("**") || markerSample.contains('`')) return true
        var scannedChars = 0
        for (line in content.lineSequence()) {
            val trimmed = line.trimStart()
            scannedChars += line.length + 1
            val matched = MARKDOWN_HEADING_REGEX.matches(trimmed) ||
                trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") ||
                ORDERED_LIST_ITEM_REGEX.matches(trimmed)
            if (matched) {
                return true
            }
            if (scannedChars >= scanLimit) {
                break
            }
        }
        return false
    }

    private fun kindLabel(kind: ExecutionTimelineParser.Kind): String = when (kind) {
        ExecutionTimelineParser.Kind.THINK -> SpecCodingBundle.message("chat.timeline.kind.think")
        ExecutionTimelineParser.Kind.READ -> SpecCodingBundle.message("chat.timeline.kind.read")
        ExecutionTimelineParser.Kind.EDIT -> SpecCodingBundle.message("chat.timeline.kind.edit")
        ExecutionTimelineParser.Kind.TASK -> SpecCodingBundle.message("chat.timeline.kind.task")
        ExecutionTimelineParser.Kind.VERIFY -> SpecCodingBundle.message("chat.timeline.kind.verify")
        ExecutionTimelineParser.Kind.TOOL -> SpecCodingBundle.message("chat.timeline.kind.tool")
        ExecutionTimelineParser.Kind.OUTPUT -> SpecCodingBundle.message("chat.timeline.kind.output")
    }

    private fun statusLabel(status: ExecutionTimelineParser.Status): String = when (status) {
        ExecutionTimelineParser.Status.RUNNING -> SpecCodingBundle.message("chat.timeline.status.running")
        ExecutionTimelineParser.Status.DONE -> SpecCodingBundle.message("chat.timeline.status.done")
        ExecutionTimelineParser.Status.ERROR -> SpecCodingBundle.message("chat.timeline.status.error")
        ExecutionTimelineParser.Status.INFO -> SpecCodingBundle.message("chat.timeline.status.info")
    }

    private fun statusColor(status: ExecutionTimelineParser.Status): java.awt.Color = when (status) {
        ExecutionTimelineParser.Status.RUNNING -> JBColor(
            java.awt.Color(0, 102, 204),
            java.awt.Color(124, 182, 255),
        )
        ExecutionTimelineParser.Status.DONE -> JBColor(
            java.awt.Color(18, 112, 52),
            java.awt.Color(107, 206, 147),
        )
        ExecutionTimelineParser.Status.ERROR -> JBColor.RED
        ExecutionTimelineParser.Status.INFO -> JBColor.GRAY
    }

    private fun kindGlyph(kind: ExecutionTimelineParser.Kind): String = when (kind) {
        ExecutionTimelineParser.Kind.THINK -> "◔"
        ExecutionTimelineParser.Kind.READ -> "▤"
        ExecutionTimelineParser.Kind.EDIT -> "✎"
        ExecutionTimelineParser.Kind.TASK -> "▸"
        ExecutionTimelineParser.Kind.VERIFY -> "✓"
        ExecutionTimelineParser.Kind.TOOL -> "⌘"
        ExecutionTimelineParser.Kind.OUTPUT -> "≡"
    }

    private fun kindColor(kind: ExecutionTimelineParser.Kind): java.awt.Color = when (kind) {
        ExecutionTimelineParser.Kind.THINK -> JBColor(java.awt.Color(85, 105, 133), java.awt.Color(170, 188, 212))
        ExecutionTimelineParser.Kind.READ -> JBColor(java.awt.Color(58, 112, 171), java.awt.Color(135, 189, 247))
        ExecutionTimelineParser.Kind.EDIT -> JBColor(java.awt.Color(25, 123, 87), java.awt.Color(109, 207, 171))
        ExecutionTimelineParser.Kind.TASK -> JBColor(java.awt.Color(92, 96, 158), java.awt.Color(164, 170, 245))
        ExecutionTimelineParser.Kind.VERIFY -> JBColor(java.awt.Color(44, 132, 79), java.awt.Color(131, 217, 164))
        ExecutionTimelineParser.Kind.TOOL -> JBColor(java.awt.Color(140, 89, 34), java.awt.Color(231, 178, 121))
        ExecutionTimelineParser.Kind.OUTPUT -> JBColor(java.awt.Color(117, 111, 140), java.awt.Color(191, 184, 220))
    }

    private fun mergeTraceItemsForDisplay(items: List<StreamingTraceAssembler.TraceItem>): List<TraceDisplayItem> {
        if (items.isEmpty()) return emptyList()

        val merged = mutableListOf<TraceDisplayItem>()
        var currentGroup = mutableListOf<StreamingTraceAssembler.TraceItem>()

        fun flush() {
            if (currentGroup.isEmpty()) return
            merged += toTraceDisplayItem(currentGroup)
            currentGroup = mutableListOf()
        }

        items.forEach { item ->
            val currentKind = currentGroup.firstOrNull()?.kind
            if (currentKind == null || currentKind == item.kind) {
                currentGroup += item
            } else {
                flush()
                currentGroup += item
            }
        }
        flush()

        return merged
    }

    private fun toTraceDisplayItem(groupItems: List<StreamingTraceAssembler.TraceItem>): TraceDisplayItem {
        val first = groupItems.first()
        val mergedCount = groupItems.size
        val status = groupItems.maxByOrNull { statusPriority(it.status) }?.status ?: first.status
        val detail = if (mergedCount == 1) {
            first.detail
        } else {
            val previews = groupItems
                .asSequence()
                .map { toPreview(it.detail, TRACE_GROUP_DETAIL_PREVIEW_LENGTH) }
                .distinct()
                .take(TRACE_GROUP_DETAIL_SAMPLE_COUNT)
                .toList()
            val more = (mergedCount - previews.size).coerceAtLeast(0)
            val lines = previews.map { "- $it" } +
                if (more > 0) listOf("... (+$more)") else emptyList()
            lines.joinToString("\n")
        }

        return TraceDisplayItem(
            kind = first.kind,
            status = status,
            detail = detail,
            fileAction = if (mergedCount == 1) first.fileAction else null,
            isVerbose = groupItems.any { it.isVerbose },
            mergedCount = mergedCount,
        )
    }

    private fun mergeOutputItemsForDisplay(
        items: List<StreamingTraceAssembler.TraceItem>,
        filterLevel: OutputFilterLevel,
        charBudget: Int? = null,
    ): OutputDisplay {
        if (items.isEmpty()) {
            return OutputDisplay(
                status = ExecutionTimelineParser.Status.INFO,
                detail = "",
            )
        }
        val status = items.maxByOrNull { statusPriority(it.status) }?.status
            ?: ExecutionTimelineParser.Status.INFO

        val lines = mutableListOf<String>()
        var previousLine: String? = null
        var previousBlank = false
        var consumedChars = 0
        var truncated = false
        outer@ for (item in items) {
            for (raw in item.detail.lineSequence()) {
                val line = raw.trimEnd('\r').trimEnd()
                val blank = line.isBlank()
                if (blank && previousBlank) continue
                if (line.isNotBlank() && line == previousLine) continue

                val estimatedChars = line.length + 1
                if (charBudget != null && consumedChars + estimatedChars > charBudget) {
                    truncated = true
                    break@outer
                }

                lines += line
                previousLine = line
                previousBlank = blank
                consumedChars += estimatedChars
            }
        }

        val mergedDetail = when {
            lines.isNotEmpty() -> buildString {
                append(lines.joinToString("\n").trim())
                if (truncated && isNotBlank()) {
                    append("\n...")
                }
            }
            charBudget != null -> {
                val fallback = items.firstOrNull()
                    ?.detail
                    ?.take(charBudget.coerceAtLeast(1))
                    ?.trim()
                    .orEmpty()
                if (fallback.length >= charBudget.coerceAtLeast(1) && fallback.isNotBlank()) {
                    "$fallback\n..."
                } else {
                    fallback
                }
            }
            else -> items.firstOrNull()?.detail?.trim().orEmpty()
        }

        return OutputDisplay(
            status = status,
            detail = applyOutputFilter(mergedDetail, filterLevel),
        )
    }

    private fun applyOutputFilter(rawDetail: String, level: OutputFilterLevel): String {
        if (level == OutputFilterLevel.ALL) return rawDetail
        if (rawDetail.isBlank()) return rawDetail

        val nonBlankLines = rawDetail
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (nonBlankLines.isEmpty()) return rawDetail

        val keyLines = selectKeyOutputLines(nonBlankLines)
        if (keyLines.isEmpty()) {
            return nonBlankLines
                .take(OUTPUT_FILTER_FALLBACK_LINES)
                .joinToString("\n")
        }

        val filteredCount = (nonBlankLines.size - keyLines.size).coerceAtLeast(0)
        val body = keyLines.joinToString("\n")
        if (filteredCount <= 0) return body

        return buildString {
            append(body)
            append("\n\n")
            append(SpecCodingBundle.message("chat.timeline.output.filtered.more", filteredCount))
        }
    }

    private fun selectKeyOutputLines(lines: List<String>): List<String> {
        val kept = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val lowered = line.lowercase()
            val keywordMatch = OUTPUT_KEYWORDS.any { lowered.contains(it) } ||
                OUTPUT_KEYWORDS_ZH.any { line.contains(it) }
            val keyValueMatch = OUTPUT_KEY_VALUE_REGEX.containsMatchIn(line)
            val structureMatch = line.startsWith("[") ||
                line.startsWith("#") ||
                line.startsWith("$ ") ||
                line.startsWith("> ")

            if (keywordMatch || keyValueMatch || structureMatch) {
                kept += line
            } else if (index < OUTPUT_FILTER_CONTEXT_HEAD_LINES && kept.size < OUTPUT_FILTER_MIN_LINES) {
                kept += line
            }
        }

        val deduped = kept.distinct()
        if (deduped.isNotEmpty()) {
            return deduped.take(OUTPUT_FILTER_MAX_LINES)
        }

        return lines
            .take(OUTPUT_FILTER_FALLBACK_LINES)
            .distinct()
    }

    private fun outputFilterLabel(level: OutputFilterLevel): String {
        return when (level) {
            OutputFilterLevel.KEY -> SpecCodingBundle.message("chat.timeline.output.filter.key")
            OutputFilterLevel.ALL -> SpecCodingBundle.message("chat.timeline.output.filter.all")
        }
    }

    private fun statusPriority(status: ExecutionTimelineParser.Status): Int {
        return when (status) {
            ExecutionTimelineParser.Status.ERROR -> 3
            ExecutionTimelineParser.Status.RUNNING -> 2
            ExecutionTimelineParser.Status.DONE -> 1
            ExecutionTimelineParser.Status.INFO -> 0
        }
    }

    private fun entryKey(item: StreamingTraceAssembler.TraceItem): String {
        val detail = item.detail
        if (detail.isBlank()) {
            return "${item.kind.name}:_"
        }
        val head = detail.take(ENTRY_KEY_HEAD_CHARS).lowercase(Locale.ROOT)
        val tail = if (detail.length > ENTRY_KEY_HEAD_CHARS) {
            detail.takeLast(ENTRY_KEY_TAIL_CHARS).lowercase(Locale.ROOT)
        } else {
            ""
        }
        val digest = 31 * (31 + detail.length) + head.hashCode() + 31 * tail.hashCode()
        return "${item.kind.name}:$digest"
    }

    private fun extractCodeBlocks() {
        val content = contentBuilder.toString()
        val regex = Regex("```\\w*\\n([\\s\\S]*?)```")
        codeBlocks.clear()
        regex.findAll(content).forEach {
            codeBlocks.add(it.groupValues[1].trim())
        }
    }

    private fun addActionButtons() {
        // 系统消息不需要操作按钮
        if (role == MessageRole.SYSTEM) return

        buttonPanel?.let { remove(it) }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        buttonPanel.isOpaque = false
        buttonPanel.border = JBUI.Borders.emptyTop(6)

        // 复制全文按钮
        val copyAllBtn = JButton()
        styleIconActionButton(
            button = copyAllBtn,
            icon = AllIcons.Actions.Copy,
            tooltip = SpecCodingBundle.message("chat.message.copy.all"),
        )
        copyAllBtn.addActionListener {
            val copied = copyToClipboard(contentBuilder.toString())
            showCopyFeedback(copyAllBtn, copied = copied, iconOnly = true)
        }
        buttonPanel.add(copyAllBtn)

        val continueHandler = onContinue
        if (role == MessageRole.ASSISTANT && continueHandler != null) {
            val continueBtn = JButton()
            styleIconActionButton(
                button = continueBtn,
                icon = AllIcons.Actions.Execute,
                tooltip = SpecCodingBundle.message("chat.message.continue"),
            )
            continueBtn.addActionListener { continueHandler.invoke(this) }
            buttonPanel.add(continueBtn)
        }

        // 重新生成按钮（仅 Assistant 消息）
        if (role == MessageRole.ASSISTANT && onRegenerate != null) {
            val regenBtn = JButton()
            styleIconActionButton(
                button = regenBtn,
                icon = AllIcons.Actions.Refresh,
                tooltip = SpecCodingBundle.message("chat.message.regenerate"),
            )
            regenBtn.addActionListener { onRegenerate.invoke(this) }
            buttonPanel.add(regenBtn)
        }

        // 删除按钮（User 和 Assistant 消息）
        if ((role == MessageRole.USER || role == MessageRole.ASSISTANT) && onDelete != null) {
            val deleteBtn = JButton()
            styleIconActionButton(
                button = deleteBtn,
                icon = AllIcons.Actions.GC,
                tooltip = SpecCodingBundle.message("chat.message.delete"),
            )
            deleteBtn.addActionListener { onDelete.invoke(this) }
            buttonPanel.add(deleteBtn)
        }

        this.buttonPanel = buttonPanel
        add(buttonPanel, BorderLayout.SOUTH)
        revalidate()
    }

    private fun createWorkflowCommandRunButton(command: String, inline: Boolean): JButton {
        val display = if (command.length > MAX_COMMAND_DISPLAY_LENGTH) {
            "${command.take(MAX_COMMAND_DISPLAY_LENGTH - 3)}..."
        } else {
            command
        }

        val btn = JButton(SpecCodingBundle.message("chat.workflow.action.runCommand", display))
        if (inline) {
            styleInlineActionButton(btn)
        } else {
            styleActionButton(btn)
        }
        val tooltipKey = if (command.trim().startsWith("/")) {
            "chat.workflow.action.runSlashCommand.tooltip"
        } else {
            "chat.workflow.action.runCommand.tooltip"
        }
        btn.toolTipText = SpecCodingBundle.message(tooltipKey, command)
        btn.addActionListener {
            if (onWorkflowCommandExecute != null) {
                onWorkflowCommandExecute.invoke(command)
            } else {
                val copied = copyToClipboard(command)
                showCopyFeedback(btn, copied = copied, iconOnly = false)
            }
        }
        return btn
    }

    private fun copyToClipboard(text: String): Boolean {
        val selection = StringSelection(text)
        val copiedByIde = runCatching {
            CopyPasteManager.getInstance().setContents(selection)
        }.isSuccess
        if (copiedByIde) return true

        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            true
        }.getOrElse { false }
    }

    private fun showCopyFeedback(button: JButton, copied: Boolean, iconOnly: Boolean) {
        val previousTimer = button.getClientProperty(COPY_FEEDBACK_TIMER_KEY) as? Timer
        previousTimer?.stop()

        val originalText = button.text
        val originalIcon = button.icon
        val originalToolTip = button.toolTipText
        val originalAccessibleName = button.accessibleContext.accessibleName
        val originalForeground = button.foreground

        val feedbackText = when {
            iconOnly && copied -> COPY_FEEDBACK_ICON_SUCCESS
            iconOnly && !copied -> COPY_FEEDBACK_ICON_FAILURE
            copied -> SpecCodingBundle.message("chat.message.copy.copied")
            else -> SpecCodingBundle.message("chat.message.copy.failed")
        }
        val feedbackTip = SpecCodingBundle.message(
            if (copied) "chat.message.copy.copied" else "chat.message.copy.failed"
        )

        if (iconOnly) {
            button.icon = null
        }
        button.text = feedbackText
        button.toolTipText = feedbackTip
        button.accessibleContext.accessibleName = feedbackTip
        button.foreground = if (copied) {
            JBColor(java.awt.Color(23, 128, 62), java.awt.Color(119, 226, 160))
        } else {
            JBColor(java.awt.Color(175, 48, 48), java.awt.Color(255, 149, 149))
        }

        val timer = Timer(COPY_FEEDBACK_DURATION_MS) {
            button.text = originalText
            button.icon = originalIcon
            button.toolTipText = originalToolTip
            button.accessibleContext.accessibleName = originalAccessibleName
            button.foreground = originalForeground
            button.putClientProperty(COPY_FEEDBACK_TIMER_KEY, null)
        }
        timer.isRepeats = false
        button.putClientProperty(COPY_FEEDBACK_TIMER_KEY, timer)
        timer.start()
    }

    private fun abbreviateForActionButton(displayPath: String): String {
        val normalized = displayPath.trim()
        if (normalized.length <= MAX_ACTION_FILE_DISPLAY_LENGTH) {
            return normalized
        }
        return "...${normalized.takeLast(MAX_ACTION_FILE_DISPLAY_LENGTH - 3)}"
    }

    private fun styleActionButton(button: JButton) {
        button.margin = JBUI.insets(3, 8, 3, 8)
        button.isFocusPainted = false
        button.isFocusable = false
        button.font = button.font.deriveFont(11f)
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(1, 6, 1, 6)
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private fun styleInlineActionButton(button: JButton) {
        button.margin = JBUI.insets(1, 5, 1, 5)
        button.isFocusPainted = false
        button.isFocusable = false
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(1, 3, 1, 3)
        button.font = button.font.deriveFont(11f)
        button.foreground = JBColor(
            java.awt.Color(78, 102, 132),
            java.awt.Color(159, 189, 226),
        )
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private fun styleIconActionButton(button: JButton, icon: Icon, tooltip: String) {
        styleActionButton(button)
        button.icon = icon
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
        button.preferredSize = JBUI.size(26, 22)
        button.minimumSize = JBUI.size(26, 22)
    }

    private fun toPreview(text: String, limit: Int): String {
        if (text.isBlank()) return ""
        val targetLength = limit.coerceAtLeast(1)
        val scanBudget = targetLength + PREVIEW_EXTRA_SCAN_CHARS
        val compact = StringBuilder(scanBudget)
        var sawNonWhitespace = false
        var pendingSpace = false
        var index = 0

        while (index < text.length && compact.length < scanBudget) {
            val ch = text[index]
            if (ch.isWhitespace()) {
                if (sawNonWhitespace) {
                    pendingSpace = true
                }
            } else {
                if (pendingSpace && compact.isNotEmpty() && compact.length < scanBudget) {
                    compact.append(' ')
                }
                compact.append(ch)
                sawNonWhitespace = true
                pendingSpace = false
            }
            index += 1
        }

        val normalized = compact.toString().trim()
        if (normalized.isEmpty()) return ""
        val hasOverflow = index < text.length || normalized.length > targetLength
        return if (!hasOverflow) {
            normalized
        } else {
            normalized.take(targetLength).trimEnd() + "..."
        }
    }

    private fun toMarkdownPreview(text: String, limit: Int): String {
        if (text.isBlank()) return ""
        val targetLength = limit.coerceAtLeast(1)
        val rawBudget = (targetLength + MARKDOWN_PREVIEW_EXTRA_SCAN_CHARS).coerceAtLeast(targetLength)
        val sampled = text.take(rawBudget)
        val normalized = sampled
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.length <= targetLength && sampled.length == text.length) {
            return normalized
        }

        val clipped = normalized.take(targetLength).trimEnd()
        val splitAt = clipped.lastIndexOf('\n')
        if (splitAt >= targetLength / 2) {
            return clipped.substring(0, splitAt).trimEnd() + "\n..."
        }
        return clipped + "..."
    }

    private fun extractAssistantAnswerContent(content: String): String {
        if (content.isBlank()) return ""

        val kept = mutableListOf<String>()
        var skippingOutputBody = false

        content.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val timelineItem = ExecutionTimelineParser.parseLine(rawLine)
            if (timelineItem != null) {
                skippingOutputBody = timelineItem.kind == ExecutionTimelineParser.Kind.OUTPUT ||
                    timelineItem.kind == ExecutionTimelineParser.Kind.TOOL
                return@forEach
            }

            if (skippingOutputBody) {
                if (trimmed.isBlank()) {
                    skippingOutputBody = false
                } else if (isLikelyWorkflowHeading(trimmed)) {
                    skippingOutputBody = false
                    kept += rawLine
                }
                return@forEach
            }

            kept += rawLine
        }

        return kept.joinToString("\n").trim()
    }

    private fun isLikelyWorkflowHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("## ")) return true
        val normalized = trimmed
            .removePrefix("**")
            .removeSuffix("**")
            .trim()
            .trimEnd(':', '：')
            .lowercase()
        return normalized in WORKFLOW_HEADING_TITLES
    }

    private fun getBackgroundColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(244, 248, 255),
            java.awt.Color(41, 47, 58)
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(249, 251, 253),
            java.awt.Color(38, 44, 52)
        )
        MessageRole.SYSTEM -> JBColor(
            java.awt.Color(246, 247, 249),
            java.awt.Color(45, 47, 51)
        )
        MessageRole.ERROR -> JBColor(
            java.awt.Color(255, 242, 242),
            java.awt.Color(62, 37, 37)
        )
    }

    private fun messageCardBorderColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(208, 220, 238),
            java.awt.Color(92, 102, 115),
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(211, 220, 232),
            java.awt.Color(90, 100, 112),
        )
        MessageRole.SYSTEM -> JBColor(
            java.awt.Color(212, 219, 228),
            java.awt.Color(93, 98, 105),
        )
        MessageRole.ERROR -> JBColor(
            java.awt.Color(232, 200, 200),
            java.awt.Color(126, 82, 82),
        )
    }

    private fun traceCardBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(252, 253, 255),
        java.awt.Color(42, 47, 55),
    )

    private fun traceCardBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(197, 208, 222),
        java.awt.Color(91, 101, 112),
    )

    private fun outputCardBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(252, 253, 255),
        java.awt.Color(41, 46, 53),
    )

    private fun outputCardBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(196, 207, 221),
        java.awt.Color(89, 99, 111),
    )

    private fun traceRowBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(250, 252, 255),
        java.awt.Color(46, 52, 60),
    )

    private fun traceRowBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(210, 219, 230),
        java.awt.Color(92, 102, 114),
    )

    private fun traceDetailBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(247, 250, 255),
        java.awt.Color(43, 49, 58),
    )

    private fun traceDetailBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(214, 223, 233),
        java.awt.Color(92, 102, 114),
    )

    private fun traceSectionDividerColor(): java.awt.Color = JBColor(
        java.awt.Color(218, 225, 234),
        java.awt.Color(96, 106, 119),
    )

    private fun outputSectionDividerColor(): java.awt.Color = JBColor(
        java.awt.Color(216, 224, 234),
        java.awt.Color(96, 106, 119),
    )

    private fun createSummaryBadge(text: String): JBLabel {
        val badge = JBLabel(text)
        badge.isOpaque = true
        badge.background = JBColor(
            java.awt.Color(242, 246, 252),
            java.awt.Color(56, 63, 74),
        )
        badge.foreground = JBColor(
            java.awt.Color(86, 102, 122),
            java.awt.Color(174, 190, 212),
        )
        badge.font = badge.font.deriveFont(10.5f)
        badge.border = buildRoundedContainerBorder(
            lineColor = JBColor(
                java.awt.Color(219, 227, 236),
                java.awt.Color(83, 94, 107),
            ),
            arc = 9,
            padding = JBUI.insets(1, 6, 1, 6),
        )
        return badge
    }

    private fun createRunningIndicator(
        color: java.awt.Color,
        size: Int,
        tooltip: String,
    ): RunningSpinnerIndicator {
        return RunningSpinnerIndicator(
            color = color,
            size = size,
        ).apply {
            toolTipText = tooltip
        }
    }

    private fun buildRoundedContainerBorder(
        lineColor: java.awt.Color,
        arc: Int,
        padding: Insets,
    ): Border {
        return CompoundBorder(
            RoundedLineBorder(
                lineColor = lineColor,
                arc = arc,
            ),
            JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right),
        )
    }

    private class RoundedLineBorder(
        private val lineColor: java.awt.Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = lineColor
            val safeThickness = thickness.coerceAtLeast(1)
            repeat(safeThickness) { index ->
                val offset = index + 0.5f
                val drawWidth = width - index * 2 - 1f
                val drawHeight = height - index * 2 - 1f
                if (drawWidth <= 0f || drawHeight <= 0f) return@repeat
                val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                g2.draw(
                    RoundRectangle2D.Float(
                        x + offset,
                        y + offset,
                        drawWidth,
                        drawHeight,
                        arcSize,
                        arcSize,
                    )
                )
            }
            g2.dispose()
        }

        override fun getBorderInsets(c: Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }

    private class RunningSpinnerIndicator(
        private val color: java.awt.Color,
        size: Int,
    ) : JComponent() {
        private var angle = 0
        private val timer = Timer(SPINNER_TICK_MS) {
            angle = (angle + SPINNER_STEP_DEGREES) % 360
            repaint()
        }
        private val diameter = JBUI.scale(size)

        init {
            isOpaque = false
            preferredSize = Dimension(diameter, diameter)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        override fun addNotify() {
            super.addNotify()
            if (!timer.isRunning) {
                timer.start()
            }
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

        override fun paintComponent(g: Graphics?) {
            super.paintComponent(g)
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())

            val drawSize = (diameter - 2).coerceAtLeast(6)
            val arcExtent = 120

            g2.color = faded(color, alpha = 70)
            g2.drawArc(1, 1, drawSize, drawSize, 0, 360)

            g2.color = color
            g2.drawArc(1, 1, drawSize, drawSize, -angle, arcExtent)
            g2.dispose()
        }

        private fun faded(base: java.awt.Color, alpha: Int): java.awt.Color {
            val resolvedAlpha = alpha.coerceIn(0, 255)
            return java.awt.Color(base.red, base.green, base.blue, resolvedAlpha)
        }
    }

    companion object {
        private const val MAX_FILE_ACTIONS = 4
        private const val MAX_COMMAND_ACTIONS = 4
        private const val MAX_COMMAND_DISPLAY_LENGTH = 26
        private const val MAX_ACTION_FILE_DISPLAY_LENGTH = 30
        private const val MARKDOWN_SEGMENT_GAP = 6
        private const val CODE_CARD_MIN_VISIBLE_LINES = 3
        private const val CODE_CARD_MAX_VISIBLE_LINES = 14
        private const val CODE_CARD_COLLAPSE_THRESHOLD_LINES = 8
        private const val CODE_CARD_COLLAPSED_VISIBLE_LINES = 4
        private const val TRACE_DETAIL_PREVIEW_LENGTH = 220
        private const val TRACE_OUTPUT_PREVIEW_LENGTH = 140
        private const val TRACE_GROUP_DETAIL_PREVIEW_LENGTH = 96
        private const val TRACE_GROUP_DETAIL_SAMPLE_COUNT = 2
        private const val MAX_TIMELINE_VISIBLE_ITEMS = 10
        private const val TRACE_RAW_PARSE_SKIP_THRESHOLD = 6_000
        private const val OUTPUT_COLLAPSED_CHAR_BUDGET = 12_000
        private const val MARKDOWN_DETECTION_SCAN_LIMIT = 6_000
        private const val ENTRY_KEY_HEAD_CHARS = 160
        private const val ENTRY_KEY_TAIL_CHARS = 80
        private const val PREVIEW_EXTRA_SCAN_CHARS = 120
        private const val MARKDOWN_PREVIEW_EXTRA_SCAN_CHARS = 240
        private const val OUTPUT_FILTER_MIN_LINES = 2
        private const val OUTPUT_FILTER_MAX_LINES = 24
        private const val OUTPUT_FILTER_FALLBACK_LINES = 6
        private const val OUTPUT_FILTER_CONTEXT_HEAD_LINES = 6
        private const val COPY_FEEDBACK_DURATION_MS = 1000
        private const val COPY_FEEDBACK_TIMER_KEY = "spec.copy.feedback.timer"
        private const val COPY_FEEDBACK_ICON_SUCCESS = "OK"
        private const val COPY_FEEDBACK_ICON_FAILURE = "!"
        private const val SPINNER_TICK_MS = 70
        private const val SPINNER_STEP_DEGREES = 20
        private val CODE_CARD_BG = JBColor(java.awt.Color(245, 248, 253), java.awt.Color(47, 53, 63))
        private val CODE_CARD_BORDER = JBColor(java.awt.Color(214, 224, 241), java.awt.Color(78, 88, 104))
        private val CODE_CARD_META_FG = JBColor(java.awt.Color(101, 118, 146), java.awt.Color(156, 178, 210))
        private val CODE_CARD_CODE_BG = JBColor(java.awt.Color(239, 244, 250), java.awt.Color(39, 45, 53))
        private val CODE_CARD_CODE_FG = JBColor(java.awt.Color(49, 59, 72), java.awt.Color(214, 223, 236))
        private val PROMPT_REFERENCE_FG = JBColor(
            java.awt.Color(23, 96, 186),
            java.awt.Color(136, 188, 255),
        )
        private val PROMPT_REFERENCE_BG = JBColor(
            java.awt.Color(226, 239, 255),
            java.awt.Color(55, 75, 101),
        )
        private val MARKDOWN_HEADING_REGEX = Regex("""^#{1,6}\s+.*$""")
        private val ORDERED_LIST_ITEM_REGEX = Regex("""^\d+\.\s+.*""")
        private val PROMPT_REFERENCE_TOKEN_REGEX = Regex("""(?<!\S)#([\p{L}\p{N}_.-]+)""")
        private val WORKFLOW_HEADING_TITLES = setOf(
            "plan", "planning", "计划", "规划",
            "execute", "execution", "implement", "执行", "实施",
            "verify", "verification", "test", "验证", "测试",
        )
        private val OUTPUT_KEYWORDS = listOf(
            "model", "provider", "workdir", "sandbox", "approval", "session id",
            "error", "failed", "success", "warning", "exit", "command", "token",
            "cost", "trace", "task", "verify", "read", "edit", "spec", "mcp", "hook",
        )
        private val OUTPUT_KEYWORDS_ZH = listOf(
            "模型", "提供商", "工作目录", "沙箱", "审批", "会话",
            "错误", "失败", "成功", "警告", "退出", "命令",
            "成本", "任务", "验证", "读取", "编辑", "规格", "输出",
        )
        private val OUTPUT_KEY_VALUE_REGEX = Regex("""^[^\\s].{0,40}[:：]\\s*.+$""")
    }

    private data class TraceDisplayItem(
        val kind: ExecutionTimelineParser.Kind,
        val status: ExecutionTimelineParser.Status,
        val detail: String,
        val fileAction: WorkflowQuickActionParser.FileAction?,
        val isVerbose: Boolean,
        val mergedCount: Int,
    )

    private data class OutputDisplay(
        val status: ExecutionTimelineParser.Status,
        val detail: String,
    )

    private sealed class MarkdownSegment {
        data class Markdown(val text: String) : MarkdownSegment()

        data class Code(
            val language: String,
            val code: String,
        ) : MarkdownSegment()
    }

    private enum class OutputFilterLevel {
        KEY,
        ALL,
        ;

        fun next(): OutputFilterLevel {
            return when (this) {
                KEY -> ALL
                ALL -> KEY
            }
        }
    }
}
