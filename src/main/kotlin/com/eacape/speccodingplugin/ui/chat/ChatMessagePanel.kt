package com.eacape.speccodingplugin.ui.chat

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
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
) : JPanel(BorderLayout()) {

    private val contentPane = JTextPane()
    private val contentBuilder = StringBuilder()
    private val codeBlocks = mutableListOf<String>()

    enum class MessageRole {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(8)

        // 角色标签
        val roleLabel = createRoleLabel()

        // 内容区域
        contentPane.isEditable = false
        contentPane.isOpaque = true
        contentPane.border = JBUI.Borders.empty(8)
        contentPane.background = getBackgroundColor()

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(roleLabel, BorderLayout.NORTH)
        wrapper.add(contentPane, BorderLayout.CENTER)

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
        renderContent()
        extractCodeBlocks()
        addActionButtons()
    }

    private fun renderContent() {
        val content = contentBuilder.toString()
        if (role == MessageRole.ASSISTANT) {
            MarkdownRenderer.render(contentPane, content)
        } else {
            val doc = contentPane.styledDocument
            doc.remove(0, doc.length)
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, "Monospaced")
            StyleConstants.setFontSize(attrs, 13)
            doc.insertString(0, content, attrs)
        }
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

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        buttonPanel.isOpaque = false
        buttonPanel.border = JBUI.Borders.emptyTop(4)

        // 代码块复制按钮
        codeBlocks.forEachIndexed { index, code ->
            val copyBtn = JButton("Copy #${index + 1}")
            copyBtn.addActionListener {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(code), null)
            }
            buttonPanel.add(copyBtn)
        }

        // 复制全文按钮
        val copyAllBtn = JButton("Copy All")
        copyAllBtn.addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(contentBuilder.toString()), null)
        }
        buttonPanel.add(copyAllBtn)

        // 重新生成按钮（仅 Assistant 消息）
        if (role == MessageRole.ASSISTANT && onRegenerate != null) {
            val regenBtn = JButton("Regenerate")
            regenBtn.addActionListener { onRegenerate.invoke(this) }
            buttonPanel.add(regenBtn)
        }

        // 删除按钮（User 和 Assistant 消息）
        if ((role == MessageRole.USER || role == MessageRole.ASSISTANT) && onDelete != null) {
            val deleteBtn = JButton("Delete")
            deleteBtn.addActionListener { onDelete.invoke(this) }
            buttonPanel.add(deleteBtn)
        }

        add(buttonPanel, BorderLayout.SOUTH)
        revalidate()
    }

    private fun createRoleLabel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyBottom(4)

        val label = javax.swing.JLabel(getRoleText())
        label.font = label.font.deriveFont(
            java.awt.Font.BOLD, 12f
        )
        label.foreground = getRoleColor()
        panel.add(label)

        return panel
    }

    private fun getRoleText(): String = when (role) {
        MessageRole.USER -> "You"
        MessageRole.ASSISTANT -> "Assistant"
        MessageRole.SYSTEM -> "System"
        MessageRole.ERROR -> "Error"
    }

    private fun getRoleColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(0, 120, 215),
            java.awt.Color(78, 154, 241)
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(16, 124, 16),
            java.awt.Color(76, 175, 80)
        )
        MessageRole.SYSTEM -> JBColor.GRAY
        MessageRole.ERROR -> JBColor.RED
    }

    private fun getBackgroundColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(240, 245, 255),
            java.awt.Color(40, 44, 52)
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(245, 255, 245),
            java.awt.Color(35, 48, 40)
        )
        MessageRole.SYSTEM -> JBColor(
            java.awt.Color(248, 248, 248),
            java.awt.Color(45, 45, 45)
        )
        MessageRole.ERROR -> JBColor(
            java.awt.Color(255, 240, 240),
            java.awt.Color(60, 35, 35)
        )
    }
}
