package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.skill.SkillExecutor
import com.eacape.speccodingplugin.skill.SkillContext
import com.eacape.speccodingplugin.ui.chat.ChatMessagePanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagesListPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 改进版 Chat Tool Window Panel
 *
 * 新增功能：
 * - 会话历史管理
 * - 斜杠命令支持
 * - 更好的错误处理
 * - 协程作用域管理
 */
class ImprovedChatPanel(
    private val project: Project,
) : JBPanel<ImprovedChatPanel>(BorderLayout()), Disposable {

    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val skillExecutor: SkillExecutor = SkillExecutor.getInstance(project)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // UI Components
    private val providerComboBox = ComboBox(projectService.availableProviders().toTypedArray())
    private val promptComboBox = ComboBox<PromptTemplate>()
    private val statusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val messagesPanel = ChatMessagesListPanel()
    private val inputField = JBTextField()
    private val sendButton = JButton(SpecCodingBundle.message("toolwindow.send"))
    private val clearButton = JButton("Clear")
    private var currentAssistantPanel: ChatMessagePanel? = null

    // Session state
    private val conversationHistory = mutableListOf<LlmMessage>()
    private var isGenerating = false
    @Volatile
    private var _isDisposed = false

    init {
        border = JBUI.Borders.empty(12)
        setupUI()
        loadPromptTemplatesAsync()
        addSystemMessage(SpecCodingBundle.message("toolwindow.system.intro"))
    }

    private fun setupUI() {
        // Header
        val titleLabel = JBLabel(SpecCodingBundle.message("toolwindow.welcome"))
        titleLabel.font = titleLabel.font.deriveFont(16f)

        val descriptionLabel = JBLabel(SpecCodingBundle.message("toolwindow.description", project.name))
        descriptionLabel.foreground = JBColor.GRAY
        descriptionLabel.border = JBUI.Borders.emptyTop(8)

        // Provider and Prompt selection
        val providerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        providerPanel.isOpaque = false
        providerPanel.border = JBUI.Borders.emptyTop(8)
        providerPanel.add(JBLabel(SpecCodingBundle.message("toolwindow.provider.label")))
        providerPanel.add(providerComboBox)
        providerPanel.add(JBLabel(SpecCodingBundle.message("toolwindow.prompt.label")))
        providerPanel.add(promptComboBox)
        providerPanel.add(clearButton)
        statusLabel.foreground = JBColor.GRAY
        providerPanel.add(statusLabel)

        promptComboBox.isEnabled = false
        promptComboBox.renderer = PromptTemplateCellRenderer()
        promptComboBox.addActionListener {
            val selected = promptComboBox.selectedItem as? PromptTemplate ?: return@addActionListener
            ApplicationManager.getApplication().executeOnPooledThread {
                projectService.switchActivePrompt(selected.id)
            }
        }

        clearButton.addActionListener { clearConversation() }

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel)
        headerPanel.add(descriptionLabel)
        headerPanel.add(providerPanel)

        // Conversation area
        val conversationScrollPane = messagesPanel.getScrollPane()
        conversationScrollPane.border = JBUI.Borders.emptyTop(12)

        // Input area
        val inputPanel = JPanel(BorderLayout())
        inputPanel.isOpaque = false
        inputPanel.border = JBUI.Borders.emptyTop(8)
        inputField.emptyText.text = SpecCodingBundle.message("toolwindow.input.placeholder")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        sendButton.addActionListener { sendCurrentInput() }
        inputField.addActionListener { sendCurrentInput() }

        // Layout
        val content = JPanel(BorderLayout())
        content.isOpaque = false
        content.add(headerPanel, BorderLayout.NORTH)
        content.add(conversationScrollPane, BorderLayout.CENTER)
        content.add(inputPanel, BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)
    }

    private fun loadPromptTemplatesAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val templates = projectService.availablePrompts()
            val activePromptId = projectService.activePromptId()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }

                promptComboBox.removeAllItems()
                templates.forEach { promptComboBox.addItem(it) }
                promptComboBox.selectedItem = templates.firstOrNull { it.id == activePromptId }
                promptComboBox.isEnabled = templates.isNotEmpty()
            }
        }
    }

    private fun sendCurrentInput() {
        val input = inputField.text.trim()
        if (input.isBlank() || isGenerating || project.isDisposed || _isDisposed) {
            return
        }

        // Check for slash commands
        if (input.startsWith("/")) {
            handleSlashCommand(input)
            return
        }

        val providerId = providerComboBox.selectedItem as? String

        // Add user message to history
        val userMessage = LlmMessage(LlmRole.USER, input)
        conversationHistory.add(userMessage)

        appendUserMessage(input)
        inputField.text = ""

        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        scope.launch {
            try {
                val assistantContent = StringBuilder()

                projectService.chat(providerId = providerId, userInput = input) { chunk ->
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }

                        if (!chunk.isLast) {
                            assistantContent.append(chunk.delta)
                            currentAssistantPanel?.appendContent(chunk.delta)
                            messagesPanel.scrollToBottom()
                        } else {
                            currentAssistantPanel?.finishMessage()
                        }
                    }
                }

                // Add assistant message to history
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                conversationHistory.add(assistantMessage)

            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    addErrorMessage(
                        error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"),
                    )
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun handleSlashCommand(command: String) {
        inputField.text = ""
        appendUserMessage(command)

        setSendingState(true)

        scope.launch {
            try {
                val context = SkillContext(
                    selectedCode = null,
                    currentFile = null
                )

                val result = skillExecutor.executeFromCommand(command, context)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }

                    when (result) {
                        is com.eacape.speccodingplugin.skill.SkillExecutionResult.Success -> {
                            val panel = addAssistantMessage()
                            panel.appendContent(result.output)
                            panel.finishMessage()
                        }
                        is com.eacape.speccodingplugin.skill.SkillExecutionResult.Failure -> {
                            addErrorMessage(result.error)
                        }
                    }
                }
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    addErrorMessage(error.message ?: "Unknown error")
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun clearConversation() {
        conversationHistory.clear()
        messagesPanel.clearAll()
        currentAssistantPanel = null
        addSystemMessage("Conversation cleared.")
    }

    private fun appendUserMessage(content: String) {
        val panel = ChatMessagePanel(
            ChatMessagePanel.MessageRole.USER, content,
            onDelete = ::handleDeleteMessage,
        )
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun addAssistantMessage(): ChatMessagePanel {
        val panel = ChatMessagePanel(
            ChatMessagePanel.MessageRole.ASSISTANT,
            onDelete = ::handleDeleteMessage,
            onRegenerate = ::handleRegenerateMessage,
        )
        messagesPanel.addMessage(panel)
        return panel
    }

    private fun addSystemMessage(content: String) {
        val panel = ChatMessagePanel(ChatMessagePanel.MessageRole.SYSTEM, content)
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun addErrorMessage(content: String) {
        val panel = ChatMessagePanel(ChatMessagePanel.MessageRole.ERROR, content)
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun setSendingState(sending: Boolean) {
        isGenerating = sending
        sendButton.isEnabled = !sending
        inputField.isEnabled = !sending
        providerComboBox.isEnabled = !sending
        promptComboBox.isEnabled = !sending && promptComboBox.itemCount > 0
        clearButton.isEnabled = !sending
        statusLabel.text = if (sending) {
            SpecCodingBundle.message("toolwindow.status.generating")
        } else {
            SpecCodingBundle.message("toolwindow.status.ready")
        }
    }

    private fun handleDeleteMessage(panel: ChatMessagePanel) {
        if (isGenerating) return
        messagesPanel.removeMessage(panel)
        // 同步删除对话历史中对应的消息
        rebuildConversationHistory()
    }

    private fun handleRegenerateMessage(panel: ChatMessagePanel) {
        if (isGenerating) return

        // 找到对应的 user 消息（前一条）
        val allMessages = messagesPanel.getAllMessages()
        val index = allMessages.indexOf(panel)
        if (index <= 0) return

        val userPanel = allMessages[index - 1]
        if (userPanel.role != ChatMessagePanel.MessageRole.USER) return

        val userInput = userPanel.getContent()

        // 删除当前 assistant 消息
        messagesPanel.removeMessage(panel)
        rebuildConversationHistory()

        // 重新发送
        val providerId = providerComboBox.selectedItem as? String
        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        scope.launch {
            try {
                val assistantContent = StringBuilder()
                projectService.chat(
                    providerId = providerId,
                    userInput = userInput
                ) { chunk ->
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        if (!chunk.isLast) {
                            assistantContent.append(chunk.delta)
                            currentAssistantPanel?.appendContent(chunk.delta)
                            messagesPanel.scrollToBottom()
                        } else {
                            currentAssistantPanel?.finishMessage()
                        }
                    }
                }
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                conversationHistory.add(assistantMessage)
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    addErrorMessage(error.message ?: "Unknown error")
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun rebuildConversationHistory() {
        conversationHistory.clear()
        for (panel in messagesPanel.getAllMessages()) {
            val role = when (panel.role) {
                ChatMessagePanel.MessageRole.USER -> LlmRole.USER
                ChatMessagePanel.MessageRole.ASSISTANT -> LlmRole.ASSISTANT
                ChatMessagePanel.MessageRole.SYSTEM -> LlmRole.SYSTEM
                ChatMessagePanel.MessageRole.ERROR -> continue
            }
            conversationHistory.add(LlmMessage(role, panel.getContent()))
        }
    }

    override fun dispose() {
        _isDisposed = true
        scope.cancel()
    }
}
