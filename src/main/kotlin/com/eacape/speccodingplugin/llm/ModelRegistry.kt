package com.eacape.speccodingplugin.llm

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * 模型注册表 - 管理所有可用的 LLM 模型元数据
 */
@Service
class ModelRegistry {
    private val models = mutableMapOf<String, ModelInfo>()

    init {
        registerDefaultModels()
    }

    /**
     * 注册默认模型
     */
    private fun registerDefaultModels() {
        // OpenAI 模型
        register(
            ModelInfo(
                id = "gpt-4o",
                name = "GPT-4o",
                provider = "openai",
                contextWindow = 128000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.CODE_REVIEW,
                    ModelCapability.CHAT
                )
            )
        )

        register(
            ModelInfo(
                id = "gpt-4o-mini",
                name = "GPT-4o Mini",
                provider = "openai",
                contextWindow = 128000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.CODE_REVIEW,
                    ModelCapability.CHAT
                )
            )
        )

        register(
            ModelInfo(
                id = "o1",
                name = "O1",
                provider = "openai",
                contextWindow = 200000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.REASONING
                )
            )
        )

        register(
            ModelInfo(
                id = "o1-mini",
                name = "O1 Mini",
                provider = "openai",
                contextWindow = 128000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.REASONING
                )
            )
        )

        // Anthropic 模型
        register(
            ModelInfo(
                id = "claude-opus-4-20250514",
                name = "Claude Opus 4",
                provider = "anthropic",
                contextWindow = 200000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.CODE_REVIEW,
                    ModelCapability.CHAT,
                    ModelCapability.REASONING
                )
            )
        )

        register(
            ModelInfo(
                id = "claude-sonnet-4-20250514",
                name = "Claude Sonnet 4",
                provider = "anthropic",
                contextWindow = 200000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.CODE_REVIEW,
                    ModelCapability.CHAT
                )
            )
        )

        register(
            ModelInfo(
                id = "claude-3-5-sonnet-20241022",
                name = "Claude 3.5 Sonnet",
                provider = "anthropic",
                contextWindow = 200000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.CODE_REVIEW,
                    ModelCapability.CHAT
                )
            )
        )

        register(
            ModelInfo(
                id = "claude-3-5-haiku-20241022",
                name = "Claude 3.5 Haiku",
                provider = "anthropic",
                contextWindow = 200000,
                capabilities = setOf(
                    ModelCapability.CODE_GENERATION,
                    ModelCapability.CHAT
                )
            )
        )
    }

    /**
     * 注册模型
     */
    fun register(model: ModelInfo) {
        models[model.id] = model
    }

    /**
     * 获取模型信息
     */
    fun getModel(id: String): ModelInfo? {
        return models[id]
    }

    /**
     * 获取所有模型
     */
    fun getAllModels(): List<ModelInfo> {
        return models.values.toList()
    }

    /**
     * 按提供者分组获取模型
     */
    fun getModelsByProvider(): Map<String, List<ModelInfo>> {
        return models.values.groupBy { it.provider }
    }

    /**
     * 获取指定提供者的模型
     */
    fun getModelsForProvider(provider: String): List<ModelInfo> {
        return models.values.filter { it.provider == provider }
    }

    /**
     * 检查模型是否支持某个能力
     */
    fun hasCapability(modelId: String, capability: ModelCapability): Boolean {
        return models[modelId]?.capabilities?.contains(capability) ?: false
    }

    companion object {
        fun getInstance(): ModelRegistry = service()
    }
}

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Int,
    val capabilities: Set<ModelCapability>,
    val description: String? = null
)

/**
 * 模型能力
 */
enum class ModelCapability {
    CODE_GENERATION,
    CODE_REVIEW,
    CHAT,
    REASONING,
    REFACTOR,
    TEST_GENERATION
}
