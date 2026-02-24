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
        parentSessionId: String? = null,
        branchFromMessageId: String? = null,
        branchName: String? = null,
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
                parentSessionId = parentSessionId?.trim()?.ifBlank { null },
                branchFromMessageId = branchFromMessageId?.trim()?.ifBlank { null },
                branchName = branchName?.trim()?.ifBlank { null },
                createdAt = now,
                updatedAt = now,
            )

            withConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO sessions(
                        id, title, spec_task_id, worktree_id, model_provider,
                        parent_session_id, branch_from_message_id, branch_name,
                        created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, session.id)
                    statement.setString(2, session.title)
                    statement.setString(3, session.specTaskId)
                    statement.setString(4, session.worktreeId)
                    statement.setString(5, session.modelProvider)
                    statement.setString(6, session.parentSessionId)
                    statement.setString(7, session.branchFromMessageId)
                    statement.setString(8, session.branchName)
                    statement.setLong(9, session.createdAt)
                    statement.setLong(10, session.updatedAt)
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

    fun updateSessionSpecTaskId(sessionId: String, specTaskId: String?): Result<ConversationSession> {
        return runCatching {
            val normalizedSessionId = sessionId.trim()
            require(normalizedSessionId.isNotBlank()) { "Session id cannot be blank" }

            val normalizedSpecTaskId = specTaskId?.trim()?.ifBlank { null }
            val now = clock()

            val updatedCount = withConnection { connection ->
                connection.prepareStatement(
                    "UPDATE sessions SET spec_task_id = ?, updated_at = ? WHERE id = ?"
                ).use { statement ->
                    statement.setString(1, normalizedSpecTaskId)
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
                SELECT
                    id, title, spec_task_id, worktree_id, model_provider,
                    parent_session_id, branch_from_message_id, branch_name,
                    created_at, updated_at
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
                SELECT
                    id, title, spec_task_id, worktree_id, model_provider,
                    parent_session_id, branch_from_message_id, branch_name,
                    created_at, updated_at
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
                    s.parent_session_id,
                    s.branch_name,
                    s.updated_at,
                    COUNT(m.id) AS message_count
                FROM sessions s
                LEFT JOIN messages m ON m.session_id = s.id
                """.trimIndent()
            )

            val conditions = mutableListOf<String>()
            val specSessionCondition = """
                (
                    (s.spec_task_id IS NOT NULL AND TRIM(s.spec_task_id) <> '')
                    OR LOWER(TRIM(s.title)) LIKE '/spec%'
                    OR LOWER(TRIM(s.title)) LIKE '[spec]%'
                )
            """.trimIndent()
            when (filter) {
                SessionFilter.ALL -> Unit
                SessionFilter.SPEC -> conditions += specSessionCondition
                SessionFilter.VIBE -> conditions += "NOT $specSessionCondition"
            }

            if (hasQuery) {
                conditions += "(s.title LIKE ? OR s.id LIKE ? OR s.spec_task_id LIKE ? OR s.worktree_id LIKE ? OR s.branch_name LIKE ?)"
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
                    repeat(5) {
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

    fun listChildSessions(parentSessionId: String, limit: Int = 100): List<ConversationSession> {
        val normalizedParentId = parentSessionId.trim()
        if (normalizedParentId.isBlank()) {
            return emptyList()
        }

        val normalizedLimit = limit.coerceAtLeast(1)
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT
                    id, title, spec_task_id, worktree_id, model_provider,
                    parent_session_id, branch_from_message_id, branch_name,
                    created_at, updated_at
                FROM sessions
                WHERE parent_session_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, normalizedParentId)
                statement.setInt(2, normalizedLimit)
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

    fun forkSession(
        sourceSessionId: String,
        fromMessageId: String? = null,
        branchName: String? = null,
    ): Result<ConversationSession> {
        return runCatching {
            val normalizedSourceId = sourceSessionId.trim()
            require(normalizedSourceId.isNotBlank()) { "Source session id cannot be blank" }

            val source = getSession(normalizedSourceId)
                ?: throw IllegalArgumentException("Session not found: $normalizedSourceId")
            val sourceMessages = listMessages(normalizedSourceId, 5000)

            val normalizedMessageId = fromMessageId?.trim()?.ifBlank { null }
            val copyUntilExclusive = if (normalizedMessageId == null) {
                sourceMessages.size
            } else {
                val index = sourceMessages.indexOfFirst { message -> message.id == normalizedMessageId }
                require(index >= 0) { "Message not found in source session: $normalizedMessageId" }
                index + 1
            }

            val normalizedBranchName = branchName?.trim()?.ifBlank { null }
            val effectiveBranchName = normalizedBranchName ?: "branch-${clock()}"
            val targetMessageId = normalizedMessageId ?: sourceMessages.lastOrNull()?.id
            val branchTitle = "${source.title} [$effectiveBranchName]"

            val forked = createSession(
                title = branchTitle,
                specTaskId = source.specTaskId,
                worktreeId = source.worktreeId,
                modelProvider = source.modelProvider,
                parentSessionId = source.id,
                branchFromMessageId = targetMessageId,
                branchName = effectiveBranchName,
            ).getOrThrow()

            sourceMessages.take(copyUntilExclusive).forEach { message ->
                addMessage(
                    sessionId = forked.id,
                    role = message.role,
                    content = message.content,
                    tokenCount = message.tokenCount,
                    metadataJson = message.metadataJson,
                ).getOrThrow()
            }

            getSession(forked.id) ?: forked
        }
    }

    fun compareSessions(
        leftSessionId: String,
        rightSessionId: String,
        previewLength: Int = 120,
    ): Result<SessionBranchComparison> {
        return runCatching {
            val leftId = leftSessionId.trim()
            val rightId = rightSessionId.trim()
            require(leftId.isNotBlank()) { "Left session id cannot be blank" }
            require(rightId.isNotBlank()) { "Right session id cannot be blank" }
            require(previewLength > 0) { "previewLength must be positive" }

            val leftMessages = listMessages(leftId, 5000)
            val rightMessages = listMessages(rightId, 5000)

            var commonPrefix = 0
            val minSize = minOf(leftMessages.size, rightMessages.size)
            while (commonPrefix < minSize && messagesEquivalent(leftMessages[commonPrefix], rightMessages[commonPrefix])) {
                commonPrefix += 1
            }

            val leftOnly = leftMessages.size - commonPrefix
            val rightOnly = rightMessages.size - commonPrefix
            val leftPreview = leftMessages.getOrNull(commonPrefix)?.content?.trim()?.take(previewLength)
            val rightPreview = rightMessages.getOrNull(commonPrefix)?.content?.trim()?.take(previewLength)

            SessionBranchComparison(
                leftSessionId = leftId,
                rightSessionId = rightId,
                commonPrefixCount = commonPrefix,
                leftOnlyCount = leftOnly,
                rightOnlyCount = rightOnly,
                leftPreview = leftPreview,
                rightPreview = rightPreview,
            )
        }
    }

    fun saveContextSnapshot(
        sessionId: String,
        messageId: String? = null,
        title: String? = null,
        metadataJson: String? = null,
    ): Result<SessionContextSnapshot> {
        return runCatching {
            val normalizedSessionId = sessionId.trim()
            require(normalizedSessionId.isNotBlank()) { "Session id cannot be blank" }

            val session = getSession(normalizedSessionId)
                ?: throw IllegalArgumentException("Session not found: $normalizedSessionId")
            val messages = listMessages(normalizedSessionId, 5000)
            val normalizedMessageId = messageId?.trim()?.ifBlank { null }

            val includedCount = if (normalizedMessageId == null) {
                messages.size
            } else {
                val index = messages.indexOfFirst { message -> message.id == normalizedMessageId }
                require(index >= 0) { "Message not found in source session: $normalizedMessageId" }
                index + 1
            }

            val effectiveMessageId = if (includedCount > 0) {
                messages[includedCount - 1].id
            } else {
                null
            }
            val derivedTitle = if (includedCount > 0) {
                messages[includedCount - 1].content
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    ?.ifBlank { null }
            } else {
                null
            }
            val normalizedTitle = title?.trim()?.ifBlank { null } ?: derivedTitle ?: session.title
            val now = clock()
            val snapshot = SessionContextSnapshot(
                id = idGenerator(),
                sessionId = normalizedSessionId,
                messageId = effectiveMessageId,
                title = normalizedTitle.take(120),
                messageCount = includedCount,
                metadataJson = metadataJson?.trim()?.ifBlank { null },
                createdAt = now,
            )

            withConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO session_context_snapshots(
                        id, session_id, message_id, snapshot_title, message_count, metadata_json, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, snapshot.id)
                    statement.setString(2, snapshot.sessionId)
                    statement.setString(3, snapshot.messageId)
                    statement.setString(4, snapshot.title)
                    statement.setInt(5, snapshot.messageCount)
                    statement.setString(6, snapshot.metadataJson)
                    statement.setLong(7, snapshot.createdAt)
                    statement.executeUpdate()
                }
            }
            snapshot
        }
    }

    fun listContextSnapshots(sessionId: String? = null, limit: Int = 100): List<SessionContextSnapshot> {
        val normalizedSessionId = sessionId?.trim()?.ifBlank { null }
        val normalizedLimit = limit.coerceAtLeast(1)

        return withConnection { connection ->
            val sql = buildString {
                append(
                    """
                    SELECT id, session_id, message_id, snapshot_title, message_count, metadata_json, created_at
                    FROM session_context_snapshots
                    """.trimIndent()
                )
                if (normalizedSessionId != null) {
                    append(" WHERE session_id = ?")
                }
                append(" ORDER BY created_at DESC")
                append(" LIMIT ?")
            }

            connection.prepareStatement(sql).use { statement ->
                var index = 1
                if (normalizedSessionId != null) {
                    statement.setString(index, normalizedSessionId)
                    index += 1
                }
                statement.setInt(index, normalizedLimit)

                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toContextSnapshot())
                        }
                    }
                }
            }
        }
    }

    fun continueFromSnapshot(
        snapshotId: String,
        branchName: String? = null,
    ): Result<ConversationSession> {
        return runCatching {
            val normalizedSnapshotId = snapshotId.trim()
            require(normalizedSnapshotId.isNotBlank()) { "Snapshot id cannot be blank" }

            val snapshot = getContextSnapshot(normalizedSnapshotId)
                ?: throw IllegalArgumentException("Context snapshot not found: $normalizedSnapshotId")
            val source = getSession(snapshot.sessionId)
                ?: throw IllegalArgumentException("Session not found: ${snapshot.sessionId}")
            val sourceMessages = listMessages(source.id, 5000)
            val copyUntilExclusive = if (snapshot.messageId == null) {
                snapshot.messageCount.coerceAtMost(sourceMessages.size)
            } else {
                val index = sourceMessages.indexOfFirst { message -> message.id == snapshot.messageId }
                require(index >= 0) { "Message not found in source session: ${snapshot.messageId}" }
                index + 1
            }

            val normalizedBranchName = branchName?.trim()?.ifBlank { null }
            val effectiveBranchName = normalizedBranchName ?: "continue-${clock()}"
            val continuedTitle = "${source.title} [continue]"
            val continued = createSession(
                title = continuedTitle,
                specTaskId = source.specTaskId,
                worktreeId = source.worktreeId,
                modelProvider = source.modelProvider,
                parentSessionId = source.id,
                branchFromMessageId = snapshot.messageId,
                branchName = effectiveBranchName,
            ).getOrThrow()

            sourceMessages.take(copyUntilExclusive).forEach { message ->
                addMessage(
                    sessionId = continued.id,
                    role = message.role,
                    content = message.content,
                    tokenCount = message.tokenCount,
                    metadataJson = message.metadataJson,
                ).getOrThrow()
            }

            getSession(continued.id) ?: continued
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
                if (migration.version == 3) {
                    runSessionBranchMigration(connection)
                } else {
                    migration.statements.forEach { sql ->
                        connection.createStatement().use { statement ->
                            statement.execute(sql)
                        }
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

    private fun getContextSnapshot(snapshotId: String): SessionContextSnapshot? {
        val normalizedSnapshotId = snapshotId.trim()
        if (normalizedSnapshotId.isBlank()) {
            return null
        }

        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, message_id, snapshot_title, message_count, metadata_json, created_at
                FROM session_context_snapshots
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, normalizedSnapshotId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        resultSet.toContextSnapshot()
                    }
                }
            }
        }
    }

    private fun ResultSet.toSession(): ConversationSession {
        return ConversationSession(
            id = getString("id"),
            title = getString("title"),
            specTaskId = getString("spec_task_id"),
            worktreeId = getString("worktree_id"),
            modelProvider = getString("model_provider"),
            parentSessionId = getString("parent_session_id"),
            branchFromMessageId = getString("branch_from_message_id"),
            branchName = getString("branch_name"),
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

    private fun ResultSet.toContextSnapshot(): SessionContextSnapshot {
        return SessionContextSnapshot(
            id = getString("id"),
            sessionId = getString("session_id"),
            messageId = getString("message_id"),
            title = getString("snapshot_title"),
            messageCount = getInt("message_count"),
            metadataJson = getString("metadata_json"),
            createdAt = getLong("created_at"),
        )
    }

    private fun ResultSet.toSummary(): SessionSummary {
        return SessionSummary(
            id = getString("id"),
            title = getString("title"),
            specTaskId = getString("spec_task_id"),
            worktreeId = getString("worktree_id"),
            modelProvider = getString("model_provider"),
            parentSessionId = getString("parent_session_id"),
            branchName = getString("branch_name"),
            messageCount = getInt("message_count"),
            updatedAt = getLong("updated_at"),
        )
    }

    private fun messagesEquivalent(left: ConversationMessage, right: ConversationMessage): Boolean {
        return left.role == right.role && left.content.trim() == right.content.trim()
    }

    private fun hasColumn(connection: Connection, tableName: String, columnName: String): Boolean {
        connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    if (resultSet.getString("name").equals(columnName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun runSessionBranchMigration(connection: Connection) {
        if (!hasColumn(connection, "sessions", "parent_session_id")) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE sessions ADD COLUMN parent_session_id TEXT")
            }
        }
        if (!hasColumn(connection, "sessions", "branch_from_message_id")) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE sessions ADD COLUMN branch_from_message_id TEXT")
            }
        }
        if (!hasColumn(connection, "sessions", "branch_name")) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE sessions ADD COLUMN branch_name TEXT")
            }
        }
        connection.createStatement().use { statement ->
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_parent_session_id ON sessions(parent_session_id)")
        }
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
            SessionMigration(
                version = 3,
                statements = emptyList(),
            ),
            SessionMigration(
                version = 4,
                statements = listOf(
                    """
                    CREATE TABLE IF NOT EXISTS session_context_snapshots(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        message_id TEXT,
                        snapshot_title TEXT NOT NULL,
                        message_count INTEGER NOT NULL DEFAULT 0,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_context_snapshots_session_created_at ON session_context_snapshots(session_id, created_at DESC)",
                ),
            ),
        )

        fun getInstance(project: Project): SessionManager = project.service()
    }
}
