package com.eacape.speccodingplugin.session

enum class ConversationRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

enum class SessionFilter {
    ALL,
    SPEC_BOUND,
    WORKTREE_BOUND,
}

data class ConversationSession(
    val id: String,
    val title: String,
    val specTaskId: String? = null,
    val worktreeId: String? = null,
    val modelProvider: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ConversationMessage(
    val id: String,
    val sessionId: String,
    val role: ConversationRole,
    val content: String,
    val tokenCount: Int? = null,
    val createdAt: Long,
    val metadataJson: String? = null,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val specTaskId: String? = null,
    val worktreeId: String? = null,
    val modelProvider: String? = null,
    val messageCount: Int,
    val updatedAt: Long,
)
