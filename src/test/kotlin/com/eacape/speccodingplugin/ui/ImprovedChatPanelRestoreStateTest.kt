package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.window.WindowRuntimeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImprovedChatPanelRestoreStateTest {

    @Test
    fun `startup should fall back to compact composer height for suspiciously low stored proportion`() {
        val snapshot = WindowRuntimeState(
            selectedTabTitle = "Chat",
            activeSessionId = null,
            operationMode = null,
            chatInteractionMode = "workflow",
            chatSpecSidebarVisible = false,
            chatSpecSidebarDividerLocation = 0,
            chatComposerDividerProportion = 0.5277778f,
            updatedAt = 0L,
        )

        assertEquals(
            0.80f,
            ImprovedChatPanel.resolveInitialComposerDividerProportion(snapshot),
        )
    }

    @Test
    fun `startup should keep a valid user sized composer proportion`() {
        val snapshot = WindowRuntimeState(
            selectedTabTitle = "Chat",
            activeSessionId = null,
            operationMode = null,
            chatInteractionMode = "workflow",
            chatSpecSidebarVisible = false,
            chatSpecSidebarDividerLocation = 0,
            chatComposerDividerProportion = 0.64f,
            updatedAt = 0L,
        )

        assertEquals(
            0.64f,
            ImprovedChatPanel.resolveInitialComposerDividerProportion(snapshot),
        )
    }
}
