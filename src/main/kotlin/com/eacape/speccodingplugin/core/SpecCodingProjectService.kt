package com.eacape.speccodingplugin.core

import com.eacape.speccodingplugin.llm.LlmChunk
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecCodingProjectService(private val project: Project) {
    private val llmRouter = LlmRouter()
    private val promptManager = project.getService(PromptManager::class.java)

    fun getProjectName(): String = project.name

    fun availableProviders(): List<String> = llmRouter.availableProviders()

    fun availablePrompts(): List<PromptTemplate> = promptManager.listPromptTemplates()

    fun activePromptId(): String = promptManager.getActivePromptId()

    fun switchActivePrompt(promptId: String): Boolean = promptManager.setActivePrompt(promptId)

    suspend fun chat(
        providerId: String?,
        userInput: String,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(
                    role = LlmRole.SYSTEM,
                    content = promptManager.renderActivePrompt(
                        runtimeVariables = mapOf(
                            "project_name" to project.name,
                            "provider" to providerId.orEmpty(),
                        ),
                    ),
                ),
                LlmMessage(role = LlmRole.USER, content = userInput),
            ),
            model = null,
        )
        return llmRouter.stream(providerId = providerId, request = request, onChunk = onChunk)
    }
}
