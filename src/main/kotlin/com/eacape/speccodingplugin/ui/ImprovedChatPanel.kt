package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextCollector
import com.eacape.speccodingplugin.context.ContextConfig
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.skill.SkillExecutor
import com.eacape.speccodingplugin.skill.SkillContext
import com.eacape.speccodingplugin.ui.chat.ChatMessagePanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagesListPanel
import com.eacape.speccodingplugin.ui.completion.CompletionProvider
import com.eacape.speccodingplugin.ui.completion.TriggerType
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.input.ContextPreviewPanel
import com.eacape.speccodingplugin.ui.input.SmartInputField
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
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
import javax.swing.JScrollPane

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

    private val logger = thisLogger()
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val sessionManager = SessionManager.getInstance(project)
    private val windowStateStore = WindowStateStore.getInstance(project)
    private val skillExecutor: SkillExecutor = SkillExecutor.getInstance(project)
    private val contextCollector by lazy { ContextCollector.getInstance(project) }
    private val completionProvider by lazy { CompletionProvider.getInstance(project) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // UI Components
    private val providerComboBox = ComboBox(projectService.availableProviders().toTypedArray())
    private val promptComboBox = ComboBox<PromptTemplate>()
    private val statusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val messagesPanel = ChatMessagesListPanel()
    private val contextPreviewPanel = ContextPreviewPanel()
    private lateinit var inputField: SmartInputField
    private val sendButton = JButton(SpecCodingBundle.message("toolwindow.send"))
    private val clearButton = JButton("Clear")
    private var currentAssistantPanel: ChatMessagePanel? = null

    // Session state
    private val conversationHistory = mutableListOf<LlmMessage>()
    private var currentSessionId: String? = null
    private var isGenerating = false
    @Volatile
    private var _isDisposed = false

    init {
        inputField = SmartInputField(
            placeholder = SpecCodingBundle.message("toolwindow.input.placeholder"),
            onSend = { sendCurrentInput() },
            onTrigger = { trigger ->
                ApplicationManager.getApplication().executeOnPooledThread {
                    val items = completionProvider.getCompletions(trigger)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && !_isDisposed) {
                            inputField.showCompletions(items)
                        }
                    }
                }
            },
            onTriggerDismiss = { inputField.completionPopup.hide() },
            onCompletionSelect = { item ->
                item.contextItem?.let { contextPreviewPanel.addItem(it) }
            },
        )

        border = JBUI.Borders.empty(12)
        setupUI()
        subscribeToHistoryOpenEvents()
        loadPromptTemplatesAsync()
        addSystemMessage(SpecCodingBundle.message("toolwindow.system.intro"))
        restoreWindowStateIfNeeded()
    }

    private fun restoreWindowStateIfNeeded() {
        val snapshot = windowStateStore.snapshot()
        val restoredSessionId = snapshot.activeSessionId ?: return
        loadSession(restoredSessionId)
    }

    private fun subscribeToHistoryOpenEvents() {
        project.messageBus.connect(this).subscribe(
            HistorySessionOpenListener.TOPIC,
            object : HistorySessionOpenListener {
                override fun onSessionOpenRequested(sessionId: String) {
                    loadSession(sessionId)
                }
            },
        )
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

        // Input area with context preview
        val inputRow = JPanel(BorderLayout())
        inputRow.isOpaque = false
        val inputScroll = JScrollPane(inputField)
        inputScroll.border = JBUI.Borders.empty()
        inputRow.add(inputScroll, BorderLayout.CENTER)
        inputRow.add(sendButton, BorderLayout.EAST)

        sendButton.addActionListener { sendCurrentInput() }

        val bottomPanel = JPanel()
        bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
        bottomPanel.isOpaque = false
        bottomPanel.border = JBUI.Borders.emptyTop(8)
        bottomPanel.add(contextPreviewPanel)
        bottomPanel.add(inputRow)

        // Layout
        val content = JPanel(BorderLayout())
        content.isOpaque = false
        content.add(headerPanel, BorderLayout.NORTH)
        content.add(conversationScrollPane, BorderLayout.CENTER)
        content.add(bottomPanel, BorderLayout.SOUTH)

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
        val sessionId = ensureActiveSession(input, providerId) ?: return

        // Collect context: explicit items from preview + auto-collected editor context
        val explicitItems = loadExplicitItemContents(contextPreviewPanel.getItems())
        val contextSnapshot = contextCollector.collectForItems(explicitItems)
        contextPreviewPanel.clear()

        // Add user message to history
        val userMessage = LlmMessage(LlmRole.USER, input)
        conversationHistory.add(userMessage)
        persistMessage(sessionId, ConversationRole.USER, input)

        appendUserMessage(input)
        inputField.text = ""

        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        scope.launch {
            try {
                val assistantContent = StringBuilder()

                projectService.chat(
                    providerId = providerId,
                    userInput = input,
                    contextSnapshot = contextSnapshot,
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

                // Add assistant message to history
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                conversationHistory.add(assistantMessage)
                persistMessage(sessionId, ConversationRole.ASSISTANT, assistantMessage.content)

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
        val providerId = providerComboBox.selectedItem as? String
        val sessionId = ensureActiveSession(command, providerId)

        inputField.text = ""
        appendUserMessage(command)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, command)
        }

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
                            if (sessionId != null) {
                                persistMessage(sessionId, ConversationRole.ASSISTANT, result.output)
                            }
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
        currentSessionId = null
        windowStateStore.updateActiveSessionId(null)
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        currentAssistantPanel = null
        addSystemMessage(SpecCodingBundle.message("toolwindow.system.cleared"))
    }

    private fun loadSession(sessionId: String) {
        if (sessionId.isBlank() || project.isDisposed || _isDisposed) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val session = sessionManager.getSession(sessionId)
            val messages = sessionManager.listMessages(sessionId, limit = 1000)

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                if (session == null) {
                    windowStateStore.updateActiveSessionId(null)
                    addErrorMessage(SpecCodingBundle.message("toolwindow.error.session.notFound", sessionId))
                    return@invokeLater
                }

                restoreSessionMessages(messages)
                currentSessionId = session.id
                windowStateStore.updateActiveSessionId(session.id)
                statusLabel.text = SpecCodingBundle.message("toolwindow.status.session.loaded", session.title)
            }
        }
    }

    private fun restoreSessionMessages(messages: List<com.eacape.speccodingplugin.session.ConversationMessage>) {
        conversationHistory.clear()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        currentAssistantPanel = null

        for (message in messages) {
            when (message.role) {
                ConversationRole.USER -> {
                    appendUserMessage(message.content)
                    conversationHistory.add(LlmMessage(LlmRole.USER, message.content))
                }

                ConversationRole.ASSISTANT -> {
                    val panel = ChatMessagePanel(
                        ChatMessagePanel.MessageRole.ASSISTANT,
                        message.content,
                        onDelete = ::handleDeleteMessage,
                        onRegenerate = ::handleRegenerateMessage,
                    )
                    panel.finishMessage()
                    messagesPanel.addMessage(panel)
                    conversationHistory.add(LlmMessage(LlmRole.ASSISTANT, message.content))
                }

                ConversationRole.SYSTEM -> {
                    addSystemMessage(message.content)
                    conversationHistory.add(LlmMessage(LlmRole.SYSTEM, message.content))
                }

                ConversationRole.TOOL -> {
                    val panel = ChatMessagePanel(
                        ChatMessagePanel.MessageRole.SYSTEM,
                        "[Tool] ${message.content}",
                    )
                    panel.finishMessage()
                    messagesPanel.addMessage(panel)
                }
            }
        }

        if (messages.isEmpty()) {
            addSystemMessage(SpecCodingBundle.message("toolwindow.system.emptySessionLoaded"))
        }
        messagesPanel.scrollToBottom()
    }

    private fun ensureActiveSession(firstUserInput: String, providerId: String?): String? {
        currentSessionId?.let { return it }

        val title = firstUserInput.lines()
            .firstOrNull()
            .orEmpty()
            .trim()
            .ifBlank { "New Session" }
            .take(80)

        val created = sessionManager.createSession(
            title = title,
            modelProvider = providerId,
        )

        return created.fold(
            onSuccess = { session ->
                currentSessionId = session.id
                windowStateStore.updateActiveSessionId(session.id)
                session.id
            },
            onFailure = { error ->
                logger.warn("Failed to create session", error)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !_isDisposed) {
                        addErrorMessage(
                            SpecCodingBundle.message(
                                "toolwindow.error.session.createFailed",
                                error.message ?: "unknown",
                            )
                        )
                    }
                }
                null
            },
        )
    }

    private fun persistMessage(sessionId: String, role: ConversationRole, content: String) {
        val result = sessionManager.addMessage(
            sessionId = sessionId,
            role = role,
            content = content,
        )
        if (result.isFailure) {
            logger.warn("Failed to persist message for session: $sessionId", result.exceptionOrNull())
        }
    }

    private fun loadExplicitItemContents(items: List<ContextItem>): List<ContextItem> {
        return items.map { item ->
            if (item.content.isNotBlank() || item.filePath == null) {
                return@map item
            }
            val vf = LocalFileSystem.getInstance().findFileByPath(item.filePath)
                ?: return@map item
            val content = ReadAction.compute<String, Throwable> {
                String(vf.contentsToByteArray(), Charsets.UTF_8)
            }
            item.copy(content = content, tokenEstimate = content.length / 4)
        }
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
                currentSessionId?.let { sessionId ->
                    persistMessage(sessionId, ConversationRole.ASSISTANT, assistantMessage.content)
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
