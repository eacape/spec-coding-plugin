package com.eacape.speccodingplugin.llm

import com.intellij.openapi.diagnostic.thisLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class OpenAiProvider(
    private val apiKey: String = "",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val defaultModel: String = "gpt-4o",
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

    override val id: String = "openai"

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val requestId = generateRequestId()
        activeRequests[requestId] = true

        try {
            val apiRequest = buildApiRequest(request, stream = false)
            val response = httpClient.post("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                header("Content-Type", "application/json")
                setBody(json.encodeToString(OpenAiChatRequest.serializer(), apiRequest))
            }

            if (!response.status.isSuccess()) {
                throw LlmException("OpenAI API error: ${response.status} - ${response.bodyAsText()}")
            }

            val apiResponse = json.decodeFromString<OpenAiChatResponse>(response.bodyAsText())
            parseResponse(apiResponse, request)
        } catch (e: Exception) {
            logger.warn("OpenAI generate failed", e)
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
                val response = httpClient.post("$baseUrl/chat/completions") {
                    header("Authorization", "Bearer $apiKey")
                    header("Content-Type", "application/json")
                    header("Accept", "text/event-stream")
                    setBody(json.encodeToString(OpenAiChatRequest.serializer(), apiRequest))
                }

                if (!response.status.isSuccess()) {
                    throw LlmException("OpenAI API error: ${response.status} - ${response.bodyAsText()}")
                }

                parseStreamResponse(response, request, onChunk, requestId)
            } catch (e: Exception) {
                logger.warn("OpenAI stream failed", e)
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
            val response = httpClient.get("$baseUrl/models") {
                header("Authorization", "Bearer $apiKey")
            }

            if (response.status.isSuccess()) {
                LlmHealthStatus(healthy = true, message = "OpenAI API is accessible")
            } else {
                LlmHealthStatus(
                    healthy = false,
                    message = "OpenAI API returned ${response.status}"
                )
            }
        } catch (e: Exception) {
            logger.warn("OpenAI health check failed", e)
            LlmHealthStatus(
                healthy = false,
                message = "Failed to connect: ${e.message}"
            )
        }
    }

    private fun buildApiRequest(request: LlmRequest, stream: Boolean): OpenAiChatRequest {
        val messages = request.messages.map { msg ->
            OpenAiMessage(
                role = when (msg.role) {
                    LlmRole.SYSTEM -> "system"
                    LlmRole.USER -> "user"
                    LlmRole.ASSISTANT -> "assistant"
                },
                content = msg.content
            )
        }

        return OpenAiChatRequest(
            model = request.model ?: defaultModel,
            messages = messages,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = stream
        )
    }

    private fun parseResponse(apiResponse: OpenAiChatResponse, request: LlmRequest): LlmResponse {
        val choice = apiResponse.choices.firstOrNull()
            ?: throw LlmException("No choices in OpenAI response")

        return LlmResponse(
            content = choice.message.content,
            model = apiResponse.model,
            usage = LlmUsage(
                promptTokens = apiResponse.usage?.promptTokens ?: 0,
                completionTokens = apiResponse.usage?.completionTokens ?: 0
            ),
            finishReason = choice.finishReason
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

        response.bodyAsChannel().readUTF8Line(Int.MAX_VALUE)?.let { line ->
            processStreamLine(line, contentBuilder, onChunk, requestId)?.let { parsed ->
                model = parsed.model ?: model
                finishReason = parsed.finishReason
            }
        }

        // Send final chunk
        onChunk(LlmChunk(delta = "", isLast = true))

        return LlmResponse(
            content = contentBuilder.toString(),
            model = model,
            usage = LlmUsage(), // Streaming doesn't provide usage in real-time
            finishReason = finishReason
        )
    }

    private suspend fun processStreamLine(
        line: String,
        contentBuilder: StringBuilder,
        onChunk: suspend (LlmChunk) -> Unit,
        requestId: String
    ): OpenAiStreamChunk? {
        if (!activeRequests.containsKey(requestId)) {
            return null // Request was cancelled
        }

        if (!line.startsWith("data: ")) {
            return null
        }

        val data = line.removePrefix("data: ").trim()
        if (data == "[DONE]") {
            return null
        }

        return try {
            val chunk = json.decodeFromString<OpenAiStreamChunk>(data)
            val delta = chunk.choices.firstOrNull()?.delta?.content
            if (delta != null) {
                contentBuilder.append(delta)
                onChunk(LlmChunk(delta = delta, isLast = false))
            }
            chunk
        } catch (e: Exception) {
            logger.warn("Failed to parse stream chunk: $data", e)
            null
        }
    }

    private fun generateRequestId(): String {
        return "openai-${System.currentTimeMillis()}-${(0..9999).random()}"
    }

    fun close() {
        httpClient.close()
    }
}

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false
)

@Serializable
private data class OpenAiMessage(
    val role: String,
    val content: String
)

@Serializable
private data class OpenAiChatResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int
)

@Serializable
private data class OpenAiStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAiStreamChoice>,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
private data class OpenAiStreamChoice(
    val delta: OpenAiDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
private data class OpenAiDelta(
    val content: String? = null
)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
