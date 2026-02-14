package com.eacape.speccodingplugin.ui.chat

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 消息列表面板
 * 管理多条 ChatMessagePanel 的容器
 */
class ChatMessagesListPanel(
    private val maxVisibleMessages: Int = DEFAULT_MAX_VISIBLE_MESSAGES,
) : JPanel() {

    private val messages = mutableListOf<ChatMessagePanel>()
    private val scrollPane: JBScrollPane

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)

        scrollPane = JBScrollPane(this)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.verticalScrollBar.unitIncrement = 16
    }

    fun getScrollPane(): JBScrollPane = scrollPane

    /**
     * 添加消息
     */
    fun addMessage(panel: ChatMessagePanel): ChatMessagePanel {
        messages.add(panel)
        add(panel)
        trimIfNeeded()
        revalidate()
        scrollToBottom()
        return panel
    }

    /**
     * 获取最后一条消息
     */
    fun getLastMessage(): ChatMessagePanel? = messages.lastOrNull()

    /**
     * 清空所有消息
     */
    fun clearAll() {
        messages.clear()
        removeAll()
        revalidate()
        repaint()
    }

    /**
     * 获取消息数量
     */
    fun messageCount(): Int = messages.size

    /**
     * 删除指定消息
     */
    fun removeMessage(panel: ChatMessagePanel) {
        messages.remove(panel)
        remove(panel)
        revalidate()
        repaint()
    }

    /**
     * 获取所有消息
     */
    fun getAllMessages(): List<ChatMessagePanel> = messages.toList()

    /**
     * 滚动到底部
     */
    fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun trimIfNeeded() {
        if (messages.size <= maxVisibleMessages) {
            return
        }
        val overflow = messages.size - maxVisibleMessages
        repeat(overflow) {
            val removed = messages.removeAt(0)
            remove(removed)
        }
    }

    companion object {
        private const val DEFAULT_MAX_VISIBLE_MESSAGES = 240
    }
}
