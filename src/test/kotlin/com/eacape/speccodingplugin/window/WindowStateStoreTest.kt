package com.eacape.speccodingplugin.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WindowStateStoreTest {

    @Test
    fun `update methods should persist runtime snapshot`() {
        val store = WindowStateStore()

        store.updateSelectedTabTitle("History")
        store.updateActiveSessionId("session-1")
        store.updateOperationMode("AGENT")
        store.updateChatSpecSidebar(visible = true, dividerLocation = 640)

        val snapshot = store.snapshot()
        assertEquals("History", snapshot.selectedTabTitle)
        assertEquals("session-1", snapshot.activeSessionId)
        assertEquals("AGENT", snapshot.operationMode)
        assertEquals(true, snapshot.chatSpecSidebarVisible)
        assertEquals(640, snapshot.chatSpecSidebarDividerLocation)
    }

    @Test
    fun `loadState should restore values and keep blank as null`() {
        val store = WindowStateStore()

        store.loadState(
            WindowStateStore.WindowState(
                selectedTabTitle = "Specs",
                activeSessionId = "",
                operationMode = "PLAN",
                chatSpecSidebarVisible = true,
                chatSpecSidebarDividerLocation = 420,
                updatedAt = 42L,
            )
        )

        // updateActiveSessionId 会做空白归一化
        store.updateActiveSessionId("   ")

        val snapshot = store.snapshot()
        assertEquals("Specs", snapshot.selectedTabTitle)
        assertNull(snapshot.activeSessionId)
        assertEquals("PLAN", snapshot.operationMode)
        assertEquals(true, snapshot.chatSpecSidebarVisible)
        assertEquals(420, snapshot.chatSpecSidebarDividerLocation)
    }
}
