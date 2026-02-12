package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryDetailPanelTest {

    @Test
    fun `showMessages should render message list with role prefixes`() {
        val panel = HistoryDetailPanel()

        panel.showMessages(
            listOf(
                message(sessionId = "s-1", id = "m-1", role = ConversationRole.USER, content = "hello"),
                message(sessionId = "s-1", id = "m-2", role = ConversationRole.ASSISTANT, content = "world"),
            )
        )

        assertTrue(panel.displayedTextForTest().contains("[USER] hello"))
        assertTrue(panel.displayedTextForTest().contains("[ASSISTANT] world"))
        assertTrue(!panel.isShowingEmptyForTest())
    }

    @Test
    fun `showEmpty should switch panel to empty state`() {
        val panel = HistoryDetailPanel()
        panel.showMessages(listOf(message(sessionId = "s-2", id = "m-3", role = ConversationRole.USER, content = "payload")))
        assertTrue(!panel.isShowingEmptyForTest())

        panel.showEmpty()
        assertTrue(panel.isShowingEmptyForTest())
    }

    private fun message(
        sessionId: String,
        id: String,
        role: ConversationRole,
        content: String,
    ): ConversationMessage {
        return ConversationMessage(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            tokenCount = null,
            createdAt = 1L,
            metadataJson = null,
        )
    }
}
