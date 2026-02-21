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
            onContinueSession = {},
            onExportSession = { _, _ -> },
            onDeleteSession = {},
            onBranchSession = {},
            onCompareSession = {},
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
        assertTrue(selectedState["continueEnabled"] == true)
        assertTrue(selectedState["branchEnabled"] == true)
        assertTrue(selectedState["compareEnabled"] == true)
        assertTrue(selectedState["exportEnabled"] == true)
        assertTrue(selectedState["deleteEnabled"] == true)

        panel.setSelectedSession(null)
        val noSelectionState = panel.buttonStatesForTest()
        assertFalse(noSelectionState["openEnabled"] == true)
        assertFalse(noSelectionState["continueEnabled"] == true)
        assertFalse(noSelectionState["branchEnabled"] == true)
        assertFalse(noSelectionState["compareEnabled"] == true)
        assertFalse(noSelectionState["exportEnabled"] == true)
        assertFalse(noSelectionState["deleteEnabled"] == true)
    }

    @Test
    fun `actions should trigger callbacks with selected id`() {
        val opened = mutableListOf<String>()
        val continued = mutableListOf<String>()
        val branched = mutableListOf<String>()
        val compared = mutableListOf<String>()
        val exported = mutableListOf<Pair<String, SessionExportFormat>>()
        val deleted = mutableListOf<String>()

        val panel = HistorySessionListPanel(
            onSessionSelected = {},
            onOpenSession = { opened += it },
            onContinueSession = { continued += it },
            onExportSession = { id, format -> exported += id to format },
            onDeleteSession = { deleted += it },
            onBranchSession = { branched += it },
            onCompareSession = { compared += it },
        )

        panel.updateSessions(listOf(summary(id = "session-a", title = "A", msgCount = 3)))
        panel.setSelectedSession("session-a")

        panel.clickOpenForTest()
        panel.clickContinueForTest()
        panel.clickBranchForTest()
        panel.clickCompareForTest()
        panel.clickExportForTest()
        panel.clickDeleteForTest()

        assertEquals(listOf("session-a"), opened)
        assertEquals(listOf("session-a"), continued)
        assertEquals(listOf("session-a"), branched)
        assertEquals(listOf("session-a"), compared)
        assertEquals(listOf("session-a" to SessionExportFormat.MARKDOWN), exported)
        assertEquals(listOf("session-a"), deleted)
    }

    @Test
    fun `selection info should follow selected session`() {
        val panel = HistorySessionListPanel(
            onSessionSelected = {},
            onOpenSession = {},
            onContinueSession = {},
            onExportSession = { _, _ -> },
            onDeleteSession = {},
            onBranchSession = {},
            onCompareSession = {},
        )

        panel.updateSessions(
            listOf(
                summary(id = "s1", title = "Session 1", msgCount = 1),
                summary(id = "s2", title = "Session 2", msgCount = 2),
            )
        )

        val initialInfo = panel.selectedInfoTextForTest()
        assertFalse(initialInfo.contains("s1"))
        assertFalse(initialInfo.contains("Session 1"))

        panel.setSelectedSession("s1")
        val selectedInfo = panel.selectedInfoTextForTest()
        assertTrue(selectedInfo.contains("s1"))
        assertTrue(selectedInfo.contains("Session 1"))

        panel.setSelectedSession(null)
        val clearedInfo = panel.selectedInfoTextForTest()
        assertFalse(clearedInfo.contains("s1"))
        assertFalse(clearedInfo.contains("Session 1"))
    }

    private fun summary(id: String, title: String, msgCount: Int): SessionSummary {
        return SessionSummary(
            id = id,
            title = title,
            specTaskId = null,
            worktreeId = null,
            modelProvider = null,
            parentSessionId = null,
            branchName = null,
            messageCount = msgCount,
            updatedAt = 1L,
        )
    }
}
