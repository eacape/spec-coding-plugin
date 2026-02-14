package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JScrollPane
import javax.swing.SwingConstants

class HistoryDetailPanel : javax.swing.JPanel(BorderLayout()) {

    private val emptyLabel = JBLabel(SpecCodingBundle.message("history.empty.select"), SwingConstants.CENTER)
    private val messageArea = JBTextArea()

    init {
        border = JBUI.Borders.empty(8)
        showEmpty()
    }

    fun showMessages(messages: List<ConversationMessage>) {
        removeAll()
        val displayMessages = if (messages.size > MAX_RENDER_MESSAGES) {
            messages.takeLast(MAX_RENDER_MESSAGES)
        } else {
            messages
        }
        val truncationNote = if (displayMessages.size < messages.size) {
            SpecCodingBundle.message("history.detail.truncated", displayMessages.size, messages.size) + "\n\n"
        } else {
            ""
        }

        messageArea.isEditable = false
        messageArea.lineWrap = true
        messageArea.wrapStyleWord = true
        messageArea.border = JBUI.Borders.empty(4)
        messageArea.text = truncationNote + displayMessages.joinToString(separator = "\n\n") { message ->
            SpecCodingBundle.message(
                "history.detail.message.entry",
                roleText(message.role),
                message.content,
            )
        }

        add(JScrollPane(messageArea), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showText(content: String) {
        removeAll()

        messageArea.isEditable = false
        messageArea.lineWrap = true
        messageArea.wrapStyleWord = true
        messageArea.border = JBUI.Borders.empty(4)
        messageArea.text = content

        add(JScrollPane(messageArea), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    internal fun displayedTextForTest(): String = messageArea.text

    internal fun isShowingEmptyForTest(): Boolean = components.contains(emptyLabel)

    private fun roleText(role: ConversationRole): String {
        return when (role) {
            ConversationRole.USER -> SpecCodingBundle.message("history.detail.role.user")
            ConversationRole.ASSISTANT -> SpecCodingBundle.message("history.detail.role.assistant")
            ConversationRole.SYSTEM -> SpecCodingBundle.message("history.detail.role.system")
            ConversationRole.TOOL -> SpecCodingBundle.message("history.detail.role.tool")
        }
    }

    companion object {
        private const val MAX_RENDER_MESSAGES = 300
    }
}
