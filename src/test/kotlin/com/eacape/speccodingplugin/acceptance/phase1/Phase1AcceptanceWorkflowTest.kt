package com.eacape.speccodingplugin.acceptance.phase1

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.prompt.GlobalPromptManager
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase1AcceptanceWorkflowTest {

    @Test
    fun `phase1 acceptance preconditions should be available`() {
        val registry = ModelRegistry()
        val allModels = registry.getAllModels()

        assertTrue(allModels.isNotEmpty(), "Model registry should have available models")
        assertTrue(registry.getModelsForProvider("openai").isNotEmpty(), "OpenAI models should be available")
        assertTrue(registry.getModelsForProvider("anthropic").isNotEmpty(), "Anthropic models should be available")

        val promptManager = GlobalPromptManager()
        assertTrue(promptManager.listPromptTemplates().isNotEmpty(), "Global prompt manager should have templates")

        val operationModeManager = OperationModeManager(fakeProject())
        assertEquals(OperationMode.DEFAULT, operationModeManager.getCurrentMode())
    }

    @Test
    fun `operation modes required by phase1 should all be reachable`() {
        val manager = OperationModeManager(fakeProject())

        OperationMode.entries.forEach { mode ->
            manager.switchMode(mode)
            assertEquals(mode, manager.getCurrentMode(), "Mode should switch to $mode")
        }
    }

    private fun fakeProject(): Project {
        return mockk(relaxed = true)
    }
}
