package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.session.SessionSummary
import com.eacape.speccodingplugin.session.SessionExportFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySessionListPanelTest {

    @Test
    fun `updateSessions should update list and selection`() {
        val panel = HistorySessionListPanel(
            onSessionSelected = {},
            onOpenSession = {},
            onExportSession = { _, _ -> },
            onDeleteSession = {},
        )

        panel.updateSessions(
            listOf(
                summary(id = "s1", title = "Session 1", msgCount = 1),
                summary(id = "s2", title = "Session 2", msgCount = 2),
            )
        )

        assertEquals(2, panel.sessionsForTest().size)

        panel.setSelectedSession("s2")
        assertEquals("s2", panel.selectedSessionIdForTest())

        val selectedState = panel.buttonStatesForTest()
        assertTrue(selectedState["openEnabled"] == true)
        assertTrue(selectedState["exportEnabled"] == true)
        assertTrue(selectedState["deleteEnabled"] == true)

        panel.setSelectedSession(null)
        val noSelectionState = panel.buttonStatesForTest()
        assertFalse(noSelectionState["openEnabled"] == true)
        assertFalse(noSelectionState["exportEnabled"] == true)
        assertFalse(noSelectionState["deleteEnabled"] == true)
    }

    @Test
    fun `actions should trigger callbacks with selected id`() {
        val opened = mutableListOf<String>()
        val exported = mutableListOf<Pair<String, SessionExportFormat>>()
        val deleted = mutableListOf<String>()

        val panel = HistorySessionListPanel(
            onSessionSelected = {},
            onOpenSession = { opened += it },
            onExportSession = { id, format -> exported += id to format },
            onDeleteSession = { deleted += it },
        )

        panel.updateSessions(listOf(summary(id = "session-a", title = "A", msgCount = 3)))
        panel.setSelectedSession("session-a")

        panel.clickOpenForTest()
        panel.clickExportForTest()
        panel.clickDeleteForTest()

        assertEquals(listOf("session-a"), opened)
        assertEquals(listOf("session-a" to SessionExportFormat.MARKDOWN), exported)
        assertEquals(listOf("session-a"), deleted)
    }

    private fun summary(id: String, title: String, msgCount: Int): SessionSummary {
        return SessionSummary(
            id = id,
            title = title,
            specTaskId = null,
            worktreeId = null,
            modelProvider = null,
            messageCount = msgCount,
            updatedAt = 1L,
        )
    }
}
