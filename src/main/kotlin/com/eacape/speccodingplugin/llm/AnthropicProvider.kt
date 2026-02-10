package com.eacape.speccodingplugin.llm

import com.intellij.openapi.diagnostic.thisLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class AnthropicProvider(
    private val apiKey: String = "",
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val defaultModel: String = "claude-opus-4-20250514",
) : LlmProvider {
    private val logger = thisLogger()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000 // 5 minutes
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
    }

    private val activeRequests = ConcurrentHashMap<String, Boolean>()

    override val id: String = "anthropic"

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val requestId = generateRequestId()
        activeRequests[requestId] = true

        try {
            val apiRequest = buildApiRequest(request, stream = false)
            val response = httpClient.post("$baseUrl/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                header("Content-Type", "application/json")
                setBody(json.encodeToString(AnthropicMessageRequest.serializer(), apiRequest))
            }

            if (!response.status.isSuccess()) {
                throw LlmException("Anthropic API error: ${response.status} - ${response.bodyAsText()}")
            }

            val apiResponse = json.decodeFromString<AnthropicMessageResponse>(response.bodyAsText())
            parseResponse(apiResponse)
        } catch (e: Exception) {
            logger.warn("Anthropic generate failed", e)
            throw LlmException("Failed to generate response: ${e.message}", e)
        } finally {
            activeRequests.remove(requestId)
        }
    }

    override suspend fun stream(request: LlmRequest, onChunk: suspend (LlmChunk) -> Unit): LlmResponse =
        withContext(Dispatchers.IO) {
            val requestId = generateRequestId()
            activeRequests[requestId] = true

            try {
                val apiRequest = buildApiRequest(request, stream = true)
                val response = httpClient.post("$baseUrl/messages") {
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    header("Content-Type", "application/json")
                    header("Accept", "text/event-stream")
                    setBody(json.encodeToString(AnthropicMessageRequest.serializer(), apiRequest))
                }

                if (!response.status.isSuccess()) {
                    throw LlmException("Anthropic API error: ${response.status} - ${response.bodyAsText()}")
                }

                parseStreamResponse(response, request, onChunk, requestId)
            } catch (e: Exception) {
                logger.warn("Anthropic stream failed", e)
                throw LlmException("Failed to stream response: ${e.message}", e)
            } finally {
                activeRequests.remove(requestId)
            }
        }

    override fun cancel(requestId: String) {
        activeRequests.remove(requestId)
    }

    override suspend fun healthCheck(): LlmHealthStatus = withContext(Dispatchers.IO) {
        try {
            // Anthropic doesn't have a dedicated health check endpoint
            // We'll do a minimal request to verify API key validity
            val testRequest = AnthropicMessageRequest(
                model = defaultModel,
                messages = listOf(AnthropicMessage(role = "user", content = "Hi")),
                maxTokens = 1,
                stream = false
            )

            val response = httpClient.post("$baseUrl/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                header("Content-Type", "application/json")
                setBody(json.encodeToString(AnthropicMessageRequest.serializer(), testRequest))
            }

            if (response.status.isSuccess()) {
                LlmHealthStatus(healthy = true, message = "Anthropic API is accessible")
            } else {
                LlmHealthStatus(
                    healthy = false,
                    message = "Anthropic API returned ${response.status}"
                )
            }
        } catch (e: Exception) {
            logger.warn("Anthropic health check failed", e)
            LlmHealthStatus(
                healthy = false,
                message = "Failed to connect: ${e.message}"
            )
        }
    }

    private fun buildApiRequest(request: LlmRequest, stream: Boolean): AnthropicMessageRequest {
        // Separate system messages from user/assistant messages
        val systemMessage = request.messages
            .firstOrNull { it.role == LlmRole.SYSTEM }
            ?.content

        val messages = request.messages
            .filter { it.role != LlmRole.SYSTEM }
            .map { msg ->
                AnthropicMessage(
                    role = when (msg.role) {
                        LlmRole.USER -> "user"
                        LlmRole.ASSISTANT -> "assistant"
                        LlmRole.SYSTEM -> "user" // Fallback, shouldn't happen
                    },
                    content = msg.content
                )
            }

        return AnthropicMessageRequest(
            model = request.model ?: defaultModel,
            messages = messages,
            system = systemMessage,
            maxTokens = request.maxTokens ?: 4096,
            temperature = request.temperature,
            stream = stream
        )
    }

    private fun parseResponse(apiResponse: AnthropicMessageResponse): LlmResponse {
        val textContent = apiResponse.content
            .firstOrNull { it.type == "text" }
            ?.text
            ?: throw LlmException("No text content in Anthropic response")

        return LlmResponse(
            content = textContent,
            model = apiResponse.model,
            usage = LlmUsage(
                promptTokens = apiResponse.usage.inputTokens,
                completionTokens = apiResponse.usage.outputTokens
            ),
            finishReason = apiResponse.stopReason
        )
    }

    private suspend fun parseStreamResponse(
        response: HttpResponse,
        request: LlmRequest,
        onChunk: suspend (LlmChunk) -> Unit,
        requestId: String
    ): LlmResponse {
        val contentBuilder = StringBuilder()
        var model = request.model ?: defaultModel
        var finishReason: String? = null
        var inputTokens = 0
        var outputTokens = 0

        response.bodyAsChannel().apply {
            while (!isClosedForRead) {
                val line = readUTF8Line(Int.MAX_VALUE) ?: break

                if (!activeRequests.containsKey(requestId)) {
                    break // Request was cancelled
                }

                processStreamLine(line, contentBuilder, onChunk)?.let { event ->
                    when (event) {
                        is StreamEvent.ContentBlock -> {
                            // Content already processed in processStreamLine
                        }
                        is StreamEvent.MessageStart -> {
                            model = event.model
                            inputTokens = event.inputTokens
                        }
                        is StreamEvent.MessageDelta -> {
                            finishReason = event.stopReason
                            outputTokens = event.outputTokens
                        }
                    }
                }
            }
        }

        // Send final chunk
        onChunk(LlmChunk(delta = "", isLast = true))

        return LlmResponse(
            content = contentBuilder.toString(),
            model = model,
            usage = LlmUsage(
                promptTokens = inputTokens,
                completionTokens = outputTokens
            ),
            finishReason = finishReason
        )
    }

    private suspend fun processStreamLine(
        line: String,
        contentBuilder: StringBuilder,
        onChunk: suspend (LlmChunk) -> Unit
    ): StreamEvent? {
        if (!line.startsWith("data: ")) {
            return null
        }

        val data = line.removePrefix("data: ").trim()
        if (data.isEmpty()) {
            return null
        }

        return try {
            val event = json.decodeFromString<AnthropicStreamEvent>(data)

            when (event.type) {
                "content_block_delta" -> {
                    val delta = event.delta?.text
                    if (delta != null) {
                        contentBuilder.append(delta)
                        onChunk(LlmChunk(delta = delta, isLast = false))
                    }
                    StreamEvent.ContentBlock
                }
                "message_start" -> {
                    val message = event.message
                    if (message != null) {
                        StreamEvent.MessageStart(
                            model = message.model,
                            inputTokens = message.usage.inputTokens
                        )
                    } else {
                        null
                    }
                }
                "message_delta" -> {
                    val delta = event.delta
                    val usage = event.usage
                    if (delta != null && usage != null) {
                        StreamEvent.MessageDelta(
                            stopReason = delta.stopReason,
                            outputTokens = usage.outputTokens
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse stream event: $data", e)
            null
        }
    }

    private fun generateRequestId(): String {
        return "anthropic-${System.currentTimeMillis()}-${(0..9999).random()}"
    }

    fun close() {
        httpClient.close()
    }
}

@Serializable
private data class AnthropicMessageRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false
)

@Serializable
private data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
private data class AnthropicMessageResponse(
    val id: String,
    val model: String,
    val content: List<AnthropicContent>,
    @SerialName("stop_reason")
    val stopReason: String?,
    val usage: AnthropicUsage
)

@Serializable
private data class AnthropicContent(
    val type: String,
    val text: String? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int
)

@Serializable
private data class AnthropicStreamEvent(
    val type: String,
    val message: AnthropicStreamMessage? = null,
    val delta: AnthropicStreamDelta? = null,
    val usage: AnthropicStreamUsage? = null
)

@Serializable
private data class AnthropicStreamMessage(
    val model: String,
    val usage: AnthropicUsage
)

@Serializable
private data class AnthropicStreamDelta(
    val text: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null
)

@Serializable
private data class AnthropicStreamUsage(
    @SerialName("output_tokens")
    val outputTokens: Int
)

private sealed class StreamEvent {
    object ContentBlock : StreamEvent()
    data class MessageStart(val model: String, val inputTokens: Int) : StreamEvent()
    data class MessageDelta(val stopReason: String?, val outputTokens: Int) : StreamEvent()
}
