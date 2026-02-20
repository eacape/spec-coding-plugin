package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.EngineContext
import com.eacape.speccodingplugin.engine.EngineRequest
import com.eacape.speccodingplugin.engine.OpenAiCodexEngine
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Codex CLI LlmProvider 适配器
 * 将 LlmProvider 接口委托给 OpenAiCodexEngine
 */
class CodexCliLlmProvider(
    private val discoveryService: CliDiscoveryService,
) : LlmProvider {

    override val id: String = ID

    private val logger = thisLogger()

    private val engine: OpenAiCodexEngine by lazy {
        OpenAiCodexEngine(discoveryService.codexInfo.path)
    }

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val engineResponse = engine.generate(engineRequest)

        if (!engineResponse.success) {
            throw RuntimeException("Codex CLI error: ${engineResponse.error}")
        }

        return LlmResponse(
            content = engineResponse.content,
            model = request.model ?: "codex-cli",
        )
    }

    override suspend fun stream(
        request: LlmRequest,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val contentBuilder = StringBuilder()

        engine.stream(engineRequest).collect { chunk ->
            contentBuilder.append(chunk.delta)
            onChunk(
                LlmChunk(
                    delta = chunk.delta,
                    isLast = chunk.isLast,
                    event = chunk.event,
                )
            )
        }

        return LlmResponse(
            content = contentBuilder.toString(),
            model = request.model ?: "codex-cli",
        )
    }

    override fun cancel(requestId: String) {
        engine.cancelProcess(requestId)
    }

    override suspend fun healthCheck(): LlmHealthStatus {
        val info = discoveryService.codexInfo
        return LlmHealthStatus(
            healthy = info.available,
            message = if (info.available) "Codex CLI v${info.version}" else "Codex CLI not found",
        )
    }

    private fun toEngineRequest(request: LlmRequest): EngineRequest {
        // Codex 不支持 system prompt 参数，将 system 消息合并到 prompt 中
        val allMessages = request.messages.joinToString("\n\n") { msg ->
            when (msg.role) {
                LlmRole.SYSTEM -> "[System]\n${msg.content}"
                LlmRole.USER -> msg.content
                LlmRole.ASSISTANT -> "[Assistant]\n${msg.content}"
            }
        }

        val options = mutableMapOf<String, String>()
        request.model?.let { options["model"] = it }
        request.metadata["requestId"]?.let { options["requestId"] = it }
        val workingDirectory = LlmRequestContext.extractWorkingDirectory(request)

        return EngineRequest(
            prompt = allMessages,
            context = EngineContext(workingDirectory = workingDirectory),
            options = options,
        )
    }

    companion object {
        const val ID = "codex-cli"
    }
}
