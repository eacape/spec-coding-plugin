package com.eacape.speccodingplugin.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

@Service(Service.Level.PROJECT)
class SessionManager internal constructor(
    private val project: Project,
    private val connectionProvider: (String) -> Connection,
    private val idGenerator: () -> String,
    private val clock: () -> Long,
) {
    private val logger = thisLogger()
    private val lock = Any()

    @Volatile
    private var initialized = false

    constructor(project: Project) : this(
        project = project,
        connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
        idGenerator = { UUID.randomUUID().toString() },
        clock = { System.currentTimeMillis() },
    )

    fun createSession(
        title: String,
        specTaskId: String? = null,
        worktreeId: String? = null,
        modelProvider: String? = null,
    ): Result<ConversationSession> {
        return runCatching {
            val normalizedTitle = title.trim()
            require(normalizedTitle.isNotBlank()) { "Session title cannot be blank" }

            val now = clock()
            val session = ConversationSession(
                id = idGenerator(),
                title = normalizedTitle,
                specTaskId = specTaskId?.trim()?.ifBlank { null },
                worktreeId = worktreeId?.trim()?.ifBlank { null },
                modelProvider = modelProvider?.trim()?.ifBlank { null },
                createdAt = now,
                updatedAt = now,
            )

            withConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO sessions(id, title, spec_task_id, worktree_id, model_provider, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, session.id)
                    statement.setString(2, session.title)
                    statement.setString(3, session.specTaskId)
                    statement.setString(4, session.worktreeId)
                    statement.setString(5, session.modelProvider)
                    statement.setLong(6, session.createdAt)
                    statement.setLong(7, session.updatedAt)
                    statement.executeUpdate()
                }
            }
            session
        }
    }

    fun renameSession(sessionId: String, newTitle: String): Result<ConversationSession> {
        return runCatching {
            val normalizedSessionId = sessionId.trim()
            val normalizedTitle = newTitle.trim()
            require(normalizedSessionId.isNotBlank()) { "Session id cannot be blank" }
            require(normalizedTitle.isNotBlank()) { "Session title cannot be blank" }

            val now = clock()
            val updatedCount = withConnection { connection ->
                connection.prepareStatement(
                    "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?"
                ).use { statement ->
                    statement.setString(1, normalizedTitle)
                    statement.setLong(2, now)
                    statement.setString(3, normalizedSessionId)
                    statement.executeUpdate()
                }
            }
            require(updatedCount > 0) { "Session not found: $normalizedSessionId" }

            getSession(normalizedSessionId)
                ?: throw IllegalStateException("Failed to load updated session: $normalizedSessionId")
        }
    }

    fun deleteSession(sessionId: String): Result<Unit> {
        return runCatching {
            val normalizedSessionId = sessionId.trim()
            require(normalizedSessionId.isNotBlank()) { "Session id cannot be blank" }

            withConnection { connection ->
                connection.prepareStatement("DELETE FROM sessions WHERE id = ?").use { statement ->
                    statement.setString(1, normalizedSessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun getSession(sessionId: String): ConversationSession? {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) {
            return null
        }

        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, title, spec_task_id, worktree_id, model_provider, created_at, updated_at
                FROM sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, normalizedSessionId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        resultSet.toSession()
                    }
                }
            }
        }
    }

    fun listSessions(limit: Int = 100): List<ConversationSession> {
        val normalizedLimit = limit.coerceAtLeast(1)
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, title, spec_task_id, worktree_id, model_provider, created_at, updated_at
                FROM sessions
                ORDER BY updated_at DESC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, normalizedLimit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toSession())
                        }
                    }
                }
            }
        }
    }

    fun addMessage(
        sessionId: String,
        role: ConversationRole,
        content: String,
        tokenCount: Int? = null,
        metadataJson: String? = null,
    ): Result<ConversationMessage> {
        return runCatching {
            val normalizedSessionId = sessionId.trim()
            val normalizedContent = content.trim()
            require(normalizedSessionId.isNotBlank()) { "Session id cannot be blank" }
            require(normalizedContent.isNotBlank()) { "Message content cannot be blank" }

            val now = clock()
            val message = ConversationMessage(
                id = idGenerator(),
                sessionId = normalizedSessionId,
                role = role,
                content = normalizedContent,
                tokenCount = tokenCount,
                createdAt = now,
                metadataJson = metadataJson,
            )

            withConnection { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "UPDATE sessions SET updated_at = ? WHERE id = ?"
                    ).use { statement ->
                        statement.setLong(1, now)
                        statement.setString(2, message.sessionId)
                        val updatedRows = statement.executeUpdate()
                        require(updatedRows > 0) { "Session not found: ${message.sessionId}" }
                    }

                    connection.prepareStatement(
                        """
                        INSERT INTO messages(id, session_id, role, content, token_count, metadata_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, message.id)
                        statement.setString(2, message.sessionId)
                        statement.setString(3, message.role.name)
                        statement.setString(4, message.content)
                        if (message.tokenCount == null) {
                            statement.setNull(5, java.sql.Types.INTEGER)
                        } else {
                            statement.setInt(5, message.tokenCount)
                        }
                        statement.setString(6, message.metadataJson)
                        statement.setLong(7, message.createdAt)
                        statement.executeUpdate()
                    }

                    connection.commit()
                } catch (error: Exception) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }

            message
        }
    }

    fun listMessages(sessionId: String, limit: Int = 1000): List<ConversationMessage> {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) {
            return emptyList()
        }

        val normalizedLimit = limit.coerceAtLeast(1)
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, role, content, token_count, metadata_json, created_at
                FROM messages
                WHERE session_id = ?
                ORDER BY created_at ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, normalizedSessionId)
                statement.setInt(2, normalizedLimit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toMessage())
                        }
                    }
                }
            }
        }
    }

    fun searchSessions(
        query: String? = null,
        filter: SessionFilter = SessionFilter.ALL,
        limit: Int = 100,
    ): List<SessionSummary> {
        val normalizedQuery = query?.trim().orEmpty()
        val normalizedLimit = limit.coerceAtLeast(1)
        val hasQuery = normalizedQuery.isNotBlank()

        return withConnection { connection ->
            val baseSql = StringBuilder(
                """
                SELECT
                    s.id,
                    s.title,
                    s.spec_task_id,
                    s.worktree_id,
                    s.model_provider,
                    s.updated_at,
                    COUNT(m.id) AS message_count
                FROM sessions s
                LEFT JOIN messages m ON m.session_id = s.id
                """.trimIndent()
            )

            val conditions = mutableListOf<String>()
            when (filter) {
                SessionFilter.ALL -> Unit
                SessionFilter.SPEC_BOUND -> conditions += "s.spec_task_id IS NOT NULL AND TRIM(s.spec_task_id) <> ''"
                SessionFilter.WORKTREE_BOUND -> conditions += "s.worktree_id IS NOT NULL AND TRIM(s.worktree_id) <> ''"
            }

            if (hasQuery) {
                conditions += "(s.title LIKE ? OR s.id LIKE ? OR s.spec_task_id LIKE ? OR s.worktree_id LIKE ?)"
            }

            if (conditions.isNotEmpty()) {
                baseSql.append(" WHERE ").append(conditions.joinToString(" AND "))
            }

            baseSql.append(" GROUP BY s.id")
            baseSql.append(" ORDER BY s.updated_at DESC")
            baseSql.append(" LIMIT ?")

            connection.prepareStatement(baseSql.toString()).use { statement ->
                var index = 1
                if (hasQuery) {
                    val pattern = "%$normalizedQuery%"
                    repeat(4) {
                        statement.setString(index, pattern)
                        index += 1
                    }
                }
                statement.setInt(index, normalizedLimit)

                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toSummary())
                        }
                    }
                }
            }
        }
    }

    internal fun databasePathForTest(): Path? = databasePath()

    private fun ensureInitialized() {
        if (initialized) {
            return
        }

        synchronized(lock) {
            if (initialized) {
                return
            }
            val dbPath = requireNotNull(databasePath()) { "Project base path is not available" }
            Files.createDirectories(dbPath.parent)

            openConnection().use { connection ->
                runMigrations(connection)
            }

            initialized = true
            logger.info("Session database initialized at: $dbPath")
        }
    }

    private fun runMigrations(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations(
                    version INTEGER PRIMARY KEY,
                    applied_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        val currentVersion = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations").use { resultSet ->
                if (resultSet.next()) resultSet.getInt(1) else 0
            }
        }

        val pendingMigrations = migrations.filter { it.version > currentVersion }.sortedBy { it.version }
        if (pendingMigrations.isEmpty()) {
            return
        }

        connection.autoCommit = false
        try {
            pendingMigrations.forEach { migration ->
                migration.statements.forEach { sql ->
                    connection.createStatement().use { statement ->
                        statement.execute(sql)
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO schema_migrations(version, applied_at) VALUES(?, ?)"
                ).use { statement ->
                    statement.setInt(1, migration.version)
                    statement.setLong(2, clock())
                    statement.executeUpdate()
                }
            }
            connection.commit()
        } catch (error: Exception) {
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        ensureInitialized()

        openConnection().use { connection ->
            return block(connection)
        }
    }

    private fun openConnection(): Connection {
        ensureSqliteDriverLoaded()
        val jdbcUrl = requireNotNull(jdbcUrl()) { "Project base path is not available" }
        return connectionProvider(jdbcUrl).apply {
            createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
            }
        }
    }

    private fun ensureSqliteDriverLoaded() {
        runCatching {
            Class.forName("org.sqlite.JDBC", true, SessionManager::class.java.classLoader)
        }.getOrElse { error ->
            throw SQLException(
                "SQLite JDBC driver is not available in plugin runtime",
                error,
            )
        }
    }

    private fun jdbcUrl(): String? {
        val path = databasePath() ?: return null
        return "jdbc:sqlite:${path.toAbsolutePath()}"
    }

    private fun databasePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("data")
            .resolve("conversations.db")
    }

    private fun ResultSet.toSession(): ConversationSession {
        return ConversationSession(
            id = getString("id"),
            title = getString("title"),
            specTaskId = getString("spec_task_id"),
            worktreeId = getString("worktree_id"),
            modelProvider = getString("model_provider"),
            createdAt = getLong("created_at"),
            updatedAt = getLong("updated_at"),
        )
    }

    private fun ResultSet.toMessage(): ConversationMessage {
        val token = getInt("token_count")
        val tokenIsNull = wasNull()
        return ConversationMessage(
            id = getString("id"),
            sessionId = getString("session_id"),
            role = ConversationRole.entries.firstOrNull { it.name == getString("role") }
                ?: ConversationRole.USER,
            content = getString("content"),
            tokenCount = if (tokenIsNull) null else token,
            createdAt = getLong("created_at"),
            metadataJson = getString("metadata_json"),
        )
    }

    private fun ResultSet.toSummary(): SessionSummary {
        return SessionSummary(
            id = getString("id"),
            title = getString("title"),
            specTaskId = getString("spec_task_id"),
            worktreeId = getString("worktree_id"),
            modelProvider = getString("model_provider"),
            messageCount = getInt("message_count"),
            updatedAt = getLong("updated_at"),
        )
    }

    private data class SessionMigration(
        val version: Int,
        val statements: List<String>,
    )

    companion object {
        private val migrations = listOf(
            SessionMigration(
                version = 1,
                statements = listOf(
                    """
                    CREATE TABLE IF NOT EXISTS sessions(
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        spec_task_id TEXT,
                        worktree_id TEXT,
                        model_provider TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS messages(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        token_count INTEGER,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
                        session_id UNINDEXED,
                        title,
                        content,
                        tokenize = 'unicode61'
                    )
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_sessions_ai AFTER INSERT ON sessions BEGIN
                        INSERT INTO sessions_fts(session_id, title, content)
                        VALUES (new.id, new.title, '');
                    END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_sessions_au AFTER UPDATE OF title ON sessions BEGIN
                        UPDATE sessions_fts
                        SET title = new.title
                        WHERE session_id = new.id;
                    END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_sessions_ad AFTER DELETE ON sessions BEGIN
                        DELETE FROM sessions_fts
                        WHERE session_id = old.id;
                    END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_messages_ai AFTER INSERT ON messages BEGIN
                        UPDATE sessions_fts
                        SET content =
                            CASE
                                WHEN content = '' THEN new.content
                                ELSE content || char(10) || new.content
                            END
                        WHERE session_id = new.session_id;
                    END
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_sessions_updated_at ON sessions(updated_at DESC)",
                    "CREATE INDEX IF NOT EXISTS idx_messages_session_created_at ON messages(session_id, created_at)",
                ),
            ),
            SessionMigration(
                version = 2,
                statements = listOf(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
                        session_id UNINDEXED,
                        title,
                        content,
                        tokenize = 'unicode61'
                    )
                    """.trimIndent(),
                    """
                    INSERT INTO sessions_fts(session_id, title, content)
                    SELECT
                        s.id,
                        s.title,
                        COALESCE((
                            SELECT group_concat(m.content, char(10))
                            FROM messages m
                            WHERE m.session_id = s.id
                            ORDER BY m.created_at ASC
                        ), '')
                    FROM sessions s
                    WHERE NOT EXISTS (
                        SELECT 1 FROM sessions_fts f WHERE f.session_id = s.id
                    )
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_sessions_ai AFTER INSERT ON sessions BEGIN
                        INSERT INTO sessions_fts(session_id, title, content)
                        VALUES (new.id, new.title, '');
                    END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_sessions_au AFTER UPDATE OF title ON sessions BEGIN
                        UPDATE sessions_fts
                        SET title = new.title
                        WHERE session_id = new.id;
                    END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_sessions_ad AFTER DELETE ON sessions BEGIN
                        DELETE FROM sessions_fts
                        WHERE session_id = old.id;
                    END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_messages_ai AFTER INSERT ON messages BEGIN
                        UPDATE sessions_fts
                        SET content =
                            CASE
                                WHEN content = '' THEN new.content
                                ELSE content || char(10) || new.content
                            END
                        WHERE session_id = new.session_id;
                    END
                    """.trimIndent(),
                ),
            ),
        )

        fun getInstance(project: Project): SessionManager = project.service()
    }
}
