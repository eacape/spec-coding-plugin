package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.ConversationMessage
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

        messageArea.isEditable = false
        messageArea.lineWrap = true
        messageArea.wrapStyleWord = true
        messageArea.border = JBUI.Borders.empty(4)
        messageArea.text = messages.joinToString(separator = "\n\n") { message ->
            "[${message.role.name}] ${message.content}"
        }

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
}
