package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.ScrollPaneConstants

class ChatMessagesListPanelTest {

    @Test
    fun `addMessage should keep only latest messages within window`() {
        val panel = ChatMessagesListPanel(maxVisibleMessages = 3)

        repeat(5) { idx ->
            val message = ChatMessagePanel(
                role = ChatMessagePanel.MessageRole.USER,
                initialContent = "msg-$idx",
            )
            message.finishMessage()
            panel.addMessage(message)
        }

        assertEquals(3, panel.messageCount())
        assertEquals(
            listOf("msg-2", "msg-3", "msg-4"),
            panel.getAllMessages().map { it.getContent() },
        )
    }

    @Test
    fun `message list should track viewport width and disable horizontal scrollbar`() {
        val panel = ChatMessagesListPanel(maxVisibleMessages = 3)

        assertTrue(panel.getScrollableTracksViewportWidth())
        assertEquals(
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            panel.getScrollPane().horizontalScrollBarPolicy,
        )
    }
}
