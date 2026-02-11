package com.eacape.speccodingplugin.core

import com.eacape.speccodingplugin.context.ContextFormatter
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextSnapshot
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
        contextSnapshot: ContextSnapshot? = null,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        val messages = mutableListOf<LlmMessage>()

        // 1. System prompt
        messages.add(
            LlmMessage(
                role = LlmRole.SYSTEM,
                content = promptManager.renderActivePrompt(
                    runtimeVariables = mapOf(
                        "project_name" to project.name,
                        "provider" to providerId.orEmpty(),
                    ),
                ),
            ),
        )

        // 2. Context as second system message (if present)
        if (contextSnapshot != null && contextSnapshot.items.isNotEmpty()) {
            val contextText = ContextFormatter.format(contextSnapshot)
            if (contextText.isNotBlank()) {
                messages.add(LlmMessage(role = LlmRole.SYSTEM, content = contextText))
            }
        }

        // 3. User message
        messages.add(LlmMessage(role = LlmRole.USER, content = userInput))

        val request = LlmRequest(messages = messages, model = null)
        return llmRouter.stream(providerId = providerId, request = request, onChunk = onChunk)
    }
}
