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

        val snapshot = store.snapshot()
        assertEquals("History", snapshot.selectedTabTitle)
        assertEquals("session-1", snapshot.activeSessionId)
        assertEquals("AGENT", snapshot.operationMode)
    }

    @Test
    fun `loadState should restore values and keep blank as null`() {
        val store = WindowStateStore()

        store.loadState(
            WindowStateStore.WindowState(
                selectedTabTitle = "Specs",
                activeSessionId = "",
                operationMode = "PLAN",
                updatedAt = 42L,
            )
        )

        // updateActiveSessionId 会做空白归一化
        store.updateActiveSessionId("   ")

        val snapshot = store.snapshot()
        assertEquals("Specs", snapshot.selectedTabTitle)
        assertNull(snapshot.activeSessionId)
        assertEquals("PLAN", snapshot.operationMode)
    }
}

