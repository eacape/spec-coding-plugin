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
import com.eacape.speccodingplugin.llm.LlmRequestContext
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecCodingProjectService(private val project: Project) {
    private val llmRouter by lazy { LlmRouter.getInstance() }
    private val promptManager = project.getService(PromptManager::class.java)

    fun getProjectName(): String = project.name

    fun availableProviders(): List<String> = llmRouter.availableUiProviders()

    fun availablePrompts(): List<PromptTemplate> = promptManager.listPromptTemplates()

    fun activePromptId(): String = promptManager.getActivePromptId()

    fun switchActivePrompt(promptId: String): Boolean = promptManager.setActivePrompt(promptId)

    suspend fun chat(
        providerId: String?,
        userInput: String,
        modelId: String? = null,
        contextSnapshot: ContextSnapshot? = null,
        conversationHistory: List<LlmMessage> = emptyList(),
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
                        "project_path" to project.basePath.orEmpty(),
                        "provider" to providerId.orEmpty(),
                    ),
                ),
            ),
        )
        messages.add(
            LlmMessage(
                role = LlmRole.SYSTEM,
                content = DEV_WORKFLOW_SYSTEM_INSTRUCTION,
            ),
        )

        // 2. Context as second system message (if present)
        if (contextSnapshot != null && contextSnapshot.items.isNotEmpty()) {
            val contextText = ContextFormatter.format(contextSnapshot)
            if (contextText.isNotBlank()) {
                messages.add(LlmMessage(role = LlmRole.SYSTEM, content = contextText))
            }
        }

        // 3. Conversation history (if present), fallback to single-turn user message.
        val normalizedHistory = conversationHistory
            .filter { it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT }
            .takeLast(MAX_CHAT_HISTORY_MESSAGES)
        if (normalizedHistory.isNotEmpty()) {
            messages.addAll(normalizedHistory)
            if (normalizedHistory.lastOrNull()?.role != LlmRole.USER && userInput.isNotBlank()) {
                messages.add(LlmMessage(role = LlmRole.USER, content = userInput))
            }
        } else {
            messages.add(LlmMessage(role = LlmRole.USER, content = userInput))
        }

        val workingDirectory = LlmRequestContext.normalizeWorkingDirectory(project.basePath)
        val metadata = if (workingDirectory != null) {
            mapOf(LlmRequestContext.WORKING_DIRECTORY_METADATA_KEY to workingDirectory)
        } else {
            emptyMap()
        }

        val request = LlmRequest(
            messages = messages,
            model = modelId,
            metadata = metadata,
            workingDirectory = workingDirectory,
        )
        return llmRouter.stream(providerId = providerId, request = request, onChunk = onChunk)
    }

    companion object {
        private const val MAX_CHAT_HISTORY_MESSAGES = 24
        private const val DEV_WORKFLOW_SYSTEM_INSTRUCTION = """
            You are the in-IDE project development copilot.
            Prefer workflow-oriented responses for implementation tasks:
            1) clarify objective and constraints briefly,
            2) propose a concrete implementation plan,
            3) provide executable code-level changes,
            4) include verification/check steps.
            During implementation replies, include short progress lines when relevant, using prefixes:
            [Thinking], [Read], [Edit], [Task], [Verify].
            For non-trivial development requests, use this markdown structure:
            ## Plan
            - concise, actionable steps
            ## Execute
            - key code changes, files, and commands
            ## Verify
            - checks/tests and expected result
            Keep responses practical, specific to this repository, and avoid generic filler.
        """
    }
}
