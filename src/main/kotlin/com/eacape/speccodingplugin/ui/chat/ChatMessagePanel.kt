package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * 单条聊天消息的 UI 组件
 */
class ChatMessagePanel(
    val role: MessageRole,
    initialContent: String = "",
    private val onDelete: ((ChatMessagePanel) -> Unit)? = null,
    private val onRegenerate: ((ChatMessagePanel) -> Unit)? = null,
    private val onContinue: ((ChatMessagePanel) -> Unit)? = null,
    private val onWorkflowFileOpen: ((WorkflowQuickActionParser.FileAction) -> Unit)? = null,
    private val onWorkflowCommandInsert: ((String) -> Unit)? = null,
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
    private var messageFinished = false

    enum class MessageRole {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)

        // 内容区域
        contentPane.isEditable = false
        contentPane.isOpaque = false
        contentPane.border = JBUI.Borders.empty(8, 10)
        contentPane.background = getBackgroundColor()
        contentPane.isFocusable = false

        contentHost.isOpaque = false
        contentHost.add(contentPane, BorderLayout.CENTER)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = getBackgroundColor()
        wrapper.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 10),
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
        if (event != null && role == MessageRole.ASSISTANT) {
            traceAssembler.onStructuredEvent(event)
        }
        appendStreamContent(text = text, events = emptyList())
    }

    fun appendStreamContent(text: String, events: List<ChatStreamEvent>) {
        if (role == MessageRole.ASSISTANT && events.isNotEmpty()) {
            events.forEach { event ->
                traceAssembler.onStructuredEvent(event)
            }
        }
        if (text.isNotEmpty()) {
            contentBuilder.append(text)
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

    /**
     * 完成消息（流式结束后调用）
     */
    fun finishMessage() {
        messageFinished = true
        renderContent(structured = true)
        extractCodeBlocks()
        addActionButtons()
    }

    private fun renderContent(structured: Boolean = false) {
        val useStructured = structured || messageFinished
        val content = contentBuilder.toString()
        if (role == MessageRole.ASSISTANT) {
            val traceSnapshot = traceAssembler.snapshot(content)
            if (traceSnapshot.hasTrace) {
                renderAssistantTraceContent(content, traceSnapshot, useStructured)
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
            doc.remove(0, doc.length)
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, "Monospaced")
            StyleConstants.setFontSize(attrs, 13)
            doc.insertString(0, content, attrs)
        }
        revalidate()
        repaint()
    }

    private fun renderAssistantTraceContent(
        content: String,
        traceSnapshot: StreamingTraceAssembler.TraceSnapshot,
        structured: Boolean,
    ) {
        val answerContent = extractAssistantAnswerContent(content)
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
            container.add(createMarkdownPane(parseResult.remainingText))
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
                container.add(createMarkdownPane(section.content))
            }
        }
        return container
    }

    private fun createTracePanel(items: List<StreamingTraceAssembler.TraceItem>): JPanel {
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = JBColor(
            java.awt.Color(249, 251, 255),
            java.awt.Color(40, 45, 52),
        )
        wrapper.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 10),
        )

        val summaryBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        summaryBar.isOpaque = false
        val summaryLabel = JBLabel(SpecCodingBundle.message("chat.timeline.summary.label"))
        summaryLabel.font = summaryLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        summaryBar.add(summaryLabel)

        val readCount = items.count { it.kind == ExecutionTimelineParser.Kind.READ }
        val editCount = items.count { it.kind == ExecutionTimelineParser.Kind.EDIT }
        val verifyCount = items.count { it.kind == ExecutionTimelineParser.Kind.VERIFY }
        val statsLabel = JBLabel(SpecCodingBundle.message("chat.timeline.summary.stats", readCount, editCount, verifyCount))
        statsLabel.foreground = JBColor.GRAY
        statsLabel.font = statsLabel.font.deriveFont(11f)
        summaryBar.add(statsLabel)

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
        summaryBar.add(toggleButton)

        wrapper.add(summaryBar, BorderLayout.NORTH)

        if (traceExpanded) {
            val listPanel = JPanel()
            listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
            listPanel.isOpaque = false
            listPanel.border = JBUI.Borders.emptyTop(6)

            items.forEach { item ->
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
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = JBColor(
            java.awt.Color(247, 249, 252),
            java.awt.Color(38, 42, 48),
        )
        wrapper.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 10),
        )

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        header.isOpaque = false

        val title = JBLabel(SpecCodingBundle.message("chat.timeline.kind.output"))
        title.font = title.font.deriveFont(java.awt.Font.BOLD, 12f)
        header.add(title)

        val countLabel = JBLabel(items.size.toString())
        countLabel.foreground = JBColor.GRAY
        countLabel.font = countLabel.font.deriveFont(11f)
        header.add(countLabel)

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
        header.add(toggleButton)

        wrapper.add(header, BorderLayout.NORTH)

        if (outputExpanded) {
            val list = JPanel()
            list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
            list.isOpaque = false
            list.border = JBUI.Borders.emptyTop(6)
            items.forEach { item ->
                list.add(createTraceDetailBlock(item, forceVerbose = true))
            }
            wrapper.add(list, BorderLayout.CENTER)
        } else {
            val first = items.firstOrNull()
            if (first != null) {
                val previewHost = JPanel(BorderLayout())
                previewHost.isOpaque = false
                previewHost.border = JBUI.Borders.emptyTop(6)
                previewHost.add(createTraceDetailBlock(first, forceVerbose = true), BorderLayout.CENTER)
                wrapper.add(previewHost, BorderLayout.CENTER)
            }
        }

        val container = JPanel(BorderLayout())
        container.isOpaque = false
        container.border = JBUI.Borders.emptyBottom(6)
        container.add(wrapper, BorderLayout.CENTER)
        return container
    }

    private fun createTraceItemRow(item: StreamingTraceAssembler.TraceItem): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = JBColor(
            java.awt.Color(244, 248, 254),
            java.awt.Color(47, 54, 62),
        )
        row.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6, 8),
        )

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        header.isOpaque = false

        val dot = JBLabel("●")
        dot.foreground = statusColor(item.status)
        dot.font = dot.font.deriveFont(10f)
        header.add(dot)

        val kindLabel = JBLabel(
            "${kindLabel(item.kind)} · ${statusLabel(item.status)}"
        )
        kindLabel.font = kindLabel.font.deriveFont(java.awt.Font.BOLD, 11f)
        kindLabel.foreground = JBColor(
            java.awt.Color(40, 52, 73),
            java.awt.Color(196, 210, 231),
        )
        header.add(kindLabel)

        if (item.fileAction != null && onWorkflowFileOpen != null) {
            val openBtn = JButton(SpecCodingBundle.message("chat.workflow.action.openFile.short"))
            styleInlineActionButton(openBtn)
            openBtn.toolTipText = SpecCodingBundle.message("chat.workflow.action.openFile.tooltip", item.fileAction.displayPath)
            openBtn.addActionListener {
                onWorkflowFileOpen.invoke(item.fileAction)
            }
            header.add(openBtn)
        }

        row.add(header, BorderLayout.NORTH)
        row.add(createTraceDetailBlock(item), BorderLayout.CENTER)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(5)
            add(row, BorderLayout.CENTER)
        }
    }

    private fun createTraceDetailBlock(item: StreamingTraceAssembler.TraceItem, forceVerbose: Boolean = false): JPanel {
        val block = JPanel(BorderLayout())
        block.isOpaque = false
        block.border = JBUI.Borders.empty(2, 10, 2, 0)

        val key = entryKey(item)
        val verbose = forceVerbose || item.isVerbose
        val previewLength = if (forceVerbose) TRACE_OUTPUT_PREVIEW_LENGTH else TRACE_DETAIL_PREVIEW_LENGTH
        val hasOverflow = item.detail.length > previewLength
        val collapsed = verbose && key !in expandedVerboseEntries
        val visibleText = if (collapsed) {
            toPreview(item.detail, previewLength)
        } else if (!verbose && hasOverflow) {
            toPreview(item.detail, previewLength)
        } else {
            item.detail
        }

        val detailPane = JTextPane()
        detailPane.isEditable = false
        detailPane.isOpaque = false
        detailPane.isFocusable = false
        detailPane.border = JBUI.Borders.empty()
        val doc = detailPane.styledDocument
        doc.remove(0, doc.length)
        val attrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(attrs, "Monospaced")
        StyleConstants.setFontSize(attrs, 12)
        doc.insertString(0, visibleText, attrs)
        block.add(detailPane, BorderLayout.CENTER)

        if (verbose) {
            val toggleText = if (collapsed) {
                SpecCodingBundle.message("chat.timeline.toggle.expand")
            } else {
                SpecCodingBundle.message("chat.timeline.toggle.collapse")
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
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.add(createMarkdownPane(content), BorderLayout.CENTER)
        return panel
    }

    private fun createMarkdownPane(content: String): JTextPane {
        val pane = JTextPane()
        pane.isEditable = false
        pane.isOpaque = false
        pane.border = JBUI.Borders.emptyTop(2)
        pane.isFocusable = false
        MarkdownRenderer.render(pane, content)
        return pane
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

    private fun entryKey(item: StreamingTraceAssembler.TraceItem): String {
        return "${item.kind.name}:${item.detail.lowercase()}"
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

        // 代码块复制按钮
        codeBlocks.forEachIndexed { index, code ->
            val copyBtn = JButton(SpecCodingBundle.message("chat.message.copy.index", index + 1))
            styleActionButton(copyBtn)
            copyBtn.addActionListener {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(code), null)
            }
            buttonPanel.add(copyBtn)
        }

        // 复制全文按钮
        val copyAllBtn = JButton()
        styleIconActionButton(
            button = copyAllBtn,
            icon = AllIcons.Actions.Copy,
            tooltip = SpecCodingBundle.message("chat.message.copy.all"),
        )
        copyAllBtn.addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(contentBuilder.toString()), null)
        }
        buttonPanel.add(copyAllBtn)

        if (role == MessageRole.ASSISTANT) {
            addWorkflowQuickActionButtons(buttonPanel)
        }

        if (role == MessageRole.ASSISTANT && onContinue != null) {
            val continueBtn = JButton()
            styleIconActionButton(
                button = continueBtn,
                icon = AllIcons.Actions.Execute,
                tooltip = SpecCodingBundle.message("chat.message.continue"),
            )
            continueBtn.addActionListener { onContinue.invoke(this) }
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

    private fun addWorkflowQuickActionButtons(panel: JPanel) {
        val quickActions = WorkflowQuickActionParser.parse(contentBuilder.toString())
        if (quickActions.files.isEmpty() && quickActions.commands.isEmpty()) return

        if (quickActions.files.isNotEmpty()) {
            panel.add(createActionGroupLabel(SpecCodingBundle.message("chat.workflow.action.filesLabel")))
            quickActions.files.take(MAX_FILE_ACTIONS).forEach { fileAction ->
                val btn = JButton(SpecCodingBundle.message("chat.workflow.action.openFile.short"))
                styleActionButton(btn)
                btn.toolTipText = SpecCodingBundle.message("chat.workflow.action.openFile.tooltip", fileAction.displayPath)
                btn.addActionListener {
                    onWorkflowFileOpen?.invoke(fileAction)
                }
                panel.add(btn)
            }
        }

        if (quickActions.commands.isNotEmpty()) {
            panel.add(createActionGroupLabel(SpecCodingBundle.message("chat.workflow.action.commandsLabel")))
            quickActions.commands.take(MAX_COMMAND_ACTIONS).forEach { command ->
                val display = if (command.length > MAX_COMMAND_DISPLAY_LENGTH) {
                    "${command.take(MAX_COMMAND_DISPLAY_LENGTH - 3)}..."
                } else {
                    command
                }
                val btn = JButton(SpecCodingBundle.message("chat.workflow.action.insertCommand", display))
                styleActionButton(btn)
                btn.toolTipText = SpecCodingBundle.message("chat.workflow.action.insertCommand.tooltip", command)
                btn.addActionListener {
                    if (onWorkflowCommandInsert != null) {
                        onWorkflowCommandInsert.invoke(command)
                    } else {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(command), null)
                    }
                }
                panel.add(btn)
            }
        }
    }

    private fun createActionGroupLabel(text: String): JLabel {
        return JLabel("$text:").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(java.awt.Font.PLAIN, 10.5f)
        }
    }

    private fun styleActionButton(button: JButton) {
        button.margin = JBUI.insets(3, 8, 3, 8)
        button.isFocusPainted = false
        button.isFocusable = false
        button.font = button.font.deriveFont(11f)
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.background = JBColor(
            java.awt.Color(243, 245, 248),
            java.awt.Color(58, 63, 69),
        )
        button.border = JBUI.Borders.customLine(JBColor.border(), 1)
        button.putClientProperty("JButton.buttonType", "roundRect")
    }

    private fun styleInlineActionButton(button: JButton) {
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isFocusPainted = false
        button.isFocusable = false
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(0, 2)
        button.font = button.font.deriveFont(11f)
        button.foreground = JBColor(
            java.awt.Color(57, 95, 151),
            java.awt.Color(141, 190, 255),
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
        val compact = text
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (compact.length <= limit) {
            return compact
        }
        return compact.take(limit).trimEnd() + "..."
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
            java.awt.Color(242, 247, 255),
            java.awt.Color(37, 44, 55)
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(244, 250, 244),
            java.awt.Color(35, 47, 39)
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

    companion object {
        private const val MAX_FILE_ACTIONS = 4
        private const val MAX_COMMAND_ACTIONS = 4
        private const val MAX_COMMAND_DISPLAY_LENGTH = 26
        private const val TRACE_DETAIL_PREVIEW_LENGTH = 220
        private const val TRACE_OUTPUT_PREVIEW_LENGTH = 140
        private val WORKFLOW_HEADING_TITLES = setOf(
            "plan", "planning", "计划", "规划",
            "execute", "execution", "implement", "执行", "实施",
            "verify", "verification", "test", "验证", "测试",
        )
    }
}
