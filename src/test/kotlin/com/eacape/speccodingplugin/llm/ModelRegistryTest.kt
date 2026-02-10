package com.eacape.speccodingplugin.llm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelRegistryTest {

    private lateinit var registry: ModelRegistry

    @BeforeEach
    fun setUp() {
        registry = ModelRegistry()
    }

    @Test
    fun `should have default models registered`() {
        val models = registry.getAllModels()
        assertTrue(models.isNotEmpty(), "Registry should have default models")
    }

    @Test
    fun `should have OpenAI models`() {
        val openAiModels = registry.getModelsForProvider("openai")
        assertTrue(openAiModels.isNotEmpty(), "Should have OpenAI models")

        val modelIds = openAiModels.map { it.id }
        assertTrue(modelIds.contains("gpt-4o"), "Should have GPT-4o")
        assertTrue(modelIds.contains("gpt-4o-mini"), "Should have GPT-4o Mini")
        assertTrue(modelIds.contains("o1"), "Should have O1")
        assertTrue(modelIds.contains("o1-mini"), "Should have O1 Mini")
    }

    @Test
    fun `should have Anthropic models`() {
        val anthropicModels = registry.getModelsForProvider("anthropic")
        assertTrue(anthropicModels.isNotEmpty(), "Should have Anthropic models")

        val modelIds = anthropicModels.map { it.id }
        assertTrue(modelIds.contains("claude-opus-4-20250514"), "Should have Claude Opus 4")
        assertTrue(modelIds.contains("claude-sonnet-4-20250514"), "Should have Claude Sonnet 4")
        assertTrue(modelIds.contains("claude-3-5-sonnet-20241022"), "Should have Claude 3.5 Sonnet")
        assertTrue(modelIds.contains("claude-3-5-haiku-20241022"), "Should have Claude 3.5 Haiku")
    }

    @Test
    fun `should get model by id`() {
        val model = registry.getModel("gpt-4o")
        assertNotNull(model, "Should find GPT-4o model")
        assertEquals("gpt-4o", model?.id)
        assertEquals("GPT-4o", model?.name)
        assertEquals("openai", model?.provider)
    }

    @Test
    fun `should return null for non-existent model`() {
        val model = registry.getModel("non-existent-model")
        assertNull(model, "Should return null for non-existent model")
    }

    @Test
    fun `should group models by provider`() {
        val grouped = registry.getModelsByProvider()
        assertTrue(grouped.containsKey("openai"), "Should have OpenAI group")
        assertTrue(grouped.containsKey("anthropic"), "Should have Anthropic group")

        val openAiCount = grouped["openai"]?.size ?: 0
        val anthropicCount = grouped["anthropic"]?.size ?: 0

        assertTrue(openAiCount >= 4, "Should have at least 4 OpenAI models")
        assertTrue(anthropicCount >= 4, "Should have at least 4 Anthropic models")
    }

    @Test
    fun `should check model capabilities`() {
        assertTrue(
            registry.hasCapability("gpt-4o", ModelCapability.CODE_GENERATION),
            "GPT-4o should have CODE_GENERATION capability"
        )
        assertTrue(
            registry.hasCapability("gpt-4o", ModelCapability.CODE_REVIEW),
            "GPT-4o should have CODE_REVIEW capability"
        )
        assertTrue(
            registry.hasCapability("claude-opus-4-20250514", ModelCapability.REASONING),
            "Claude Opus 4 should have REASONING capability"
        )
    }

    @Test
    fun `should return false for non-existent model capability check`() {
        assertFalse(
            registry.hasCapability("non-existent-model", ModelCapability.CODE_GENERATION),
            "Should return false for non-existent model"
        )
    }

    @Test
    fun `should register custom model`() {
        val customModel = ModelInfo(
            id = "custom-model",
            name = "Custom Model",
            provider = "custom",
            contextWindow = 8000,
            capabilities = setOf(ModelCapability.CHAT)
        )

        registry.register(customModel)

        val retrieved = registry.getModel("custom-model")
        assertNotNull(retrieved, "Should find custom model")
        assertEquals("Custom Model", retrieved?.name)
        assertEquals("custom", retrieved?.provider)
    }

    @Test
    fun `should overwrite existing model on re-registration`() {
        val model1 = ModelInfo(
            id = "test-model",
            name = "Test Model V1",
            provider = "test",
            contextWindow = 4000,
            capabilities = setOf(ModelCapability.CHAT)
        )

        val model2 = ModelInfo(
            id = "test-model",
            name = "Test Model V2",
            provider = "test",
            contextWindow = 8000,
            capabilities = setOf(ModelCapability.CHAT, ModelCapability.CODE_GENERATION)
        )

        registry.register(model1)
        registry.register(model2)

        val retrieved = registry.getModel("test-model")
        assertEquals("Test Model V2", retrieved?.name)
        assertEquals(8000, retrieved?.contextWindow)
        assertEquals(2, retrieved?.capabilities?.size)
    }

    @Test
    fun `should have valid context windows`() {
        val models = registry.getAllModels()
        models.forEach { model ->
            assertTrue(
                model.contextWindow > 0,
                "Model ${model.name} should have positive context window"
            )
        }
    }

    @Test
    fun `should have unique model ids`() {
        val models = registry.getAllModels()
        val ids = models.map { it.id }
        val uniqueIds = ids.toSet()

        assertEquals(
            ids.size,
            uniqueIds.size,
            "All model IDs should be unique"
        )
    }

    @Test
    fun `should have at least one capability per model`() {
        val models = registry.getAllModels()
        models.forEach { model ->
            assertTrue(
                model.capabilities.isNotEmpty(),
                "Model ${model.name} should have at least one capability"
            )
        }
    }
}
