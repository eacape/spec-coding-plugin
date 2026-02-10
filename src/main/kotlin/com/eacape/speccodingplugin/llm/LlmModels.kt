package com.eacape.speccodingplugin.llm

enum class LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class LlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    val totalTokens: Int
        get() = promptTokens + completionTokens
}

data class LlmResponse(
    val content: String,
    val model: String,
    val usage: LlmUsage = LlmUsage(),
    val finishReason: String? = null,
)

data class LlmChunk(
    val delta: String,
    val isLast: Boolean = false,
)

data class LlmHealthStatus(
    val healthy: Boolean,
    val message: String? = null,
)

