package com.eacape.speccodingplugin.session

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class SessionManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var manager: SessionManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.resolve("repo").toString()

        manager = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )
    }

    @Test
    fun `createSession should persist and list session`() {
        val created = manager.createSession(
            title = "Feature chat",
            specTaskId = "spec-1",
            worktreeId = "wt-1",
            modelProvider = "openai",
        ).getOrThrow()

        assertEquals("Feature chat", created.title)
        assertEquals("spec-1", created.specTaskId)
        assertEquals("wt-1", created.worktreeId)

        val loaded = manager.getSession(created.id)
        assertNotNull(loaded)
        assertEquals(created.id, loaded?.id)

        val listed = manager.listSessions()
        assertEquals(1, listed.size)
        assertEquals(created.id, listed.first().id)

        val dbPath = manager.databasePathForTest()
        assertNotNull(dbPath)
        assertTrue(dbPath!!.toFile().exists())
    }

    @Test
    fun `renameSession should update title and updatedAt`() {
        val created = manager.createSession(title = "Old title").getOrThrow()

        val renamed = manager.renameSession(created.id, "New title").getOrThrow()

        assertEquals("New title", renamed.title)
        assertTrue(renamed.updatedAt >= created.updatedAt)
    }

    @Test
    fun `addMessage should append message and bump session updatedAt`() {
        val session = manager.createSession(title = "Session A").getOrThrow()
        val beforeUpdatedAt = manager.getSession(session.id)!!.updatedAt

        val first = manager.addMessage(
            sessionId = session.id,
            role = ConversationRole.USER,
            content = "hello",
            tokenCount = 12,
            metadataJson = "{\"k\":\"v\"}",
        ).getOrThrow()
        val second = manager.addMessage(
            sessionId = session.id,
            role = ConversationRole.ASSISTANT,
            content = "world",
        ).getOrThrow()

        val messages = manager.listMessages(session.id)
        assertEquals(2, messages.size)
        assertEquals(first.id, messages[0].id)
        assertEquals(ConversationRole.USER, messages[0].role)
        assertEquals(12, messages[0].tokenCount)
        assertEquals(second.id, messages[1].id)
        assertEquals(ConversationRole.ASSISTANT, messages[1].role)
        assertNull(messages[1].tokenCount)

        val afterUpdatedAt = manager.getSession(session.id)!!.updatedAt
        assertTrue(afterUpdatedAt >= beforeUpdatedAt)
    }

    @Test
    fun `deleteSession should cascade delete messages`() {
        val session = manager.createSession(title = "Session to delete").getOrThrow()
        manager.addMessage(session.id, ConversationRole.USER, "msg").getOrThrow()

        manager.deleteSession(session.id).getOrThrow()

        assertNull(manager.getSession(session.id))
        assertTrue(manager.listMessages(session.id).isEmpty())
    }

    @Test
    fun `createSession should fail when title is blank`() {
        val result = manager.createSession("   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("title") == true)
    }

    @Test
    fun `addMessage should fail when session not found`() {
        val result = manager.addMessage(
            sessionId = "missing",
            role = ConversationRole.USER,
            content = "test",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Session not found") == true)
    }

    @Test
    fun `searchSessions should support filter and query`() {
        val general = manager.createSession(title = "General Chat").getOrThrow()
        val spec = manager.createSession(title = "Spec Session", specTaskId = "spec-123").getOrThrow()
        val worktree = manager.createSession(title = "Worktree Session", worktreeId = "wt-7").getOrThrow()

        manager.addMessage(general.id, ConversationRole.USER, "hello world").getOrThrow()
        manager.addMessage(spec.id, ConversationRole.USER, "design review").getOrThrow()
        manager.addMessage(worktree.id, ConversationRole.USER, "fix bug in auth").getOrThrow()

        val all = manager.searchSessions(limit = 20)
        assertEquals(3, all.size)

        val specOnly = manager.searchSessions(filter = SessionFilter.SPEC_BOUND, limit = 20)
        assertEquals(1, specOnly.size)
        assertEquals(spec.id, specOnly.first().id)

        val worktreeOnly = manager.searchSessions(filter = SessionFilter.WORKTREE_BOUND, limit = 20)
        assertEquals(1, worktreeOnly.size)
        assertEquals(worktree.id, worktreeOnly.first().id)

        val queryByTitle = manager.searchSessions(query = "General", limit = 20)
        assertEquals(1, queryByTitle.size)
        assertEquals(general.id, queryByTitle.first().id)

        val queryByBinding = manager.searchSessions(query = "spec-123", limit = 20)
        assertEquals(1, queryByBinding.size)
        assertEquals(spec.id, queryByBinding.first().id)
    }

    @Test
    fun `session data should persist across manager reinitialization`() {
        val managerBeforeRestart = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )

        val session = managerBeforeRestart.createSession(
            title = "Persisted Session",
            specTaskId = "spec-reopen",
            worktreeId = "wt-reopen",
            modelProvider = "openai",
        ).getOrThrow()
        managerBeforeRestart.addMessage(session.id, ConversationRole.USER, "hello after reopen").getOrThrow()
        managerBeforeRestart.addMessage(session.id, ConversationRole.ASSISTANT, "welcome back").getOrThrow()

        val managerAfterRestart = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )

        val loaded = managerAfterRestart.getSession(session.id)
        assertNotNull(loaded)
        assertEquals("Persisted Session", loaded?.title)

        val messages = managerAfterRestart.listMessages(session.id)
        assertEquals(2, messages.size)
        assertEquals(ConversationRole.USER, messages[0].role)
        assertEquals("hello after reopen", messages[0].content)
        assertEquals(ConversationRole.ASSISTANT, messages[1].role)
        assertEquals("welcome back", messages[1].content)

        val searched = managerAfterRestart.searchSessions(query = "Persisted", limit = 20)
        assertEquals(1, searched.size)
        assertEquals(session.id, searched.first().id)
    }
}
