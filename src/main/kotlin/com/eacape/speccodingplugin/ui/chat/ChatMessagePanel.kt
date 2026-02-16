package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
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
    private val codeBlocks = mutableListOf<String>()
    private var buttonPanel: JPanel? = null

    enum class MessageRole {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)

        // 角色标签
        val roleLabel = createRoleLabel()

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
        wrapper.add(roleLabel, BorderLayout.NORTH)
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
        contentBuilder.append(text)
        renderContent()
    }

    /**
     * 获取消息的原始文本内容
     */
    fun getContent(): String = contentBuilder.toString()

    /**
     * 完成消息（流式结束后调用）
     */
    fun finishMessage() {
        renderContent(structured = true)
        extractCodeBlocks()
        addActionButtons()
    }

    private fun renderContent(structured: Boolean = false) {
        val content = contentBuilder.toString()
        if (role == MessageRole.ASSISTANT) {
            if (structured) {
                renderAssistantStructuredContent(content)
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

    private fun renderAssistantStructuredContent(content: String) {
        val parseResult = WorkflowSectionParser.parse(content)
        if (parseResult.sections.isEmpty()) {
            contentHost.removeAll()
            contentHost.add(contentPane, BorderLayout.CENTER)
            MarkdownRenderer.render(contentPane, content)
            return
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
            val titleLabel = JBLabel(title)
            titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
            titleLabel.border = JBUI.Borders.emptyTop(6)
            container.add(titleLabel)
            container.add(createMarkdownPane(section.content))
        }

        contentHost.removeAll()
        contentHost.add(container, BorderLayout.CENTER)
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
        val copyAllBtn = JButton(SpecCodingBundle.message("chat.message.copy.all"))
        styleActionButton(copyAllBtn)
        copyAllBtn.addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(contentBuilder.toString()), null)
        }
        buttonPanel.add(copyAllBtn)

        if (role == MessageRole.ASSISTANT) {
            addWorkflowQuickActionButtons(buttonPanel)
        }

        if (role == MessageRole.ASSISTANT && onContinue != null) {
            val continueBtn = JButton(SpecCodingBundle.message("chat.message.continue"))
            styleActionButton(continueBtn)
            continueBtn.addActionListener { onContinue.invoke(this) }
            buttonPanel.add(continueBtn)
        }

        // 重新生成按钮（仅 Assistant 消息）
        if (role == MessageRole.ASSISTANT && onRegenerate != null) {
            val regenBtn = JButton(SpecCodingBundle.message("chat.message.regenerate"))
            styleActionButton(regenBtn)
            regenBtn.addActionListener { onRegenerate.invoke(this) }
            buttonPanel.add(regenBtn)
        }

        // 删除按钮（User 和 Assistant 消息）
        if ((role == MessageRole.USER || role == MessageRole.ASSISTANT) && onDelete != null) {
            val deleteBtn = JButton(SpecCodingBundle.message("chat.message.delete"))
            styleActionButton(deleteBtn)
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

    private fun createRoleLabel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyBottom(6)

        val label = javax.swing.JLabel(getRoleText())
        label.font = label.font.deriveFont(
            java.awt.Font.BOLD, 11f
        )
        label.foreground = getRoleColor()
        label.isOpaque = true
        label.background = getRoleBadgeBackground()
        label.border = JBUI.Borders.empty(3, 8)
        panel.add(label)

        return panel
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

    private fun getRoleText(): String = when (role) {
        MessageRole.USER -> SpecCodingBundle.message("toolwindow.message.user")
        MessageRole.ASSISTANT -> SpecCodingBundle.message("toolwindow.message.assistant")
        MessageRole.SYSTEM -> SpecCodingBundle.message("toolwindow.message.system")
        MessageRole.ERROR -> SpecCodingBundle.message("toolwindow.message.error")
    }

    private fun getRoleColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(0, 94, 184),
            java.awt.Color(98, 173, 255)
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(18, 112, 52),
            java.awt.Color(107, 206, 147)
        )
        MessageRole.SYSTEM -> JBColor.GRAY
        MessageRole.ERROR -> JBColor.RED
    }

    private fun getRoleBadgeBackground(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(222, 236, 255),
            java.awt.Color(43, 63, 84),
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(223, 245, 228),
            java.awt.Color(42, 71, 52),
        )
        MessageRole.SYSTEM -> JBColor(
            java.awt.Color(235, 235, 235),
            java.awt.Color(62, 62, 62),
        )
        MessageRole.ERROR -> JBColor(
            java.awt.Color(255, 228, 228),
            java.awt.Color(94, 52, 52),
        )
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
    }
}
