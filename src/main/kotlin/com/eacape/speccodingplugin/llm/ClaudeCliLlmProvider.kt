package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.ClaudeCodeEngine
import com.eacape.speccodingplugin.engine.EngineContext
import com.eacape.speccodingplugin.engine.EngineRequest
import com.intellij.openapi.diagnostic.thisLogger
import java.util.Locale

/**
 * Claude CLI LlmProvider 适配器
 * 将 LlmProvider 接口委托给 ClaudeCodeEngine
 */
class ClaudeCliLlmProvider(
    private val discoveryService: CliDiscoveryService,
) : LlmProvider {

    override val id: String = ID

    private val logger = thisLogger()

    private val engine: ClaudeCodeEngine by lazy {
        ClaudeCodeEngine(discoveryService.claudeInfo.path)
    }

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val engineResponse = engine.generate(engineRequest)

        if (!engineResponse.success) {
            throw RuntimeException("Claude CLI error: ${engineResponse.error}")
        }

        return LlmResponse(
            content = engineResponse.content,
            model = request.model ?: "claude-cli",
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
            model = request.model ?: "claude-cli",
        )
    }

    override fun cancel(requestId: String) {
        engine.cancelProcess(requestId)
    }

    override suspend fun healthCheck(): LlmHealthStatus {
        val info = discoveryService.claudeInfo
        return LlmHealthStatus(
            healthy = info.available,
            message = if (info.available) "Claude CLI v${info.version}" else "Claude CLI not found",
        )
    }

    private fun toEngineRequest(request: LlmRequest): EngineRequest {
        val systemMessages = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .joinToString("\n\n") { it.content }

        val userMessages = request.messages
            .filter { it.role != LlmRole.SYSTEM }
            .joinToString("\n\n") { it.content }

        val options = mutableMapOf<String, String>()
        request.model?.let { options["model"] = it }
        if (systemMessages.isNotBlank()) {
            options["system_prompt"] = systemMessages
        }
        request.maxTokens?.let { options["max_tokens"] = it.toString() }
        request.metadata["requestId"]?.let { options["requestId"] = it }
        val workingDirectory = LlmRequestContext.extractWorkingDirectory(request)

        val operationMode = LlmRequestContext.extractOperationMode(request)
        mapClaudePermissionMode(operationMode)?.let { options["permission_mode"] = it }
        if (operationMode?.equals(OperationMode.AUTO.name, ignoreCase = true) == true) {
            options["allow_dangerously_skip_permissions"] = "true"
            options["dangerously_skip_permissions"] = "true"
        }

        return EngineRequest(
            prompt = userMessages,
            context = EngineContext(workingDirectory = workingDirectory),
            options = options,
        )
    }

    private fun mapClaudePermissionMode(operationMode: String?): String? {
        val mode = operationMode
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { runCatching { OperationMode.valueOf(it) }.getOrNull() }
            ?: return null
        return when (mode) {
            OperationMode.PLAN -> "plan"
            OperationMode.DEFAULT -> null
            OperationMode.AGENT -> "acceptEdits"
            OperationMode.AUTO -> "bypassPermissions"
        }
    }

    companion object {
        const val ID = "claude-cli"
    }
}
