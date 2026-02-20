package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextCollector
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.skill.SkillExecutor
import com.eacape.speccodingplugin.skill.SkillContext
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecGenerationProgress
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.ui.chat.ChatMessagePanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagesListPanel
import com.eacape.speccodingplugin.ui.chat.SpecWorkflowResponseBuilder
import com.eacape.speccodingplugin.ui.chat.TraceEventMetadataCodec
import com.eacape.speccodingplugin.ui.completion.CompletionProvider
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.input.ContextPreviewPanel
import com.eacape.speccodingplugin.ui.input.SmartInputField
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.window.WindowSessionIsolationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer

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
    private val sessionIsolationService = WindowSessionIsolationService.getInstance(project)
    private val modeManager = OperationModeManager.getInstance(project)
    private val skillExecutor: SkillExecutor = SkillExecutor.getInstance(project)
    private val specEngine = SpecEngine.getInstance(project)
    private val contextCollector by lazy { ContextCollector.getInstance(project) }
    private val completionProvider by lazy { CompletionProvider.getInstance(project) }
    private val operationModeSelector = OperationModeSelector(project)
    private val llmRouter = LlmRouter.getInstance()
    private val modelRegistry = ModelRegistry.getInstance()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryListener: () -> Unit = {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            refreshProviderCombo(preserveSelection = true)
        }
    }

    // UI Components
    private val providerLabel = JBLabel(SpecCodingBundle.message("toolwindow.provider.label"))
    private val modelLabel = JBLabel(SpecCodingBundle.message("toolwindow.model.label"))
    private val promptLabel = JBLabel(SpecCodingBundle.message("toolwindow.prompt.label"))
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private val promptComboBox = ComboBox<PromptTemplate>()
    private val statusLabel = JBLabel()
    private val messagesPanel = ChatMessagesListPanel()
    private val contextPreviewPanel = ContextPreviewPanel(project)
    private val composerHintLabel = JBLabel(SpecCodingBundle.message("toolwindow.composer.hint"))
    private lateinit var inputField: SmartInputField
    private val specStageComboBox = ComboBox(SpecStageOption.entries.toTypedArray())
    private val sendButton = JButton()
    private val clearButton = JButton()
    private var currentAssistantPanel: ChatMessagePanel? = null
    private var statusAutoHideTimer: Timer? = null

    // Session state
    private val conversationHistory = mutableListOf<LlmMessage>()
    private var currentSessionId: String? = null
    private var isGenerating = false
    private var isRestoringSession = false
    private var activeSpecWorkflowId: String? = null
    @Volatile
    private var _isDisposed = false

    private enum class SpecStageOption(
        val stageKey: String,
        val messageKey: String,
    ) {
        REQUIREMENTS("requirements", "toolwindow.spec.quick.requirements"),
        DESIGN("design", "toolwindow.spec.quick.design"),
        TASKS("tasks", "toolwindow.spec.quick.tasks"),
    }

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
        CliDiscoveryService.getInstance().addDiscoveryListener(discoveryListener)
        subscribeToHistoryOpenEvents()
        subscribeToLocaleEvents()
        loadPromptTemplatesAsync()
        addSystemMessage(SpecCodingBundle.message("toolwindow.system.intro"))
        restoreWindowStateIfNeeded()
    }

    private fun restoreWindowStateIfNeeded() {
        val restoredSessionId = sessionIsolationService.restorePersistedSessionId() ?: return
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

    private fun subscribeToLocaleEvents() {
        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) return@invokeLater
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun refreshLocalizedTexts() {
        providerLabel.text = SpecCodingBundle.message("toolwindow.provider.label")
        modelLabel.text = SpecCodingBundle.message("toolwindow.model.label")
        promptLabel.text = SpecCodingBundle.message("toolwindow.prompt.label")
        composerHintLabel.text = SpecCodingBundle.message("toolwindow.composer.hint")
        refreshActionButtonTexts()
        specStageComboBox.toolTipText = SpecCodingBundle.message("toolwindow.spec.stage.tooltip")
        providerComboBox.repaint()
        modelComboBox.repaint()
        promptComboBox.repaint()
        specStageComboBox.repaint()
        updateComboTooltips()
        if (isGenerating) {
            showStatus(SpecCodingBundle.message("toolwindow.status.generating"))
        } else if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
        }
    }

    private fun setupUI() {
        // Model and prompt setup
        providerComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayName(value)
        }
        modelComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = toUiLowercase(value?.name ?: "")
        }
        providerComboBox.addActionListener {
            refreshModelCombo()
            updateComboTooltips()
        }
        modelComboBox.addActionListener { updateComboTooltips() }
        refreshProviderCombo(preserveSelection = false)

        promptComboBox.isEnabled = false
        promptComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create<PromptTemplate> { label, value, _ ->
            label.text = toUiLowercase(value?.name ?: "")
        }
        promptComboBox.addActionListener {
            val selected = promptComboBox.selectedItem as? PromptTemplate ?: return@addActionListener
            updateComboTooltips()
            ApplicationManager.getApplication().executeOnPooledThread {
                projectService.switchActivePrompt(selected.id)
            }
        }
        specStageComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create<SpecStageOption> { label, value, _ ->
            label.text = if (value == null) "" else SpecCodingBundle.message(value.messageKey)
        }
        specStageComboBox.selectedItem = SpecStageOption.TASKS
        specStageComboBox.toolTipText = SpecCodingBundle.message("toolwindow.spec.stage.tooltip")
        configureCompactCombo(specStageComboBox, 72)
        configureCompactCombo(providerComboBox, 86)
        configureCompactCombo(modelComboBox, 140)
        configureCompactCombo(promptComboBox, 138)
        providerLabel.font = JBUI.Fonts.smallFont()
        modelLabel.font = JBUI.Fonts.smallFont()
        promptLabel.font = JBUI.Fonts.smallFont()
        providerLabel.foreground = JBColor.GRAY
        modelLabel.foreground = JBColor.GRAY
        promptLabel.foreground = JBColor.GRAY
        composerHintLabel.foreground = JBColor.GRAY
        composerHintLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = JBColor.GRAY
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.isVisible = false
        configureActionButtons()
        updateComboTooltips()

        clearButton.addActionListener { clearConversation() }
        sendButton.addActionListener { sendCurrentInput() }

        // Conversation area fills the top
        val conversationScrollPane = messagesPanel.getScrollPane()
        conversationScrollPane.border = JBUI.Borders.empty()
        messagesPanel.isOpaque = true
        messagesPanel.background = JBColor(
            java.awt.Color(247, 248, 250),
            java.awt.Color(34, 36, 39),
        )
        conversationScrollPane.viewport.isOpaque = true
        conversationScrollPane.viewport.background = messagesPanel.background

        // Composer area
        val inputScroll = JScrollPane(inputField)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = JBDimension(0, 92)

        val composerMetaRow = JPanel(BorderLayout())
        composerMetaRow.isOpaque = false
        composerMetaRow.border = JBUI.Borders.emptyTop(5)
        composerMetaRow.add(composerHintLabel, BorderLayout.WEST)
        val stagePanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        stagePanel.isOpaque = false
        stagePanel.add(specStageComboBox)
        composerMetaRow.add(stagePanel, BorderLayout.EAST)

        val controlsLeftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        controlsLeftPanel.isOpaque = false
        controlsLeftPanel.add(providerLabel)
        controlsLeftPanel.add(providerComboBox)
        controlsLeftPanel.add(modelLabel)
        controlsLeftPanel.add(modelComboBox)
        controlsLeftPanel.add(promptLabel)
        controlsLeftPanel.add(promptComboBox)
        controlsLeftPanel.add(operationModeSelector)

        val controlsRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
        controlsRightPanel.isOpaque = false
        controlsRightPanel.add(statusLabel)
        controlsRightPanel.add(clearButton)
        controlsRightPanel.add(sendButton)

        val controlsRow = JPanel(BorderLayout())
        controlsRow.isOpaque = false
        controlsRow.border = JBUI.Borders.emptyTop(7)
        controlsRow.add(controlsLeftPanel, BorderLayout.CENTER)
        controlsRow.add(controlsRightPanel, BorderLayout.EAST)

        val composerContainer = JPanel(BorderLayout())
        composerContainer.isOpaque = true
        composerContainer.background = JBColor(
            java.awt.Color(252, 252, 253),
            java.awt.Color(43, 45, 49),
        )
        composerContainer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                JBColor(
                    java.awt.Color(224, 228, 234),
                    java.awt.Color(69, 73, 80),
                ),
                1,
                0,
                0,
                0,
            ),
            JBUI.Borders.empty(8, 10),
        )
        composerContainer.add(inputScroll, BorderLayout.CENTER)
        composerContainer.add(composerMetaRow, BorderLayout.SOUTH)

        val bottomPanel = JPanel()
        bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
        bottomPanel.isOpaque = false
        bottomPanel.border = JBUI.Borders.emptyTop(8)
        bottomPanel.add(contextPreviewPanel)
        bottomPanel.add(composerContainer)
        bottomPanel.add(controlsRow)

        // Layout
        val content = JPanel(BorderLayout())
        content.isOpaque = false
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
                updateComboTooltips()
            }
        }
    }

    private fun sendCurrentInput() {
        val rawInput = inputField.text.trim()
        if (rawInput.isBlank() || isGenerating || project.isDisposed || _isDisposed) {
            return
        }
        if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
            return
        }

        // Check for slash commands
        if (rawInput.startsWith("/")) {
            handleSlashCommand(rawInput)
            return
        }

        val stage = specStageComboBox.selectedItem as? SpecStageOption ?: SpecStageOption.TASKS
        val stageLabel = SpecCodingBundle.message(stage.messageKey)
        val visibleInput = "[$stageLabel] $rawInput"
        val stagedPrompt = buildStageAwarePrompt(stage, rawInput)

        val providerId = providerComboBox.selectedItem as? String
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val sessionId = ensureActiveSession(visibleInput, providerId)
        val explicitItems = loadExplicitItemContents(contextPreviewPanel.getItems())
        val contextSnapshot = contextCollector.collectForItems(explicitItems)
        contextPreviewPanel.clear()

        // Add user message to history
        val userMessage = LlmMessage(LlmRole.USER, stagedPrompt)
        appendToConversationHistory(userMessage)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, visibleInput)
        }

        appendUserMessage(visibleInput)
        inputField.text = ""

        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        scope.launch {
            try {
                val assistantContent = StringBuilder()
                val pendingDelta = StringBuilder()
                val pendingEvents = mutableListOf<ChatStreamEvent>()
                val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
                var pendingChunks = 0
                var lastFlushAtNanos = System.nanoTime()

                fun flushPending(force: Boolean = false) {
                    val now = System.nanoTime()
                    val dueByTime = now - lastFlushAtNanos >= STREAM_BATCH_INTERVAL_NANOS
                    val shouldFlush = force ||
                        dueByTime ||
                        pendingChunks >= STREAM_BATCH_CHUNK_COUNT ||
                        pendingDelta.length >= STREAM_BATCH_CHAR_COUNT ||
                        pendingDelta.contains('\n') ||
                        pendingEvents.isNotEmpty()
                    if (!shouldFlush) return
                    if (pendingDelta.isEmpty() && pendingEvents.isEmpty()) {
                        pendingChunks = 0
                        return
                    }

                    val delta = pendingDelta.toString()
                    val events = pendingEvents.toList()
                    pendingDelta.setLength(0)
                    pendingEvents.clear()
                    pendingChunks = 0
                    lastFlushAtNanos = now

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        val panel = currentAssistantPanel ?: return@invokeLater
                        val shouldAutoScroll = messagesPanel.isNearBottom(STREAM_AUTO_SCROLL_THRESHOLD_PX)
                        panel.appendStreamContent(delta, events)
                        if (shouldAutoScroll) {
                            messagesPanel.scrollToBottom()
                        }
                    }
                }

                projectService.chat(
                    providerId = providerId,
                    userInput = stagedPrompt,
                    modelId = modelId,
                    contextSnapshot = contextSnapshot,
                    conversationHistory = conversationHistory.toList(),
                ) { chunk ->
                    if (chunk.delta.isNotEmpty()) {
                        assistantContent.append(chunk.delta)
                        pendingDelta.append(chunk.delta)
                    }
                    chunk.event
                        ?.let(::sanitizeStreamEvent)
                        ?.let {
                            pendingEvents += it
                            streamedTraceEvents += it
                        }
                    pendingChunks += 1

                    if (chunk.isLast) {
                        flushPending(force = true)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            currentAssistantPanel?.finishMessage()
                        }
                    } else {
                        flushPending(force = false)
                    }
                }

                // Add assistant message to history
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                appendToConversationHistory(assistantMessage)
                if (sessionId != null) {
                    persistMessage(
                        sessionId = sessionId,
                        role = ConversationRole.ASSISTANT,
                        content = assistantMessage.content,
                        metadataJson = TraceEventMetadataCodec.encode(streamedTraceEvents),
                    )
                }

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

    private fun buildStageAwarePrompt(stage: SpecStageOption, input: String): String {
        return buildString {
            appendLine("Spec stage: ${stage.stageKey}")
            appendLine("User instruction:")
            appendLine(input.trim())
            appendLine()
            append("Please generate or refine the `${stage.stageKey}` phase content first.")
        }
    }

    private fun handleSlashCommand(command: String) {
        val trimmedCommand = command.trim()
        if (trimmedCommand == "/skills") {
            showAvailableSkills()
            return
        }
        if (trimmedCommand.startsWith("/pipeline")) {
            handlePipelineCommand(trimmedCommand)
            return
        }
        if (handleModeCommand(trimmedCommand)) {
            return
        }
        if (trimmedCommand.startsWith("/spec")) {
            handleSpecCommand(trimmedCommand)
            return
        }

        val operation = mapSlashCommandToOperation(trimmedCommand)
        if (operation != null && !checkOperationPermission(operation, trimmedCommand)) {
            return
        }

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
                val autoContext = contextCollector.collectContext()
                val selectedCode = autoContext.items
                    .firstOrNull { it.type == ContextType.SELECTED_CODE }
                    ?.content
                val currentFile = autoContext.items
                    .firstOrNull { it.type == ContextType.CURRENT_FILE }
                    ?.filePath

                val context = SkillContext(
                    selectedCode = selectedCode,
                    currentFile = currentFile,
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
                    addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
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

    private fun handleSpecCommand(command: String) {
        val providerId = providerComboBox.selectedItem as? String
        val sessionId = ensureActiveSession(command, providerId)

        inputField.text = ""
        appendUserMessage(command)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, command)
        }

        setSendingState(true)

        scope.launch(Dispatchers.IO) {
            try {
                val output = executeSpecCommand(command)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    val panel = addAssistantMessage()
                    panel.appendContent(output)
                    panel.finishMessage()
                    if (sessionId != null) {
                        persistMessage(sessionId, ConversationRole.ASSISTANT, output)
                    }
                    appendToConversationHistory(LlmMessage(LlmRole.ASSISTANT, output))
                }
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    addErrorMessage(
                        SpecCodingBundle.message(
                            "toolwindow.spec.command.failed",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        )
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

    private suspend fun executeSpecCommand(command: String): String {
        val args = command.removePrefix("/spec").trim()
        if (args.isBlank() || args.equals("help", ignoreCase = true)) {
            return SpecCodingBundle.message("toolwindow.spec.command.help")
        }

        val commandToken = args.substringBefore(" ").trim().lowercase()
        val commandArg = args.substringAfter(" ", "").trim()

        return when (commandToken) {
            "status" -> formatSpecStatus()
            "open" -> {
                openSpecTab()
                SpecCodingBundle.message("toolwindow.spec.command.opened")
            }
            "next" -> transitionSpecWorkflow(advance = true)
            "back" -> transitionSpecWorkflow(advance = false)
            "generate" -> {
                if (commandArg.isBlank()) {
                    SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "generate")
                } else {
                    generateSpecForActiveWorkflow(commandArg)
                }
            }
            "complete" -> completeSpecWorkflow()
            else -> createAndGenerateWorkflow(args)
        }
    }

    private suspend fun createAndGenerateWorkflow(requirementsInput: String): String {
        if (requirementsInput.isBlank()) {
            return SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "<requirements text>")
        }

        val title = requirementsInput
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
            .take(80)

        val workflow = specEngine
            .createWorkflow(title = title, description = requirementsInput)
            .getOrElse { throw it }

        activeSpecWorkflowId = workflow.id

        val generationSummary = runSpecGeneration(workflow.id, requirementsInput)
        return buildString {
            appendLine(SpecCodingBundle.message("toolwindow.spec.command.created", workflow.id))
            if (generationSummary.isNotBlank()) {
                append(generationSummary)
            }
        }.trimEnd()
    }

    private suspend fun generateSpecForActiveWorkflow(input: String): String {
        val workflow = resolveActiveSpecWorkflow()
            ?: return SpecCodingBundle.message("toolwindow.spec.command.noActive")
        return runSpecGeneration(workflow.id, input)
    }

    private suspend fun runSpecGeneration(workflowId: String, input: String): String {
        val lines = mutableListOf<String>()
        val workflow = specEngine.loadWorkflow(workflowId).getOrElse { throw it }
        val phaseName = workflow.currentPhase.displayName

        updateStatusLabel(
            SpecCodingBundle.message("toolwindow.spec.command.generating", phaseName),
        )

        specEngine.generateCurrentPhase(workflowId, input).collect { progress ->
            when (progress) {
                is SpecGenerationProgress.Started -> {
                    updateStatusLabel(
                        SpecCodingBundle.message("toolwindow.spec.command.generating", progress.phase.displayName),
                    )
                }

                is SpecGenerationProgress.Generating -> {
                    val percent = (progress.progress * 100).toInt().coerceIn(0, 100)
                    updateStatusLabel(
                        SpecCodingBundle.message(
                            "toolwindow.spec.command.generating.percent",
                            progress.phase.displayName,
                            percent,
                        )
                    )
                }

                is SpecGenerationProgress.Completed -> {
                    lines += SpecCodingBundle.message(
                        "toolwindow.spec.command.generated",
                        workflowId,
                        progress.document.phase.displayName,
                    )
                    lines += SpecCodingBundle.message("toolwindow.spec.command.validation.pass")
                }

                is SpecGenerationProgress.ValidationFailed -> {
                    lines += SpecCodingBundle.message(
                        "toolwindow.spec.command.generated",
                        workflowId,
                        progress.document.phase.displayName,
                    )
                    lines += SpecCodingBundle.message("toolwindow.spec.command.validation.fail")
                }

                is SpecGenerationProgress.Failed -> {
                    throw IllegalStateException(progress.error)
                }
            }
        }

        if (lines.isEmpty()) {
            lines += SpecCodingBundle.message("toolwindow.spec.command.generated", workflowId, phaseName)
        }
        return lines.joinToString("\n")
    }

    private fun transitionSpecWorkflow(advance: Boolean): String {
        val current = resolveActiveSpecWorkflow()
            ?: return SpecCodingBundle.message("toolwindow.spec.command.noActive")
        val updated = if (advance) {
            specEngine.proceedToNextPhase(current.id)
        } else {
            specEngine.goBackToPreviousPhase(current.id)
        }.getOrElse { throw it }

        activeSpecWorkflowId = updated.id
        val template = templateForPhase(updated.currentPhase)
        return SpecWorkflowResponseBuilder.buildPhaseTransitionResponse(
            workflowId = updated.id,
            phaseDisplayName = updated.currentPhase.displayName,
            template = template,
            advanced = advance,
            templateInserted = false,
        )
    }

    private fun completeSpecWorkflow(): String {
        val workflow = resolveActiveSpecWorkflow()
            ?: return SpecCodingBundle.message("toolwindow.spec.command.noActive")
        val completed = specEngine.completeWorkflow(workflow.id).getOrElse { throw it }
        activeSpecWorkflowId = completed.id
        return SpecCodingBundle.message("toolwindow.spec.command.completed", completed.id)
    }

    private fun formatSpecStatus(): String {
        val workflow = resolveActiveSpecWorkflow()
            ?: return SpecCodingBundle.message("toolwindow.spec.command.status.none")
        return SpecCodingBundle.message(
            "toolwindow.spec.command.status.entry",
            workflow.id,
            workflow.currentPhase.displayName,
            workflowStatusDisplayName(workflow),
            workflow.title.ifBlank { workflow.id },
        )
    }

    private fun workflowStatusDisplayName(workflow: SpecWorkflow): String {
        return when (workflow.status) {
            com.eacape.speccodingplugin.spec.WorkflowStatus.IN_PROGRESS ->
                SpecCodingBundle.message("spec.workflow.status.inProgress")
            com.eacape.speccodingplugin.spec.WorkflowStatus.PAUSED ->
                SpecCodingBundle.message("spec.workflow.status.paused")
            com.eacape.speccodingplugin.spec.WorkflowStatus.COMPLETED ->
                SpecCodingBundle.message("spec.workflow.status.completed")
            com.eacape.speccodingplugin.spec.WorkflowStatus.FAILED ->
                SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun templateForPhase(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("toolwindow.spec.template.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("toolwindow.spec.template.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("toolwindow.spec.template.tasks")
        }
    }

    private fun resolveActiveSpecWorkflow(): SpecWorkflow? {
        val explicitId = activeSpecWorkflowId
        if (!explicitId.isNullOrBlank()) {
            val workflow = specEngine.loadWorkflow(explicitId).getOrNull()
            if (workflow != null) {
                return workflow
            }
        }

        val latestId = specEngine.listWorkflows().lastOrNull() ?: return null
        val workflow = specEngine.loadWorkflow(latestId).getOrNull() ?: return null
        activeSpecWorkflowId = workflow.id
        return workflow
    }

    private fun openSpecTab() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return@invokeLater
            toolWindow.activate(null)
            val specTabTitle = SpecCodingBundle.message("spec.tab.title")
            val target = toolWindow.contentManager.contents.firstOrNull { it.displayName == specTabTitle }
            if (target != null) {
                toolWindow.contentManager.setSelectedContent(target)
            }
        }
    }

    private fun updateStatusLabel(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            showStatus(text)
        }
    }

    private fun handlePipelineCommand(command: String) {
        val operation = Operation.WRITE_FILE
        if (!checkOperationPermission(operation, command)) {
            return
        }

        val parsedStages = skillExecutor.parsePipelineStages(command)
        if (parsedStages.isNullOrEmpty()) {
            addErrorMessage(SpecCodingBundle.message("toolwindow.pipeline.invalid"))
            return
        }

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
                val autoContext = contextCollector.collectContext()
                val selectedCode = autoContext.items
                    .firstOrNull { it.type == ContextType.SELECTED_CODE }
                    ?.content
                val currentFile = autoContext.items
                    .firstOrNull { it.type == ContextType.CURRENT_FILE }
                    ?.filePath

                val context = SkillContext(
                    selectedCode = selectedCode,
                    currentFile = currentFile,
                )

                val result = skillExecutor.executePipelineFromCommand(command, context)

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
                    addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
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

    private fun showAvailableSkills() {
        val skills = skillExecutor.listAvailableSkills()
        val lines = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.skill.available.header", skills.size))
            skills.forEach { skill ->
                appendLine(
                    SpecCodingBundle.message(
                        "toolwindow.skill.available.item",
                        skill.slashCommand,
                        skill.description,
                    )
                )
            }
        }.trimEnd()
        addSystemMessage(lines)
    }

    private fun handleModeCommand(command: String): Boolean {
        if (!command.startsWith("/mode")) {
            return false
        }

        val tokens = command.removePrefix("/").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size == 1) {
            addSystemMessage(
                SpecCodingBundle.message(
                    "toolwindow.mode.current",
                    modeManager.getCurrentMode().displayName,
                )
            )
            return true
        }

        val requestedMode = tokens[1].uppercase()
        val mode = OperationMode.entries.firstOrNull { it.name == requestedMode }
        if (mode == null) {
            addErrorMessage(SpecCodingBundle.message("toolwindow.mode.invalid", tokens[1]))
            return true
        }

        modeManager.switchMode(mode)
        operationModeSelector.setSelectedMode(mode)
        addSystemMessage(SpecCodingBundle.message("toolwindow.mode.switched", mode.displayName))
        return true
    }

    private fun mapSlashCommandToOperation(command: String): Operation? {
        val normalized = command
            .removePrefix("/")
            .trim()
            .split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            ?.lowercase()
            ?: return null

        return when (normalized) {
            "review", "explain", "skills" -> Operation.ANALYZE_CODE
            "refactor", "test", "fix", "pipeline" -> Operation.WRITE_FILE
            else -> null
        }
    }

    private fun checkOperationPermission(operation: Operation, command: String): Boolean {
        val request = OperationRequest(
            operation = operation,
            description = "Slash command: $command",
            details = mapOf("command" to command),
        )

        return when (val result = modeManager.checkOperation(request)) {
            is OperationResult.Allowed -> true
            is OperationResult.Denied -> {
                addErrorMessage(
                    SpecCodingBundle.message(
                        "toolwindow.mode.operation.denied",
                        modeManager.getCurrentMode().displayName,
                        result.reason,
                    )
                )
                false
            }

            is OperationResult.RequiresConfirmation -> {
                val confirmed = command.split(Regex("\\s+")).any { it.equals("--confirm", ignoreCase = true) }
                if (confirmed) {
                    addSystemMessage(
                        SpecCodingBundle.message(
                            "toolwindow.mode.confirmation.accepted",
                            operation.name,
                        )
                    )
                    true
                } else {
                    addErrorMessage(
                        SpecCodingBundle.message(
                            "toolwindow.mode.confirmation.required",
                            modeManager.getCurrentMode().displayName,
                            operation.name,
                        )
                    )
                    false
                }
            }
        }
    }

    private fun refreshProviderCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val previousSelection = providerComboBox.selectedItem as? String
        val providers = (llmRouter.availableUiProviders().ifEmpty { llmRouter.availableProviders() })
            .ifEmpty { listOf(MockLlmProvider.ID) }

        providerComboBox.removeAllItems()
        providers.forEach { providerComboBox.addItem(it) }

        val preferred = when {
            preserveSelection && !previousSelection.isNullOrBlank() -> previousSelection
            settings.defaultProvider.isNotBlank() -> settings.defaultProvider
            else -> llmRouter.defaultProviderId()
        }
        val selected = providers.firstOrNull { it == preferred } ?: providers.firstOrNull()
        providerComboBox.selectedItem = selected

        refreshModelCombo()
    }

    private fun refreshModelCombo() {
        val selectedProvider = providerComboBox.selectedItem as? String
        modelComboBox.removeAllItems()
        if (selectedProvider.isNullOrBlank()) {
            return
        }

        val settings = SpecCodingSettingsState.getInstance()
        val models = modelRegistry.getModelsForProvider(selectedProvider).toMutableList()
        val savedModelId = settings.selectedCliModel.trim()
        if (models.isEmpty() && savedModelId.isNotBlank()) {
            models += ModelInfo(
                id = savedModelId,
                name = savedModelId,
                provider = selectedProvider,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }

        models.forEach { modelComboBox.addItem(it) }

        val selected = models.firstOrNull { it.id == savedModelId } ?: models.firstOrNull()
        if (selected != null) {
            modelComboBox.selectedItem = selected
        }
    }

    private fun clearConversation() {
        conversationHistory.clear()
        currentSessionId = null
        activeSpecWorkflowId = null
        sessionIsolationService.clearActiveSession()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        currentAssistantPanel = null
        addSystemMessage(SpecCodingBundle.message("toolwindow.system.cleared"))
    }

    private fun loadSession(sessionId: String) {
        if (sessionId.isBlank() || project.isDisposed || _isDisposed) {
            return
        }
        setSessionRestoringState(true)
        showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))

        scope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                val session = sessionManager.getSession(sessionId)
                val messages = sessionManager
                    .listMessages(sessionId, limit = SESSION_LOAD_FETCH_LIMIT)
                    .takeLast(MAX_RESTORED_MESSAGES)
                session to messages
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                if (loaded.isFailure) {
                    setSessionRestoringState(false)
                    addErrorMessage(
                        loaded.exceptionOrNull()?.message ?: SpecCodingBundle.message("toolwindow.error.unknown"),
                    )
                    return@invokeLater
                }

                val (session, messages) = loaded.getOrThrow()
                if (session == null) {
                    currentSessionId = null
                    sessionIsolationService.clearActiveSession()
                    addErrorMessage(SpecCodingBundle.message("toolwindow.error.session.notFound", sessionId))
                    setSessionRestoringState(false)
                    return@invokeLater
                }

                restoreSessionMessages(messages)
                currentSessionId = session.id
                sessionIsolationService.activateSession(session.id)
                showStatus(
                    text = SpecCodingBundle.message("toolwindow.status.session.loaded.short"),
                    tooltip = SpecCodingBundle.message("toolwindow.status.session.loaded", session.title),
                    autoHideMillis = STATUS_SESSION_LOADED_AUTO_HIDE_MILLIS,
                )
                setSessionRestoringState(false)
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
                    appendToConversationHistory(LlmMessage(LlmRole.USER, message.content))
                }

                ConversationRole.ASSISTANT -> {
                    val panel = ChatMessagePanel(
                        ChatMessagePanel.MessageRole.ASSISTANT,
                        message.content,
                        onDelete = ::handleDeleteMessage,
                        onRegenerate = ::handleRegenerateMessage,
                        onContinue = ::handleContinueMessage,
                        onWorkflowFileOpen = ::handleWorkflowFileOpen,
                        onWorkflowCommandInsert = ::handleWorkflowCommandInsert,
                    )
                    val restoredTraceEvents = TraceEventMetadataCodec.decode(message.metadataJson)
                        .mapNotNull(::sanitizeStreamEvent)
                    if (restoredTraceEvents.isNotEmpty()) {
                        panel.appendStreamContent(text = "", events = restoredTraceEvents)
                    }
                    panel.finishMessage()
                    messagesPanel.addMessage(panel)
                    appendToConversationHistory(LlmMessage(LlmRole.ASSISTANT, message.content))
                }

                ConversationRole.SYSTEM -> {
                    addSystemMessage(message.content)
                    appendToConversationHistory(LlmMessage(LlmRole.SYSTEM, message.content))
                }

                ConversationRole.TOOL -> {
                    val panel = ChatMessagePanel(
                        ChatMessagePanel.MessageRole.SYSTEM,
                        SpecCodingBundle.message("toolwindow.message.tool.entry", message.content),
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
        sessionIsolationService.activeSessionId()?.let {
            currentSessionId = it
            return it
        }

        val title = firstUserInput.lines()
            .firstOrNull()
            .orEmpty()
            .trim()
            .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
            .take(80)

        val created = sessionManager.createSession(
            title = title,
            modelProvider = providerId,
        )

        return created.fold(
            onSuccess = { session ->
                currentSessionId = session.id
                sessionIsolationService.activateSession(session.id)
                session.id
            },
            onFailure = { error ->
                logger.warn("Failed to create session", error)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !_isDisposed) {
                        addErrorMessage(
                            SpecCodingBundle.message(
                                "toolwindow.error.session.createFailed.fallback",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            )
                        )
                    }
                }
                null
            },
        )
    }

    private fun persistMessage(
        sessionId: String,
        role: ConversationRole,
        content: String,
        metadataJson: String? = null,
    ) {
        val result = sessionManager.addMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            metadataJson = metadataJson,
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
            onContinue = ::handleContinueMessage,
            onWorkflowFileOpen = ::handleWorkflowFileOpen,
            onWorkflowCommandInsert = ::handleWorkflowCommandInsert,
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
        val normalized = sanitizeDisplayText(content, dropGarbledLines = true)
            .ifBlank { SpecCodingBundle.message("toolwindow.error.unknown") }
        val panel = ChatMessagePanel(ChatMessagePanel.MessageRole.ERROR, normalized)
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun sanitizeStreamEvent(event: ChatStreamEvent): ChatStreamEvent? {
        val normalizedDetail = sanitizeDisplayText(event.detail, dropGarbledLines = true)
        if (normalizedDetail.isBlank()) return null
        return if (normalizedDetail == event.detail) {
            event
        } else {
            event.copy(detail = normalizedDetail)
        }
    }

    private fun sanitizeDisplayText(content: String, dropGarbledLines: Boolean): String {
        if (content.isBlank()) return ""
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\uFFFD', ' ')
            .replace(UI_CONTROL_CHAR_REGEX, "")
        val lines = normalized.lineSequence()
            .map { it.trimEnd() }
            .filter { line -> line.isNotBlank() || !dropGarbledLines }
            .filter { line -> !dropGarbledLines || !looksLikeGarbledLine(line) }
            .filter { line -> !dropGarbledLines || !looksLikePlaceholderLine(line) }
            .toList()
        return lines.joinToString("\n").trim()
    }

    private fun looksLikeGarbledLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return false
        if (UI_CJK_REGEX.containsMatchIn(normalized)) return false
        if (UI_BOX_DRAWING_REGEX.containsMatchIn(normalized)) return true
        val suspiciousCount = UI_SUSPICIOUS_CHAR_REGEX.findAll(normalized).count()
        if (suspiciousCount < UI_GARBLED_MIN_COUNT) return false
        val ratio = suspiciousCount.toDouble() / normalized.length.toDouble().coerceAtLeast(1.0)
        return ratio >= UI_GARBLED_MIN_RATIO
    }

    private fun looksLikePlaceholderLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return true
        if (normalized.length > UI_PLACEHOLDER_MAX_LENGTH) return false
        return UI_PLACEHOLDER_LINE_REGEX.matches(normalized)
    }

    private fun setSendingState(sending: Boolean) {
        isGenerating = sending
        refreshSendButtonState()
        inputField.isEnabled = true
        providerComboBox.isEnabled = true
        modelComboBox.isEnabled = true
        promptComboBox.isEnabled = promptComboBox.itemCount > 0
        specStageComboBox.isEnabled = true
        clearButton.isEnabled = true
        if (sending) {
            showStatus(SpecCodingBundle.message("toolwindow.status.generating"))
        } else if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
        } else {
            hideStatus()
        }
    }

    private fun setSessionRestoringState(restoring: Boolean) {
        isRestoringSession = restoring
        refreshSendButtonState()
    }

    private fun refreshSendButtonState() {
        sendButton.isEnabled = !isGenerating && !isRestoringSession
    }

    private fun showStatus(
        text: String,
        tooltip: String? = null,
        autoHideMillis: Int? = null,
    ) {
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        statusLabel.text = text
        statusLabel.toolTipText = tooltip
        statusLabel.isVisible = text.isNotBlank()
        if (!statusLabel.isVisible) return
        if (autoHideMillis == null || autoHideMillis <= 0) return

        val timer = Timer(autoHideMillis) {
            if (!isGenerating && !isRestoringSession) {
                hideStatus()
            }
        }
        timer.isRepeats = false
        statusAutoHideTimer = timer
        timer.start()
    }

    private fun hideStatus() {
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        statusLabel.text = ""
        statusLabel.toolTipText = null
        statusLabel.isVisible = false
    }

    private fun refreshActionButtonTexts() {
        clearButton.toolTipText = SpecCodingBundle.message("toolwindow.clear")
        sendButton.toolTipText = SpecCodingBundle.message("toolwindow.send")
        clearButton.accessibleContext.accessibleName = clearButton.toolTipText
        sendButton.accessibleContext.accessibleName = sendButton.toolTipText
    }

    private fun configureActionButtons() {
        clearButton.icon = AllIcons.Actions.GC
        sendButton.icon = AllIcons.Actions.Execute
        clearButton.text = ""
        sendButton.text = ""
        clearButton.isFocusable = false
        sendButton.isFocusable = false
        clearButton.isFocusPainted = false
        sendButton.isFocusPainted = false
        clearButton.margin = JBUI.emptyInsets()
        sendButton.margin = JBUI.emptyInsets()
        clearButton.preferredSize = JBDimension(30, 28)
        sendButton.preferredSize = JBDimension(30, 28)
        clearButton.minimumSize = JBDimension(30, 28)
        sendButton.minimumSize = JBDimension(30, 28)
        clearButton.putClientProperty("JButton.buttonType", "toolbar")
        sendButton.putClientProperty("JButton.buttonType", "toolbar")
        refreshActionButtonTexts()
    }

    private fun configureCompactCombo(comboBox: ComboBox<*>, width: Int) {
        val size = JBDimension(width, 28)
        comboBox.preferredSize = size
        comboBox.minimumSize = size
        comboBox.maximumSize = size
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = JBColor(Color(248, 250, 252), Color(46, 50, 56))
        comboBox.border = JBUI.Borders.empty(0)
        comboBox.isOpaque = false
        comboBox.font = JBUI.Fonts.smallFont()
    }

    private fun updateComboTooltips() {
        providerComboBox.toolTipText = providerDisplayName(providerComboBox.selectedItem as? String)
        modelComboBox.toolTipText = (modelComboBox.selectedItem as? ModelInfo)?.name
        promptComboBox.toolTipText = (promptComboBox.selectedItem as? PromptTemplate)?.name
    }

    private fun providerDisplayName(providerId: String?): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> "claude"
            CodexCliLlmProvider.ID -> "codex"
            MockLlmProvider.ID -> toUiLowercase(SpecCodingBundle.message("toolwindow.model.mock"))
            else -> toUiLowercase(providerId.orEmpty())
        }
    }

    private fun toUiLowercase(value: String): String = value.lowercase(Locale.ROOT)

    private fun handleContinueMessage(panel: ChatMessagePanel) {
        if (isGenerating || isRestoringSession || project.isDisposed || _isDisposed) return
        val focus = resolveContinueFocus(panel)
        val continuePrompt = if (!focus.isNullOrBlank()) {
            SpecCodingBundle.message("toolwindow.continue.dynamicPrompt", focus)
        } else {
            SpecCodingBundle.message("toolwindow.continue.defaultPrompt")
        }
        inputField.text = continuePrompt
        sendCurrentInput()
    }

    private fun resolveContinueFocus(targetPanel: ChatMessagePanel): String? {
        val allMessages = messagesPanel.getAllMessages()
        val targetIndex = allMessages.indexOf(targetPanel)
        if (targetIndex < 0) return null

        val fromUser = resolveUserContinueFocus(allMessages, targetIndex)

        val fromAssistant = extractAssistantContinueFocus(targetPanel.getContent())
            ?.let(::unwrapGeneratedContinuePrompt)
            ?.let(::sanitizeContinueFocus)
            ?.takeUnless(::isGenericContinueInstruction)
            ?.takeUnless(::isCodeLikeContinueFocus)

        return fromUser ?: fromAssistant
    }

    private fun resolveUserContinueFocus(
        allMessages: List<ChatMessagePanel>,
        beforeIndexExclusive: Int,
    ): String? {
        for (index in beforeIndexExclusive - 1 downTo 0) {
            val candidate = allMessages[index]
            if (candidate.role != ChatMessagePanel.MessageRole.USER) continue

            val focus = candidate.getContent()
                .let(::stripStagePrefix)
                .let(::unwrapGeneratedContinuePrompt)
                .let(::stripStagePrefix)
                .let(::sanitizeContinueFocus)
                ?: continue
            if (isGenericContinueInstruction(focus)) continue
            return focus
        }
        return null
    }

    private fun extractAssistantContinueFocus(content: String): String? {
        if (content.isBlank()) return null
        val lines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val heading = lines.firstOrNull { line ->
            line.startsWith("## ") ||
                line.startsWith("### ") ||
                line.startsWith("- ") ||
                line.startsWith("* ") ||
                CONTINUE_ORDERED_LINE_REGEX.matches(line)
        }

        return (heading ?: lines.firstOrNull())
            ?.removePrefix("## ")
            ?.removePrefix("### ")
            ?.removePrefix("- ")
            ?.removePrefix("* ")
            ?.replace(CONTINUE_ORDERED_PREFIX_REGEX, "")
            ?.trim()
    }

    private fun stripStagePrefix(content: String): String {
        return content.trim().replace(CONTINUE_STAGE_PREFIX_REGEX, "").trim()
    }

    private fun unwrapGeneratedContinuePrompt(content: String): String {
        var current = content.trim()
        repeat(CONTINUE_PROMPT_UNWRAP_MAX_DEPTH) {
            val next = extractGeneratedContinueFocus(current)?.trim()
            if (next.isNullOrBlank() || next == current) return current
            current = next
        }
        return current
    }

    private fun extractGeneratedContinueFocus(content: String): String? {
        val normalized = content.trim()
        CONTINUE_DYNAMIC_ZH_REGEX.matchEntire(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        CONTINUE_DYNAMIC_EN_REGEX.matchEntire(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        if (normalized.startsWith("请承接上一轮输出继续执行")) {
            return normalized
                .substringAfter("重点", "")
                .substringAfter("：", "")
                .substringAfter(":", "")
                .replace(Regex("""。?\s*避免重复已完成内容.*$"""), "")
                .trim()
                .ifBlank { null }
        }
        if (normalized.lowercase(Locale.ROOT).startsWith("continue from the previous result with focus")) {
            return normalized
                .substringAfter("focus", "")
                .substringAfter(":", "")
                .substringAfter("：", "")
                .replace(Regex("""\.?\s*avoid repeating completed work.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { null }
        }
        return null
    }

    private fun sanitizeContinueFocus(content: String): String? {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(CONTINUE_FOCUS_MAX_LENGTH)
            .ifBlank { null }
    }

    private fun isCodeLikeContinueFocus(content: String): Boolean {
        val normalized = content.trim()
        if (normalized.isBlank()) return true
        if (normalized.length > 64) return false
        if (normalized.contains(' ')) return false
        if (UI_CJK_REGEX.containsMatchIn(normalized)) return false
        return CONTINUE_CODE_LIKE_FOCUS_REGEX.matches(normalized)
    }

    private fun isGenericContinueInstruction(content: String): Boolean {
        val normalized = content.lowercase(Locale.ROOT)
        return normalized.contains("plan/execute/verify") ||
            normalized.contains("continue with the current") ||
            normalized.contains("continue from the previous result with focus") ||
            normalized.contains("avoid repeating completed work") ||
            normalized.contains("继续按当前") ||
            normalized.contains("继续执行下一步") ||
            normalized.contains("请承接上一轮输出继续执行")
    }

    private fun handleWorkflowCommandInsert(command: String) {
        if (inputField.text.isBlank()) {
            inputField.text = command
        } else {
            inputField.text = inputField.text.trimEnd() + "\n" + command
        }
        inputField.requestFocusInWindow()
        inputField.caretPosition = inputField.text.length
    }

    private fun handleWorkflowFileOpen(fileAction: com.eacape.speccodingplugin.ui.chat.WorkflowQuickActionParser.FileAction) {
        val basePath = project.basePath ?: return
        val normalized = fileAction.path.replace('\\', '/')
        val resolvedPath = if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:/.*"))) {
            normalized
        } else {
            "$basePath/$normalized"
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
        if (vf == null) {
            addErrorMessage(SpecCodingBundle.message("chat.workflow.action.fileNotFound", fileAction.path))
            return
        }

        val line = fileAction.line?.coerceAtLeast(1)?.minus(1) ?: 0
        OpenFileDescriptor(project, vf, line, 0).navigate(true)
    }

    private fun handleDeleteMessage(panel: ChatMessagePanel) {
        if (isGenerating) return
        messagesPanel.removeMessage(panel)
        // 同步删除对话历史中对应的消息
        rebuildConversationHistory()
    }

    private fun handleRegenerateMessage(panel: ChatMessagePanel) {
        if (isGenerating || isRestoringSession) return

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
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        scope.launch {
            try {
                val assistantContent = StringBuilder()
                val pendingDelta = StringBuilder()
                val pendingEvents = mutableListOf<ChatStreamEvent>()
                val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
                var pendingChunks = 0
                var lastFlushAtNanos = System.nanoTime()

                fun flushPending(force: Boolean = false) {
                    val now = System.nanoTime()
                    val dueByTime = now - lastFlushAtNanos >= STREAM_BATCH_INTERVAL_NANOS
                    val shouldFlush = force ||
                        dueByTime ||
                        pendingChunks >= STREAM_BATCH_CHUNK_COUNT ||
                        pendingDelta.length >= STREAM_BATCH_CHAR_COUNT ||
                        pendingDelta.contains('\n') ||
                        pendingEvents.isNotEmpty()
                    if (!shouldFlush) return
                    if (pendingDelta.isEmpty() && pendingEvents.isEmpty()) {
                        pendingChunks = 0
                        return
                    }

                    val delta = pendingDelta.toString()
                    val events = pendingEvents.toList()
                    pendingDelta.setLength(0)
                    pendingEvents.clear()
                    pendingChunks = 0
                    lastFlushAtNanos = now

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        val panelForUpdate = currentAssistantPanel ?: return@invokeLater
                        val shouldAutoScroll = messagesPanel.isNearBottom(STREAM_AUTO_SCROLL_THRESHOLD_PX)
                        panelForUpdate.appendStreamContent(delta, events)
                        if (shouldAutoScroll) {
                            messagesPanel.scrollToBottom()
                        }
                    }
                }

                projectService.chat(
                    providerId = providerId,
                    userInput = userInput,
                    modelId = modelId,
                    conversationHistory = conversationHistory.toList(),
                ) { chunk ->
                    if (chunk.delta.isNotEmpty()) {
                        assistantContent.append(chunk.delta)
                        pendingDelta.append(chunk.delta)
                    }
                    chunk.event
                        ?.let(::sanitizeStreamEvent)
                        ?.let {
                            pendingEvents += it
                            streamedTraceEvents += it
                        }
                    pendingChunks += 1

                    if (chunk.isLast) {
                        flushPending(force = true)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            currentAssistantPanel?.finishMessage()
                        }
                    } else {
                        flushPending(force = false)
                    }
                }
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                appendToConversationHistory(assistantMessage)
                currentSessionId?.let { sessionId ->
                    persistMessage(
                        sessionId = sessionId,
                        role = ConversationRole.ASSISTANT,
                        content = assistantMessage.content,
                        metadataJson = TraceEventMetadataCodec.encode(streamedTraceEvents),
                    )
                }
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
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
            appendToConversationHistory(LlmMessage(role, panel.getContent()))
        }
    }

    private fun appendToConversationHistory(message: LlmMessage) {
        conversationHistory.add(message)
        trimConversationHistoryIfNeeded()
    }

    private fun trimConversationHistoryIfNeeded() {
        if (conversationHistory.size <= MAX_CONVERSATION_HISTORY) {
            return
        }
        val overflow = conversationHistory.size - MAX_CONVERSATION_HISTORY
        repeat(overflow) {
            conversationHistory.removeAt(0)
        }
    }

    override fun dispose() {
        _isDisposed = true
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        scope.cancel()
    }

    companion object {
        private const val MAX_CONVERSATION_HISTORY = 240
        private const val MAX_RESTORED_MESSAGES = 240
        private const val SESSION_LOAD_FETCH_LIMIT = 5000
        private const val STREAM_BATCH_CHUNK_COUNT = 4
        private const val STREAM_BATCH_CHAR_COUNT = 240
        private const val STREAM_BATCH_INTERVAL_NANOS = 120_000_000L
        private const val STREAM_AUTO_SCROLL_THRESHOLD_PX = 80
        private const val STATUS_SESSION_LOADED_AUTO_HIDE_MILLIS = 2200
        private const val CONTINUE_FOCUS_MAX_LENGTH = 84
        private val CONTINUE_STAGE_PREFIX_REGEX = Regex("""^\[[^\]]+]\s*""")
        private val CONTINUE_ORDERED_PREFIX_REGEX = Regex("""^\d+\.\s*""")
        private val CONTINUE_ORDERED_LINE_REGEX = Regex("""^\d+\.\s+.+""")
        private val UI_CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]""")
        private val UI_BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
        private val UI_CJK_REGEX = Regex("""\p{IsHan}""")
        private val UI_SUSPICIOUS_CHAR_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
        private const val UI_GARBLED_MIN_COUNT = 4
        private const val UI_GARBLED_MIN_RATIO = 0.15
        private const val UI_PLACEHOLDER_MAX_LENGTH = 4
        private val UI_PLACEHOLDER_LINE_REGEX = Regex("""^[\p{P}\p{S}]+$""")
        private const val CONTINUE_PROMPT_UNWRAP_MAX_DEPTH = 8
        private val CONTINUE_CODE_LIKE_FOCUS_REGEX = Regex("""^[a-z0-9_.:+\-/]{2,64}$""", RegexOption.IGNORE_CASE)
        private val CONTINUE_DYNAMIC_ZH_REGEX = Regex(
            """^请承接上一轮输出继续执行，?\s*重点[:：]\s*(.+?)(?:。?\s*避免重复已完成内容.*)?$"""
        )
        private val CONTINUE_DYNAMIC_EN_REGEX = Regex(
            """^continue from the previous result with focus[:：]\s*(.+?)(?:\.?\s*avoid repeating completed work.*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
