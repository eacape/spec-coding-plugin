package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.SessionExportFormat
import com.eacape.speccodingplugin.session.SessionExportResult
import com.eacape.speccodingplugin.session.SessionFilter
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HistoryPanelTest {

    @Test
    fun `refreshSessions should apply query filter and update selection`() {
        val capturedQuery = mutableListOf<String>()
        val capturedFilter = mutableListOf<SessionFilter>()

        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { query, filter, _ ->
                capturedQuery += query
                capturedFilter += filter
                listOf(
                    summary(id = "s-1", title = "Fix history"),
                    summary(id = "s-2", title = "Worktree flow"),
                )
            },
            listMessages = { _, _ -> emptyList() },
            deleteSession = { Result.success(Unit) },
            getSession = { null },
            exportSession = { _, _, _, _ -> Result.failure(IllegalStateException("unused")) },
            runSynchronously = true,
        )

        panel.setSearchQueryForTest("history")
        panel.setFilterForTest(SessionFilter.SPEC_BOUND)
        panel.refreshSessions()

        assertTrue(capturedQuery.contains("history"))
        assertTrue(capturedFilter.contains(SessionFilter.SPEC_BOUND))
        assertEquals(2, panel.sessionsForTest().size)
        assertEquals("s-1", panel.selectedSessionIdForTest())
        assertTrue(panel.statusTextForTest().contains("2"))

        panel.dispose()
    }

    @Test
    fun `open action should publish message bus event and load details`() {
        val messageBus = mockk<MessageBus>(relaxed = true)
        val listener = mockk<HistorySessionOpenListener>(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<HistorySessionOpenListener>>()) } returns listener

        val project = mockk<Project>(relaxed = true)
        every { project.isDisposed } returns false
        every { project.messageBus } returns messageBus
        every { project.basePath } returns "D:/repo"

        val panel = HistoryPanel(
            project = project,
            searchSessions = { _, _, _ -> listOf(summary(id = "open-1", title = "Open me")) },
            listMessages = { sessionId, _ ->
                if (sessionId == "open-1") {
                    listOf(message(sessionId, ConversationRole.USER, "hello"))
                } else {
                    emptyList()
                }
            },
            deleteSession = { Result.success(Unit) },
            getSession = { null },
            exportSession = { _, _, _, _ -> Result.failure(IllegalStateException("unused")) },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("open-1")
        panel.clickOpenForTest()

        verify(exactly = 1) { listener.onSessionOpenRequested("open-1") }
        assertTrue(panel.statusTextForTest().isNotBlank())
        assertTrue(panel.detailTextForTest().contains("hello"))

        panel.dispose()
    }

    @Test
    fun `delete failure should keep session and show failure status`() {
        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { _, _, _ -> listOf(summary(id = "del-1", title = "Delete me")) },
            listMessages = { _, _ -> emptyList() },
            deleteSession = { Result.failure(IllegalStateException("db down")) },
            getSession = { null },
            exportSession = { _, _, _, _ -> Result.failure(IllegalStateException("unused")) },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("del-1")
        panel.clickDeleteForTest()

        assertEquals("del-1", panel.selectedSessionIdForTest())
        assertTrue(panel.statusTextForTest().contains("db down"))

        panel.dispose()
    }

    @Test
    fun `export action should use injected exporter and update status`() {
        val exported = mutableListOf<Pair<String, SessionExportFormat>>()

        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { _, _, _ -> listOf(summary(id = "exp-1", title = "Export me")) },
            listMessages = { sessionId, _ -> listOf(message(sessionId, ConversationRole.USER, "payload")) },
            deleteSession = { Result.success(Unit) },
            getSession = { sessionId ->
                ConversationSession(
                    id = sessionId,
                    title = "Export me",
                    specTaskId = null,
                    worktreeId = null,
                    modelProvider = "openai",
                    createdAt = 1L,
                    updatedAt = 2L,
                )
            },
            exportSession = { _, session, _, format ->
                exported += session.id to format
                Result.success(
                    SessionExportResult(
                        format = format,
                        filePath = Path.of("D:/repo/.spec-coding/exports/${session.id}.md"),
                        messageCount = 1,
                        bytesWritten = 12,
                    )
                )
            },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("exp-1")
        panel.clickExportForTest()

        assertEquals(listOf("exp-1" to SessionExportFormat.MARKDOWN), exported)
        assertTrue(panel.statusTextForTest().contains("exp-1"))

        panel.dispose()
    }

    private fun fakeProject(): Project {
        val messageBus = mockk<MessageBus>(relaxed = true)
        val listener = mockk<HistorySessionOpenListener>(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<HistorySessionOpenListener>>()) } returns listener

        return mockk(relaxed = true) {
            every { isDisposed } returns false
            every { basePath } returns "D:/repo"
            every { this@mockk.messageBus } returns messageBus
        }
    }

    private fun summary(id: String, title: String): SessionSummary {
        return SessionSummary(
            id = id,
            title = title,
            specTaskId = null,
            worktreeId = null,
            modelProvider = "openai",
            messageCount = 1,
            updatedAt = 1L,
        )
    }

    private fun message(sessionId: String, role: ConversationRole, content: String): ConversationMessage {
        return ConversationMessage(
            id = "m-$sessionId",
            sessionId = sessionId,
            role = role,
            content = content,
            tokenCount = null,
            createdAt = 1L,
            metadataJson = null,
        )
    }
}
