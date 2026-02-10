package com.eacape.speccodingplugin.llm

class LlmRouter(
    providers: List<LlmProvider> = listOf(
        MockLlmProvider(),
        OpenAiProvider(),
        AnthropicProvider(),
    ),
    private val defaultProviderId: String = "mock",
) {
    private val providerMap: Map<String, LlmProvider> = providers.associateBy { it.id }

    fun availableProviders(): List<String> = providerMap.keys.sorted()

    suspend fun generate(providerId: String?, request: LlmRequest): LlmResponse {
        return resolveProvider(providerId).generate(request)
    }

    suspend fun stream(
        providerId: String?,
        request: LlmRequest,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        return resolveProvider(providerId).stream(request, onChunk)
    }

    suspend fun healthCheckAll(): Map<String, LlmHealthStatus> {
        return providerMap.mapValues { (_, provider) -> provider.healthCheck() }
    }

    private fun resolveProvider(providerId: String?): LlmProvider {
        val normalized = providerId?.trim().orEmpty()
        if (normalized.isNotBlank()) {
            providerMap[normalized]?.let { return it }
        }
        return providerMap[defaultProviderId]
            ?: error("Default provider '$defaultProviderId' is not registered")
    }
}

