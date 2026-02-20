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
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.DocumentRevisionConflictException
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.eacape.speccodingplugin.ui.chat.ChatSpecSidebarPanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagePanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagesListPanel
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadata
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadataCodec
import com.eacape.speccodingplugin.ui.chat.SpecCardMessagePanel
import com.eacape.speccodingplugin.ui.chat.SpecCardPanelSnapshot
import com.eacape.speccodingplugin.ui.chat.SpecCardSaveConflictException
import com.eacape.speccodingplugin.ui.chat.TraceEventMetadataCodec
import com.eacape.speccodingplugin.ui.completion.CompletionProvider
import com.eacape.speccodingplugin.ui.history.HistoryPanel
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.input.ContextPreviewPanel
import com.eacape.speccodingplugin.ui.input.SmartInputField
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedEvent
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedListener
import com.eacape.speccodingplugin.window.WindowSessionIsolationService
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
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
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.Timer
import javax.swing.JComponent

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
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private val interactionModeComboBox = ComboBox(ChatInteractionMode.entries.toTypedArray())
    private val statusLabel = JBLabel()
    private val messagesPanel = ChatMessagesListPanel()
    private val conversationHostPanel = JPanel(BorderLayout())
    private val conversationScrollPane by lazy { messagesPanel.getScrollPane() }
    private val specSidebarPanel = ChatSpecSidebarPanel(
        loadWorkflow = { workflowId -> specEngine.loadWorkflow(workflowId) },
        listWorkflows = { specEngine.listWorkflows() },
        onOpenDocument = ::openSpecWorkflowDocument,
    )
    private val chatSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
    private val specSidebarToggleButton = JButton()
    private val contextPreviewPanel = ContextPreviewPanel(project)
    private val composerHintLabel = JBLabel(SpecCodingBundle.message("toolwindow.composer.hint"))
    private lateinit var inputField: SmartInputField
    private val sendButton = JButton()
    private var currentAssistantPanel: ChatMessagePanel? = null
    private var statusAutoHideTimer: Timer? = null
    private val runningWorkflowCommands = ConcurrentHashMap<String, RunningWorkflowCommand>()

    // Session state
    private val conversationHistory = mutableListOf<LlmMessage>()
    private var currentSessionId: String? = null
    private var isGenerating = false
    private var isRestoringSession = false
    private var activeSpecWorkflowId: String? = null
    private var specSidebarVisible = false
    private var specSidebarDividerLocation = SPEC_SIDEBAR_DEFAULT_DIVIDER
    @Volatile
    private var _isDisposed = false

    private enum class ChatInteractionMode(
        val key: String,
        val messageKey: String,
    ) {
        VIBE("vibe", "toolwindow.chat.mode.vibe"),
        SPEC("spec", "toolwindow.chat.mode.spec"),
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
        subscribeToToolWindowControlEvents()
        subscribeToSpecWorkflowEvents()
        subscribeToLocaleEvents()
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

    private fun subscribeToToolWindowControlEvents() {
        project.messageBus.connect(this).subscribe(
            ChatToolWindowControlListener.TOPIC,
            object : ChatToolWindowControlListener {
                override fun onNewSessionRequested() {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        startNewSession()
                    }
                }

                override fun onOpenHistoryRequested() {
                    openHistoryPanel()
                }
            },
        )
    }

    private fun subscribeToSpecWorkflowEvents() {
        project.messageBus.connect(this).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) return@invokeLater
                        event.workflowId?.let { workflowId ->
                            activeSpecWorkflowId = workflowId
                        }
                        if (!specSidebarVisible) return@invokeLater
                        val eventWorkflowId = event.workflowId
                        if (!eventWorkflowId.isNullOrBlank() && specSidebarPanel.hasFocusedWorkflow(eventWorkflowId)) {
                            specSidebarPanel.focusWorkflow(eventWorkflowId)
                        } else {
                            specSidebarPanel.refreshCurrentWorkflow()
                        }
                    }
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
        composerHintLabel.text = SpecCodingBundle.message("toolwindow.composer.hint")
        refreshActionButtonTexts()
        updateSpecSidebarToggleButtonTexts()
        specSidebarPanel.refreshLocalizedTexts()
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        providerComboBox.repaint()
        modelComboBox.repaint()
        interactionModeComboBox.repaint()
        refreshHistoryContentTitle()
        updateComboTooltips()
        if (isGenerating) {
            showStatus(SpecCodingBundle.message("toolwindow.status.generating"))
        } else if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
        }
    }

    private fun setupUI() {
        // Model and interaction setup
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

        interactionModeComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create<ChatInteractionMode> { label, value, _ ->
            label.text = if (value == null) "" else SpecCodingBundle.message(value.messageKey)
        }
        interactionModeComboBox.selectedItem = ChatInteractionMode.VIBE
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        configureCompactCombo(interactionModeComboBox, 74)
        configureCompactCombo(providerComboBox, 72)
        configureCompactCombo(modelComboBox, 136)
        providerLabel.font = JBUI.Fonts.smallFont()
        modelLabel.font = JBUI.Fonts.smallFont()
        providerLabel.foreground = JBColor.GRAY
        modelLabel.foreground = JBColor.GRAY
        composerHintLabel.foreground = JBColor.GRAY
        composerHintLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = JBColor.GRAY
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.isVisible = false
        configureActionButtons()
        updateComboTooltips()

        sendButton.addActionListener { sendCurrentInput() }

        // Conversation area fills the top
        conversationHostPanel.isOpaque = false
        conversationScrollPane.border = JBUI.Borders.empty()
        messagesPanel.isOpaque = true
        messagesPanel.background = JBColor(
            java.awt.Color(247, 248, 250),
            java.awt.Color(34, 36, 39),
        )
        conversationScrollPane.viewport.isOpaque = true
        conversationScrollPane.viewport.background = messagesPanel.background

        chatSplitPane.leftComponent = conversationScrollPane
        chatSplitPane.rightComponent = specSidebarPanel
        chatSplitPane.dividerSize = JBUI.scale(2)
        chatSplitPane.resizeWeight = 0.74
        chatSplitPane.isContinuousLayout = true
        chatSplitPane.isOpaque = false
        chatSplitPane.border = JBUI.Borders.empty()
        chatSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { _ ->
            val location = chatSplitPane.dividerLocation
            if (location > 0) {
                specSidebarDividerLocation = location
                if (specSidebarVisible) {
                    windowStateStore.updateChatSpecSidebar(true, specSidebarDividerLocation)
                }
            }
        }
        configureSpecSidebarToggleButton()
        val restoredWindowState = windowStateStore.snapshot()
        specSidebarDividerLocation = restoredWindowState.chatSpecSidebarDividerLocation
            .takeIf { it > 0 }
            ?: SPEC_SIDEBAR_DEFAULT_DIVIDER
        applySpecSidebarVisibility(restoredWindowState.chatSpecSidebarVisible, persist = false)

        // Composer area
        val inputScroll = JScrollPane(inputField)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = JBDimension(0, 92)

        val composerMetaRow = JPanel(BorderLayout())
        composerMetaRow.isOpaque = false
        composerMetaRow.border = JBUI.Borders.emptyTop(5)
        composerMetaRow.add(composerHintLabel, BorderLayout.WEST)
        val composerMetaActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        composerMetaActions.isOpaque = false
        composerMetaActions.add(interactionModeComboBox)
        composerMetaRow.add(composerMetaActions, BorderLayout.EAST)

        val controlsLeftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        controlsLeftPanel.isOpaque = false
        controlsLeftPanel.add(providerComboBox)
        controlsLeftPanel.add(modelLabel)
        controlsLeftPanel.add(modelComboBox)
        controlsLeftPanel.add(operationModeSelector)

        val controlsRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2))
        controlsRightPanel.isOpaque = false
        controlsRightPanel.add(statusLabel)
        controlsRightPanel.add(specSidebarToggleButton)
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
        content.add(conversationHostPanel, BorderLayout.CENTER)
        content.add(bottomPanel, BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)
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

        val interactionMode = interactionModeComboBox.selectedItem as? ChatInteractionMode ?: ChatInteractionMode.VIBE
        if (interactionMode == ChatInteractionMode.SPEC) {
            handleSpecCommand("/spec $rawInput")
            return
        }

        val promptReference = resolvePromptReferences(rawInput)
        val visibleInput = rawInput
        val chatInput = buildChatInput(
            mode = interactionMode,
            userInput = promptReference.cleanedInput.ifBlank { rawInput },
            referencedPrompts = promptReference.templates,
        )

        val providerId = providerComboBox.selectedItem as? String
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val sessionId = ensureActiveSession(visibleInput, providerId)
        val explicitItems = loadExplicitItemContents(contextPreviewPanel.getItems())
        val contextSnapshot = contextCollector.collectForItems(explicitItems)
        contextPreviewPanel.clear()

        // Add user message to history
        val userMessage = LlmMessage(LlmRole.USER, chatInput)
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
                    userInput = chatInput,
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

    private data class PromptReferenceResolution(
        val cleanedInput: String,
        val templates: List<PromptTemplate>,
    )

    private fun buildChatInput(
        mode: ChatInteractionMode,
        userInput: String,
        referencedPrompts: List<PromptTemplate>,
    ): String {
        val modeAwareInput = when (mode) {
            ChatInteractionMode.VIBE -> userInput.trim()
            ChatInteractionMode.SPEC -> buildSpecModePrompt(userInput)
        }
        if (referencedPrompts.isEmpty()) {
            return modeAwareInput
        }

        val promptBlocks = referencedPrompts.joinToString("\n\n") { template ->
            buildString {
                appendLine("Prompt #${template.id} (${template.name}):")
                append(template.content.trim())
            }
        }
        return buildString {
            appendLine("Referenced prompt templates:")
            appendLine(promptBlocks)
            appendLine()
            append(modeAwareInput)
        }
    }

    private fun buildSpecModePrompt(input: String): String {
        val workflow = resolveActiveSpecWorkflow()
        val workflowHint = if (workflow != null) {
            "Workflow=${workflow.id}, phase=${workflow.currentPhase.name.lowercase(Locale.ROOT)}, status=${workflow.status.name.lowercase(Locale.ROOT)}"
        } else {
            "No active workflow. Infer phase from context and keep outputs aligned with requirements/design/tasks."
        }
        return buildString {
            appendLine("Interaction mode: spec")
            appendLine(workflowHint)
            appendLine(SpecCodingBundle.message("toolwindow.chat.mode.spec.instruction"))
            appendLine("User instruction:")
            appendLine(input.trim())
        }
    }

    private fun resolvePromptReferences(rawInput: String): PromptReferenceResolution {
        val allReferences = PROMPT_REFERENCE_REGEX.findAll(rawInput)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (allReferences.isEmpty()) {
            return PromptReferenceResolution(cleanedInput = rawInput, templates = emptyList())
        }

        val templates = projectService.availablePrompts()
        if (templates.isEmpty()) {
            return PromptReferenceResolution(cleanedInput = rawInput, templates = emptyList())
        }

        val byId = templates.associateBy { it.id.lowercase(Locale.ROOT) }
        val byName = templates.associateBy { normalizePromptAlias(it.name) }
        val resolvedTemplates = mutableListOf<PromptTemplate>()

        allReferences.forEach { ref ->
            val byIdMatch = byId[ref.lowercase(Locale.ROOT)]
            val byNameMatch = byName[normalizePromptAlias(ref)]
            val matched = byIdMatch ?: byNameMatch
            if (matched != null && resolvedTemplates.none { it.id == matched.id }) {
                resolvedTemplates += matched
            }
        }

        val cleaned = rawInput
            .replace(PROMPT_REFERENCE_REGEX, " ")
            .replace(PROMPT_EXTRA_SPACES_REGEX, " ")
            .trim()
        return PromptReferenceResolution(
            cleanedInput = cleaned.ifBlank { rawInput.trim() },
            templates = resolvedTemplates,
        )
    }

    private fun normalizePromptAlias(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(PROMPT_ALIAS_SEPARATOR_REGEX, "")
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
        val progressPanel = addAssistantMessage()
        val hasProgressEvent = AtomicBoolean(false)

        inputField.text = ""
        appendUserMessage(command)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, command)
        }

        setSendingState(true)

        scope.launch(Dispatchers.IO) {
            try {
                val result = executeSpecCommand(command) { event ->
                    val sanitizedEvent = sanitizeStreamEvent(event) ?: return@executeSpecCommand
                    hasProgressEvent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        val shouldAutoScroll = messagesPanel.isNearBottom(STREAM_AUTO_SCROLL_THRESHOLD_PX)
                        progressPanel.appendStreamContent("", listOf(sanitizedEvent))
                        if (shouldAutoScroll) {
                            messagesPanel.scrollToBottom()
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    if (hasProgressEvent.get()) {
                        progressPanel.finishMessage()
                    } else {
                        messagesPanel.removeMessage(progressPanel)
                    }
                    renderSpecCommandResult(
                        result = result,
                        persistSessionId = sessionId,
                        publishReason = "spec_command",
                    )
                }
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    if (hasProgressEvent.get()) {
                        progressPanel.finishMessage()
                    } else {
                        messagesPanel.removeMessage(progressPanel)
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

    private data class SpecCommandResult(
        val output: String,
        val metadata: SpecCardMetadata? = null,
    )

    private suspend fun executeSpecCommand(
        command: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): SpecCommandResult {
        val args = command.removePrefix("/spec").trim()
        if (args.isBlank() || args.equals("help", ignoreCase = true)) {
            return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.help"))
        }

        val commandToken = args.substringBefore(" ").trim().lowercase()
        val commandArg = args.substringAfter(" ", "").trim()

        return when (commandToken) {
            "status" -> {
                val workflow = resolveActiveSpecWorkflow()
                    ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
                buildSpecCardResult(
                    workflow = workflow,
                    sourceCommand = command,
                    summary = formatSpecStatus(workflow),
                )
            }
            "open" -> {
                openSpecTab()
                plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.opened"))
            }
            "next" -> transitionSpecWorkflow(advance = true, sourceCommand = command)
            "back" -> transitionSpecWorkflow(advance = false, sourceCommand = command)
            "generate" -> {
                if (commandArg.isBlank()) {
                    plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "generate"))
                } else {
                    generateSpecForActiveWorkflow(
                        input = commandArg,
                        sourceCommand = command,
                        onProgress = onProgress,
                    )
                }
            }
            "complete" -> completeSpecWorkflow(sourceCommand = command)
            else -> createAndGenerateWorkflow(
                requirementsInput = args,
                sourceCommand = command,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun createAndGenerateWorkflow(
        requirementsInput: String,
        sourceCommand: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): SpecCommandResult {
        if (requirementsInput.isBlank()) {
            return plainSpecResult(
                SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "<requirements text>"),
            )
        }

        val title = requirementsInput
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
            .take(80)

        onProgress(
            buildSpecProgressEvent(
                kind = ChatTraceKind.TASK,
                status = ChatTraceStatus.RUNNING,
                detail = SpecCodingBundle.message("toolwindow.spec.command.progress.create"),
            ),
        )
        val workflow = specEngine
            .createWorkflow(title = title, description = requirementsInput)
            .getOrElse { throw it }
        onProgress(
            buildSpecProgressEvent(
                kind = ChatTraceKind.TASK,
                status = ChatTraceStatus.DONE,
                detail = SpecCodingBundle.message("toolwindow.spec.command.progress.create"),
            ),
        )

        activeSpecWorkflowId = workflow.id

        val generationSummary = runSpecGeneration(workflow.id, requirementsInput, onProgress)
        val summary = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.spec.command.created", workflow.id))
            if (generationSummary.isNotBlank()) {
                append(generationSummary)
            }
        }.trimEnd()
        val refreshed = specEngine.loadWorkflow(workflow.id).getOrNull() ?: workflow
        return buildSpecCardResult(
            workflow = refreshed,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private suspend fun generateSpecForActiveWorkflow(
        input: String,
        sourceCommand: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): SpecCommandResult {
        val workflow = resolveActiveSpecWorkflow()
            ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))
        val summary = runSpecGeneration(workflow.id, input, onProgress)
        val refreshed = specEngine.loadWorkflow(workflow.id).getOrNull() ?: workflow
        return buildSpecCardResult(
            workflow = refreshed,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private suspend fun runSpecGeneration(
        workflowId: String,
        input: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): String {
        val lines = mutableListOf<String>()
        val workflow = specEngine.loadWorkflow(workflowId).getOrElse { throw it }
        val phaseName = specPhaseDisplayName(workflow.currentPhase)
        val generationTaskDetail = SpecCodingBundle.message(
            "toolwindow.spec.command.progress.generate",
            phaseName,
        )

        updateStatusLabel(
            SpecCodingBundle.message("toolwindow.spec.command.generating", phaseName),
        )
        onProgress(
            buildSpecProgressEvent(
                kind = ChatTraceKind.TASK,
                status = ChatTraceStatus.RUNNING,
                detail = generationTaskDetail,
            ),
        )

        specEngine.generateCurrentPhase(workflowId, input).collect { progress ->
            when (progress) {
                is SpecGenerationProgress.Started -> {
                    val progressPhaseName = specPhaseDisplayName(progress.phase)
                    updateStatusLabel(
                        SpecCodingBundle.message("toolwindow.spec.command.generating", progressPhaseName),
                    )
                }

                is SpecGenerationProgress.Generating -> {
                    val percent = (progress.progress * 100).toInt().coerceIn(0, 100)
                    val progressPhaseName = specPhaseDisplayName(progress.phase)
                    updateStatusLabel(
                        SpecCodingBundle.message(
                            "toolwindow.spec.command.generating.percent",
                            progressPhaseName,
                            percent,
                        )
                    )
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.OUTPUT,
                            status = ChatTraceStatus.INFO,
                            detail = SpecCodingBundle.message(
                                "toolwindow.spec.command.generating.percent",
                                progressPhaseName,
                                percent,
                            ),
                        ),
                    )
                }

                is SpecGenerationProgress.Completed -> {
                    val validationPassedLabel = SpecCodingBundle.message("toolwindow.spec.command.validation.pass")
                        .removePrefix("- ")
                        .removePrefix("-")
                        .trim()
                    lines += SpecCodingBundle.message(
                        "toolwindow.spec.command.generated",
                        workflowId,
                        specPhaseDisplayName(progress.document.phase),
                    )
                    lines += SpecCodingBundle.message("toolwindow.spec.command.validation.pass")
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.TASK,
                            status = ChatTraceStatus.DONE,
                            detail = generationTaskDetail,
                        ),
                    )
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.VERIFY,
                            status = ChatTraceStatus.DONE,
                            detail = validationPassedLabel,
                        ),
                    )
                }

                is SpecGenerationProgress.ValidationFailed -> {
                    val validationFailedLabel = SpecCodingBundle.message("toolwindow.spec.command.validation.fail")
                        .removePrefix("- ")
                        .removePrefix("-")
                        .trim()
                    lines += SpecCodingBundle.message(
                        "toolwindow.spec.command.generated",
                        workflowId,
                        specPhaseDisplayName(progress.document.phase),
                    )
                    lines += SpecCodingBundle.message("toolwindow.spec.command.validation.fail")
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.TASK,
                            status = ChatTraceStatus.DONE,
                            detail = generationTaskDetail,
                        ),
                    )
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.VERIFY,
                            status = ChatTraceStatus.ERROR,
                            detail = validationFailedLabel,
                        ),
                    )
                }

                is SpecGenerationProgress.Failed -> {
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.TASK,
                            status = ChatTraceStatus.ERROR,
                            detail = progress.error,
                        ),
                    )
                    throw IllegalStateException(progress.error)
                }
            }
        }

        if (lines.isEmpty()) {
            lines += SpecCodingBundle.message("toolwindow.spec.command.generated", workflowId, phaseName)
        }
        return lines.joinToString("\n")
    }

    private fun transitionSpecWorkflow(
        advance: Boolean,
        sourceCommand: String,
    ): SpecCommandResult {
        val current = resolveActiveSpecWorkflow()
            ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))
        val updated = if (advance) {
            specEngine.proceedToNextPhase(current.id)
        } else {
            specEngine.goBackToPreviousPhase(current.id)
        }.getOrElse { throw it }

        activeSpecWorkflowId = updated.id
        val summaryKey = if (advance) {
            "toolwindow.spec.command.phaseAdvanced"
        } else {
            "toolwindow.spec.command.phaseBack"
        }
        val summary = SpecCodingBundle.message(
            summaryKey,
            updated.id,
            updated.currentPhase.displayName,
        )
        return buildSpecCardResult(
            workflow = updated,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private fun completeSpecWorkflow(sourceCommand: String): SpecCommandResult {
        val workflow = resolveActiveSpecWorkflow()
            ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))
        val completed = specEngine.completeWorkflow(workflow.id).getOrElse { throw it }
        activeSpecWorkflowId = completed.id
        val summary = SpecCodingBundle.message("toolwindow.spec.command.completed", completed.id)
        return buildSpecCardResult(
            workflow = completed,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private fun formatSpecStatus(): String {
        val workflow = resolveActiveSpecWorkflow()
            ?: return SpecCodingBundle.message("toolwindow.spec.command.status.none")
        return formatSpecStatus(workflow)
    }

    private fun formatSpecStatus(workflow: SpecWorkflow): String {
        return SpecCodingBundle.message(
            "toolwindow.spec.command.status.entry",
            workflow.id,
            workflow.currentPhase.displayName,
            workflowStatusDisplayName(workflow),
            workflow.title.ifBlank { workflow.id },
        )
    }

    private fun workflowStatusDisplayName(workflow: SpecWorkflow): String {
        return workflowStatusDisplayName(workflow.status)
    }

    private fun workflowStatusDisplayName(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS ->
                SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED ->
                SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED ->
                SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED ->
                SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun specPhaseDisplayName(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.phase.specify")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.phase.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.phase.implement")
        }
    }

    private fun buildSpecProgressEvent(
        kind: ChatTraceKind,
        status: ChatTraceStatus,
        detail: String,
    ): ChatStreamEvent {
        return ChatStreamEvent(
            kind = kind,
            status = status,
            detail = detail,
        )
    }

    private fun buildSpecCardResult(
        workflow: SpecWorkflow,
        sourceCommand: String,
        summary: String? = null,
    ): SpecCommandResult {
        val document = workflow.getCurrentDocument()
        if (document == null) {
            val fallback = if (!summary.isNullOrBlank()) {
                listOf(summary, formatSpecStatus(workflow)).joinToString("\n")
            } else {
                formatSpecStatus(workflow)
            }
            return plainSpecResult(fallback)
        }

        val title = workflow.title.ifBlank { workflow.id }
        val metadata = SpecCardMetadata(
            workflowId = workflow.id,
            phase = workflow.currentPhase,
            status = workflow.status,
            title = title,
            revision = document.metadata.updatedAt,
            sourceCommand = sourceCommand,
        )
        return SpecCommandResult(
            output = buildSpecCardMarkdown(
                metadata = metadata,
                previewContent = document.content,
                summary = summary,
            ),
            metadata = metadata,
        )
    }

    private fun buildSpecCardMarkdown(
        metadata: SpecCardMetadata,
        previewContent: String,
        summary: String?,
    ): String {
        val preview = buildSpecCardPreview(previewContent)
        return buildString {
            appendLine(
                "## " + SpecCodingBundle.message(
                    "toolwindow.spec.card.title",
                    metadata.title.ifBlank { metadata.workflowId },
                ),
            )
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.workflow")}: `${metadata.workflowId}`")
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.phase")}: **${metadata.phase.displayName}**")
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.status")}: **${workflowStatusDisplayName(metadata.status)}**")
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.command")}: `${metadata.sourceCommand}`")
            if (!summary.isNullOrBlank()) {
                appendLine()
                appendLine("### ${SpecCodingBundle.message("toolwindow.spec.card.summary")}")
                appendLine(summary.trim())
            }
            appendLine()
            appendLine("### ${SpecCodingBundle.message("toolwindow.spec.card.preview")}")
            appendLine(preview.ifBlank { SpecCodingBundle.message("toolwindow.spec.card.empty") })
        }.trimEnd()
    }

    private fun buildSpecCardPreview(content: String): String {
        if (content.isBlank()) return ""
        val normalizedLines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .take(SPEC_CARD_PREVIEW_MAX_LINES)
            .toList()
        val normalized = normalizedLines.joinToString("\n").trim()
        if (normalized.isBlank()) return ""
        return if (normalized.length <= SPEC_CARD_PREVIEW_MAX_CHARS) {
            normalized
        } else {
            normalized.take(SPEC_CARD_PREVIEW_MAX_CHARS).trimEnd() + "..."
        }
    }

    private fun plainSpecResult(text: String): SpecCommandResult = SpecCommandResult(output = text)

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

    private fun openHistoryPanel() {
        if (project.isDisposed || _isDisposed || isGenerating || isRestoringSession) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return@invokeLater
            val contentManager = toolWindow.contentManager
            val historyTitle = SpecCodingBundle.message("history.tab.title")
            val existing = contentManager.contents.firstOrNull {
                it.getUserData(HISTORY_CONTENT_DATA_KEY) == HISTORY_CONTENT_KEY
            }

            if (existing != null) {
                existing.displayName = historyTitle
                contentManager.setSelectedContent(existing)
                toolWindow.activate(null)
                return@invokeLater
            }

            val historyPanel = HistoryPanel(project)
            val historyContent = ContentFactory.getInstance().createContent(historyPanel, historyTitle, false).apply {
                putUserData(HISTORY_CONTENT_DATA_KEY, HISTORY_CONTENT_KEY)
            }
            Disposer.register(historyContent, historyPanel)
            contentManager.addContent(historyContent)
            contentManager.setSelectedContent(historyContent)

            contentManager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) {
                    if (event.content != historyContent && contentManager.contents.contains(historyContent)) {
                        contentManager.removeContentManagerListener(this)
                        contentManager.removeContent(historyContent, true)
                    }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    if (event.content == historyContent) {
                        contentManager.removeContentManagerListener(this)
                    }
                }
            })
            toolWindow.activate(null)
        }
    }

    private fun refreshHistoryContentTitle() {
        if (project.isDisposed || _isDisposed) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return
        val historyContent = toolWindow.contentManager.contents.firstOrNull {
            it.getUserData(HISTORY_CONTENT_DATA_KEY) == HISTORY_CONTENT_KEY
        } ?: return
        historyContent.displayName = SpecCodingBundle.message("history.tab.title")
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

    private fun startNewSession() {
        if (isGenerating || isRestoringSession) {
            return
        }
        conversationHistory.clear()
        currentSessionId = null
        activeSpecWorkflowId = null
        sessionIsolationService.clearActiveSession()
        specSidebarPanel.clearFocusedWorkflow()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        inputField.text = ""
        currentAssistantPanel = null
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
                    val restoredSpecMetadata = SpecCardMetadataCodec.decode(message.metadataJson)
                    val restoredContent = if (restoredSpecMetadata != null) {
                        message.content.ifBlank {
                            buildSpecCardMarkdown(
                                metadata = restoredSpecMetadata,
                                previewContent = "",
                                summary = null,
                            )
                        }
                    } else {
                        message.content
                    }
                    val restoredTraceEvents = TraceEventMetadataCodec.decode(message.metadataJson)
                        .mapNotNull(::sanitizeStreamEvent)
                    if (restoredSpecMetadata != null && restoredTraceEvents.isEmpty()) {
                        addSpecCardMessage(
                            cardMarkdown = restoredContent,
                            metadata = restoredSpecMetadata,
                        )
                    } else {
                        val panel = ChatMessagePanel(
                            ChatMessagePanel.MessageRole.ASSISTANT,
                            restoredContent,
                            onDelete = ::handleDeleteMessage,
                            onRegenerate = ::handleRegenerateMessage,
                            onContinue = ::handleContinueMessage,
                            onWorkflowFileOpen = ::handleWorkflowFileOpen,
                            onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
                            onWorkflowCommandStop = ::handleWorkflowCommandStop,
                        )
                        if (restoredTraceEvents.isNotEmpty()) {
                            panel.appendStreamContent(text = "", events = restoredTraceEvents)
                        }
                        panel.finishMessage()
                        messagesPanel.addMessage(panel)
                    }
                    appendToConversationHistory(LlmMessage(LlmRole.ASSISTANT, restoredContent))
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
            onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
            onWorkflowCommandStop = ::handleWorkflowCommandStop,
        )
        messagesPanel.addMessage(panel)
        return panel
    }

    private fun addSpecCardMessage(
        cardMarkdown: String,
        metadata: SpecCardMetadata,
    ): SpecCardMessagePanel {
        val initialState = resolveSpecCardInitialState(metadata, cardMarkdown)
        val panel = SpecCardMessagePanel(
            metadata = initialState.metadata,
            cardMarkdown = cardMarkdown,
            initialDocumentContent = initialState.documentContent,
            onDeleteMessage = ::handleDeleteMessage,
            onContinueMessage = ::handleContinueMessage,
            onOpenSpecTab = ::openSpecTab,
            onOpenDocument = ::openSpecWorkflowDocument,
            onFocusSpecSidebar = ::focusSpecSidebarForCard,
            onSaveDocument = ::saveSpecCardDocument,
            onAdvancePhase = ::advanceSpecCardPhase,
        )
        messagesPanel.addMessage(panel)
        return panel
    }

    private data class SpecCardInitialState(
        val metadata: SpecCardMetadata,
        val documentContent: String,
    )

    private fun resolveSpecCardInitialState(
        metadata: SpecCardMetadata,
        cardMarkdown: String,
    ): SpecCardInitialState {
        val workflow = specEngine.loadWorkflow(metadata.workflowId).getOrNull()
        val fallbackContent = extractSpecCardPreviewFromMarkdown(cardMarkdown)
        if (workflow == null) {
            return SpecCardInitialState(metadata = metadata, documentContent = fallbackContent)
        }

        val documentAtPhase = workflow.getDocument(metadata.phase)
        if (documentAtPhase != null) {
            val refreshedMetadata = metadata.copy(
                status = workflow.status,
                title = workflow.title.ifBlank { metadata.title.ifBlank { metadata.workflowId } },
                revision = documentAtPhase.metadata.updatedAt,
            )
            val content = documentAtPhase.content.ifBlank { fallbackContent }
            return SpecCardInitialState(metadata = refreshedMetadata, documentContent = content)
        }

        val currentDocument = workflow.getCurrentDocument()
        if (currentDocument != null) {
            val refreshedMetadata = metadata.copy(
                phase = currentDocument.phase,
                status = workflow.status,
                title = workflow.title.ifBlank { metadata.title.ifBlank { metadata.workflowId } },
                revision = currentDocument.metadata.updatedAt,
            )
            val content = currentDocument.content.ifBlank { fallbackContent }
            return SpecCardInitialState(metadata = refreshedMetadata, documentContent = content)
        }

        return SpecCardInitialState(metadata = metadata, documentContent = fallbackContent)
    }

    private fun extractSpecCardPreviewFromMarkdown(cardMarkdown: String): String {
        val normalizedLines = cardMarkdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val previewHeaderIndex = normalizedLines.indexOfFirst { line ->
            line.trimStart().startsWith("### ") &&
                (line.contains("preview", ignoreCase = true) || line.contains("预览"))
        }
        if (previewHeaderIndex < 0 || previewHeaderIndex >= normalizedLines.lastIndex) {
            return cardMarkdown.trim()
        }
        return normalizedLines.subList(previewHeaderIndex + 1, normalizedLines.size)
            .joinToString("\n")
            .trim()
    }

    private fun focusSpecSidebarForCard(metadata: SpecCardMetadata) {
        focusSpecSidebar(metadata.workflowId, metadata.phase)
    }

    private fun openSpecWorkflowDocument(metadata: SpecCardMetadata) {
        openSpecWorkflowDocument(metadata.workflowId, metadata.phase)
    }

    private fun openSpecWorkflowDocument(workflowId: String, phase: SpecPhase) {
        val basePath = project.basePath ?: return
        val path = "$basePath/.spec-coding/specs/$workflowId/${phase.outputFileName}"
        val normalizedPath = path.replace('\\', '/')
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath)
        if (vf == null) {
            addErrorMessage(SpecCodingBundle.message("chat.workflow.action.fileNotFound", normalizedPath))
            return
        }
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }

    private fun publishSpecWorkflowChanged(workflowId: String?, reason: String) {
        runCatching {
            project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC)
                .onWorkflowChanged(
                    SpecWorkflowChangedEvent(
                        workflowId = workflowId,
                        reason = reason,
                    ),
                )
        }.onFailure { error ->
            logger.warn("Failed to publish spec workflow changed event", error)
        }
    }

    private fun renderSpecCommandResult(
        result: SpecCommandResult,
        persistSessionId: String?,
        publishReason: String,
    ) {
        result.metadata?.workflowId?.let { workflowId ->
            publishSpecWorkflowChanged(workflowId, reason = publishReason)
        }
        if (result.metadata != null) {
            if (specSidebarVisible) {
                specSidebarPanel.focusWorkflow(result.metadata.workflowId, result.metadata.phase)
            }
            addSpecCardMessage(
                cardMarkdown = result.output,
                metadata = result.metadata,
            )
        } else {
            val panel = addAssistantMessage()
            panel.appendContent(result.output)
            panel.finishMessage()
        }
        if (persistSessionId != null) {
            persistMessage(
                sessionId = persistSessionId,
                role = ConversationRole.ASSISTANT,
                content = result.output,
                metadataJson = result.metadata?.let(SpecCardMetadataCodec::encode),
            )
        }
        appendToConversationHistory(LlmMessage(LlmRole.ASSISTANT, result.output))
    }

    private fun saveSpecCardDocument(
        metadata: SpecCardMetadata,
        editedContent: String,
        forceSave: Boolean,
    ): Result<SpecCardPanelSnapshot> {
        return runCatching {
            val normalized = editedContent
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            require(normalized.isNotBlank()) { "Document content cannot be blank" }

            val updatedWorkflow = specEngine.updateDocumentContent(
                workflowId = metadata.workflowId,
                phase = metadata.phase,
                content = normalized,
                expectedRevision = if (forceSave) null else metadata.revision,
            ).getOrElse { error ->
                val conflict = error as? DocumentRevisionConflictException
                if (conflict != null) {
                    val latestContent = specEngine.loadWorkflow(metadata.workflowId)
                        .getOrNull()
                        ?.getDocument(metadata.phase)
                        ?.content
                        .orEmpty()
                    throw SpecCardSaveConflictException(
                        latestContent = latestContent,
                        expectedRevision = conflict.expectedRevision,
                        actualRevision = conflict.actualRevision,
                    )
                }
                throw error
            }

            activeSpecWorkflowId = updatedWorkflow.id

            val updatedDocument = updatedWorkflow.getDocument(metadata.phase)
                ?: throw IllegalStateException("Document not found after save: ${metadata.phase.name}")
            val refreshedMetadata = metadata.copy(
                status = updatedWorkflow.status,
                title = updatedWorkflow.title.ifBlank { metadata.title.ifBlank { metadata.workflowId } },
                revision = updatedDocument.metadata.updatedAt,
            )
            val markdown = buildSpecCardMarkdown(
                metadata = refreshedMetadata,
                previewContent = updatedDocument.content,
                summary = SpecCodingBundle.message(
                    if (forceSave) {
                        "toolwindow.spec.card.save.summary.force"
                    } else {
                        "toolwindow.spec.card.save.summary"
                    }
                ),
            )
            publishSpecWorkflowChanged(
                metadata.workflowId,
                reason = if (forceSave) "spec_card_force_save" else "spec_card_save",
            )

            SpecCardPanelSnapshot(
                metadata = refreshedMetadata,
                cardMarkdown = markdown,
                documentContent = updatedDocument.content,
            )
        }
    }

    private fun advanceSpecCardPhase(metadata: SpecCardMetadata) {
        if (isGenerating || isRestoringSession || project.isDisposed || _isDisposed) {
            return
        }
        activeSpecWorkflowId = metadata.workflowId
        val sessionId = currentSessionId
        setSendingState(true)

        scope.launch(Dispatchers.IO) {
            try {
                val result = transitionSpecWorkflow(
                    advance = true,
                    sourceCommand = "/spec next",
                )
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    renderSpecCommandResult(
                        result = result,
                        persistSessionId = sessionId,
                        publishReason = "spec_card_next",
                    )
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
        interactionModeComboBox.isEnabled = true
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
        sendButton.toolTipText = SpecCodingBundle.message("toolwindow.send")
        sendButton.accessibleContext.accessibleName = sendButton.toolTipText
    }

    private fun configureActionButtons() {
        sendButton.icon = AllIcons.Actions.Execute
        sendButton.text = ""
        sendButton.isFocusable = false
        sendButton.isFocusPainted = false
        sendButton.margin = JBUI.emptyInsets()
        sendButton.preferredSize = JBDimension(30, 28)
        sendButton.minimumSize = JBDimension(30, 28)
        sendButton.putClientProperty("JButton.buttonType", "toolbar")
        refreshActionButtonTexts()
    }

    private fun configureSpecSidebarToggleButton() {
        specSidebarToggleButton.icon = AllIcons.Nodes.Folder
        specSidebarToggleButton.isFocusable = false
        specSidebarToggleButton.isFocusPainted = false
        specSidebarToggleButton.margin = JBUI.emptyInsets()
        specSidebarToggleButton.preferredSize = JBDimension(28, 24)
        specSidebarToggleButton.minimumSize = JBDimension(28, 24)
        specSidebarToggleButton.maximumSize = JBDimension(28, 24)
        specSidebarToggleButton.text = ""
        specSidebarToggleButton.putClientProperty("JButton.buttonType", "borderless")
        specSidebarToggleButton.addActionListener { toggleSpecSidebar() }
        updateSpecSidebarToggleButtonTexts()
    }

    private fun updateSpecSidebarToggleButtonTexts() {
        val tooltipKey = if (specSidebarVisible) {
            "toolwindow.spec.sidebar.toggle.hide.tooltip"
        } else {
            "toolwindow.spec.sidebar.toggle.show.tooltip"
        }
        val tooltip = SpecCodingBundle.message(tooltipKey)
        specSidebarToggleButton.icon = if (specSidebarVisible) {
            AllIcons.Actions.MenuOpen
        } else {
            AllIcons.Nodes.Folder
        }
        specSidebarToggleButton.toolTipText = tooltip
        specSidebarToggleButton.accessibleContext.accessibleName = tooltip
    }

    private fun toggleSpecSidebar() {
        if (!specSidebarVisible) {
            val targetWorkflowId = activeSpecWorkflowId
                ?: specSidebarPanel.currentFocusedWorkflowId()
                ?: specEngine.listWorkflows().lastOrNull()
            if (!targetWorkflowId.isNullOrBlank()) {
                specSidebarPanel.focusWorkflow(targetWorkflowId)
            } else {
                specSidebarPanel.refreshCurrentWorkflow()
            }
        }
        applySpecSidebarVisibility(!specSidebarVisible, persist = true)
    }

    private fun focusSpecSidebar(workflowId: String, phase: SpecPhase?) {
        if (workflowId.isBlank()) return
        activeSpecWorkflowId = workflowId
        specSidebarPanel.focusWorkflow(workflowId, preferredPhase = phase)
        applySpecSidebarVisibility(visible = true, persist = true)
    }

    private fun applySpecSidebarVisibility(visible: Boolean, persist: Boolean) {
        if (specSidebarVisible == visible && conversationHostPanel.componentCount > 0) {
            if (persist) {
                windowStateStore.updateChatSpecSidebar(specSidebarVisible, specSidebarDividerLocation)
            }
            return
        }

        if (!visible) {
            val currentDivider = chatSplitPane.dividerLocation
            if (currentDivider > 0) {
                specSidebarDividerLocation = currentDivider
            }
        }

        conversationHostPanel.removeAll()
        if (visible) {
            if (specSidebarPanel.currentFocusedWorkflowId().isNullOrBlank()) {
                val defaultWorkflowId = activeSpecWorkflowId ?: specEngine.listWorkflows().lastOrNull()
                if (!defaultWorkflowId.isNullOrBlank()) {
                    activeSpecWorkflowId = defaultWorkflowId
                    specSidebarPanel.focusWorkflow(defaultWorkflowId)
                } else {
                    specSidebarPanel.refreshCurrentWorkflow()
                }
            }
            if (chatSplitPane.leftComponent !== conversationScrollPane) {
                chatSplitPane.leftComponent = conversationScrollPane
            }
            if (chatSplitPane.rightComponent !== specSidebarPanel) {
                chatSplitPane.rightComponent = specSidebarPanel
            }
            conversationHostPanel.add(chatSplitPane, BorderLayout.CENTER)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed || !specSidebarVisible) {
                    return@invokeLater
                }
                chatSplitPane.dividerLocation = specSidebarDividerLocation
                    .coerceAtLeast(SPEC_SIDEBAR_MIN_DIVIDER)
            }
        } else {
            conversationHostPanel.add(conversationScrollPane, BorderLayout.CENTER)
        }
        specSidebarVisible = visible
        updateSpecSidebarToggleButtonTexts()
        conversationHostPanel.revalidate()
        conversationHostPanel.repaint()
        if (persist) {
            windowStateStore.updateChatSpecSidebar(specSidebarVisible, specSidebarDividerLocation)
        }
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
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
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

    private fun handleWorkflowCommandExecute(command: String) {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank() || project.isDisposed || _isDisposed) {
            return
        }
        if (isGenerating || isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.generating"))
            return
        }

        if (normalizedCommand.startsWith("/")) {
            handleSlashCommand(normalizedCommand)
            return
        }

        val request = OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = "Workflow quick action command: $normalizedCommand",
            details = mapOf("command" to normalizedCommand),
        )
        if (!checkWorkflowCommandPermission(request, normalizedCommand)) {
            return
        }

        val existing = runningWorkflowCommands[normalizedCommand]
        if (existing != null && existing.process.isAlive) {
            val message = SpecCodingBundle.message("chat.workflow.action.runCommand.alreadyRunning", normalizedCommand)
            addSystemMessage(message)
            currentSessionId?.let { sessionId ->
                persistMessage(
                    sessionId = sessionId,
                    role = ConversationRole.TOOL,
                    content = message,
                )
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            runWorkflowShellCommandInBackground(normalizedCommand, request)
        }
    }

    private fun handleWorkflowCommandStop(command: String) {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank() || project.isDisposed || _isDisposed) {
            return
        }

        val running = runningWorkflowCommands[normalizedCommand]
        if (running == null || !running.process.isAlive) {
            val message = SpecCodingBundle.message("chat.workflow.action.stopCommand.notRunning", normalizedCommand)
            addSystemMessage(message)
            currentSessionId?.let { sessionId ->
                persistMessage(
                    sessionId = sessionId,
                    role = ConversationRole.TOOL,
                    content = message,
                )
            }
            return
        }

        if (!running.stopRequested.compareAndSet(false, true)) {
            return
        }

        val stoppingMessage = SpecCodingBundle.message("chat.workflow.action.stopCommand.stopping", normalizedCommand)
        addSystemMessage(stoppingMessage)
        currentSessionId?.let { sessionId ->
            persistMessage(
                sessionId = sessionId,
                role = ConversationRole.TOOL,
                content = stoppingMessage,
            )
        }

        scope.launch(Dispatchers.IO) {
            runCatching {
                running.process.destroy()
                if (running.process.isAlive) {
                    val exited = running.process.waitFor(
                        WORKFLOW_COMMAND_STOP_GRACE_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    if (!exited && running.process.isAlive) {
                        running.process.destroyForcibly()
                    }
                }
            }.onFailure { error ->
                val message = SpecCodingBundle.message(
                    "chat.workflow.action.runCommand.error",
                    normalizedCommand,
                ) + "\n" + (error.message ?: SpecCodingBundle.message("common.unknown"))
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    addErrorMessage(message)
                }
                currentSessionId?.let { sessionId ->
                    persistMessage(
                        sessionId = sessionId,
                        role = ConversationRole.TOOL,
                        content = message,
                    )
                }
            }
        }
    }

    private fun checkWorkflowCommandPermission(request: OperationRequest, command: String): Boolean {
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
                val confirmed = Messages.showYesNoDialog(
                    project,
                    SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.message", command),
                    SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.title"),
                    Messages.getQuestionIcon(),
                ) == Messages.YES
                if (confirmed) {
                    addSystemMessage(
                        SpecCodingBundle.message(
                            "toolwindow.mode.confirmation.accepted",
                            request.operation.name,
                        )
                    )
                }
                confirmed
            }
        }
    }

    private fun runWorkflowShellCommandInBackground(command: String, request: OperationRequest) {
        val started: RunningWorkflowCommand = try {
            startWorkflowShellCommand(command)
        } catch (error: Exception) {
            modeManager.recordOperation(request, success = false)
            val execution = WorkflowCommandExecutionResult(
                success = false,
                error = error.message ?: SpecCodingBundle.message("common.unknown"),
                output = error.message.orEmpty(),
            )
            val summary = formatWorkflowCommandExecutionSummary(command, execution)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                addErrorMessage(summary)
            }
            currentSessionId?.let { sessionId ->
                persistMessage(
                    sessionId = sessionId,
                    role = ConversationRole.TOOL,
                    content = summary,
                )
            }
            return
        }

        val startedMessage = SpecCodingBundle.message("chat.workflow.action.runCommand.startedBackground", command)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            addSystemMessage(startedMessage)
        }
        currentSessionId?.let { sessionId ->
            persistMessage(
                sessionId = sessionId,
                role = ConversationRole.TOOL,
                content = startedMessage,
            )
        }

        val timedOut = !started.process.waitFor(WORKFLOW_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (timedOut) {
            started.stopRequested.set(true)
            started.process.destroyForcibly()
            started.process.waitFor(2, TimeUnit.SECONDS)
        }

        started.outputReaderThread.join(WORKFLOW_COMMAND_JOIN_TIMEOUT_MILLIS)
        runningWorkflowCommands.remove(command, started)

        val exitCode = runCatching { started.process.exitValue() }.getOrNull()
        val execution = WorkflowCommandExecutionResult(
            success = !timedOut && !started.stopRequested.get() && exitCode == 0,
            exitCode = exitCode,
            output = started.outputBuffer.toString().trim(),
            timedOut = timedOut,
            stoppedByUser = started.stopRequested.get() && !timedOut,
            outputTruncated = started.outputTruncated.get(),
        )
        modeManager.recordOperation(
            request,
            success = execution.success || execution.stoppedByUser,
        )
        val summary = formatWorkflowCommandExecutionSummary(command, execution)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            if (execution.success || execution.stoppedByUser) {
                addSystemMessage(summary)
            } else {
                addErrorMessage(summary)
            }
        }
        currentSessionId?.let { sessionId ->
            persistMessage(
                sessionId = sessionId,
                role = ConversationRole.TOOL,
                content = summary,
            )
        }
    }

    private fun startWorkflowShellCommand(command: String): RunningWorkflowCommand {
        val process = ProcessBuilder(buildShellCommand(command))
            .directory(project.basePath?.let(::File))
            .redirectErrorStream(true)
            .start()
        val outputBuffer = StringBuilder()
        val outputTruncated = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val outputReaderThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(outputBuffer) {
                        if (outputBuffer.length < WORKFLOW_COMMAND_OUTPUT_MAX_CHARS) {
                            if (outputBuffer.isNotEmpty()) {
                                outputBuffer.append('\n')
                            }
                            outputBuffer.append(line)
                        } else {
                            outputTruncated.set(true)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "workflow-command-output-${command.hashCode()}"
            start()
        }

        val running = RunningWorkflowCommand(
            command = command,
            process = process,
            outputBuffer = outputBuffer,
            outputTruncated = outputTruncated,
            stopRequested = stopRequested,
            outputReaderThread = outputReaderThread,
        )
        val previous = runningWorkflowCommands.putIfAbsent(command, running)
        if (previous != null && previous.process.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("Command already running")
        }
        if (previous != null && !previous.process.isAlive) {
            runningWorkflowCommands[command] = running
        }
        return running
    }

    private fun formatWorkflowCommandExecutionSummary(
        command: String,
        execution: WorkflowCommandExecutionResult,
    ): String {
        val header = when {
            execution.stoppedByUser -> {
                SpecCodingBundle.message("chat.workflow.action.stopCommand.stopped", command)
            }

            execution.timedOut -> {
                SpecCodingBundle.message(
                    "chat.workflow.action.runCommand.timeout",
                    WORKFLOW_COMMAND_TIMEOUT_SECONDS,
                    command,
                )
            }

            execution.error != null -> {
                SpecCodingBundle.message("chat.workflow.action.runCommand.error", command)
            }

            execution.success -> {
                SpecCodingBundle.message(
                    "chat.workflow.action.runCommand.success",
                    execution.exitCode ?: 0,
                    command,
                )
            }

            else -> {
                SpecCodingBundle.message(
                    "chat.workflow.action.runCommand.failed",
                    execution.exitCode ?: -1,
                    command,
                )
            }
        }

        var output = sanitizeDisplayText(execution.output, dropGarbledLines = false)
            .ifBlank { SpecCodingBundle.message("chat.workflow.action.runCommand.noOutput") }
        if (execution.outputTruncated) {
            output += "\n${SpecCodingBundle.message("chat.workflow.action.runCommand.outputTruncated", WORKFLOW_COMMAND_OUTPUT_MAX_CHARS)}"
        }

        return buildString {
            appendLine(header)
            appendLine("```text")
            appendLine(output)
            append("```")
        }
    }

    private fun buildShellCommand(command: String): List<String> {
        return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) {
            listOf("cmd", "/c", command)
        } else {
            listOf("bash", "-lc", command)
        }
    }

    private data class WorkflowCommandExecutionResult(
        val success: Boolean,
        val exitCode: Int? = null,
        val output: String = "",
        val timedOut: Boolean = false,
        val stoppedByUser: Boolean = false,
        val error: String? = null,
        val outputTruncated: Boolean = false,
    )

    private data class RunningWorkflowCommand(
        val command: String,
        val process: Process,
        val outputBuffer: StringBuilder,
        val outputTruncated: AtomicBoolean,
        val stopRequested: AtomicBoolean,
        val outputReaderThread: Thread,
    )

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
        runningWorkflowCommands.values.forEach { running ->
            runCatching {
                running.stopRequested.set(true)
                if (running.process.isAlive) {
                    running.process.destroyForcibly()
                }
            }
        }
        runningWorkflowCommands.clear()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        scope.cancel()
    }

    companion object {
        private const val HISTORY_CONTENT_KEY = "SpecCoding.HistoryContent"
        private val HISTORY_CONTENT_DATA_KEY = Key.create<String>(HISTORY_CONTENT_KEY)
        private const val MAX_CONVERSATION_HISTORY = 240
        private const val MAX_RESTORED_MESSAGES = 240
        private const val SESSION_LOAD_FETCH_LIMIT = 5000
        private const val WORKFLOW_COMMAND_TIMEOUT_SECONDS = 120L
        private const val WORKFLOW_COMMAND_JOIN_TIMEOUT_MILLIS = 2000L
        private const val WORKFLOW_COMMAND_STOP_GRACE_SECONDS = 3L
        private const val WORKFLOW_COMMAND_OUTPUT_MAX_CHARS = 12_000
        private const val STREAM_BATCH_CHUNK_COUNT = 4
        private const val STREAM_BATCH_CHAR_COUNT = 240
        private const val STREAM_BATCH_INTERVAL_NANOS = 120_000_000L
        private const val STREAM_AUTO_SCROLL_THRESHOLD_PX = 80
        private const val SPEC_CARD_PREVIEW_MAX_LINES = 18
        private const val SPEC_CARD_PREVIEW_MAX_CHARS = 1800
        private const val SPEC_SIDEBAR_DEFAULT_DIVIDER = 760
        private const val SPEC_SIDEBAR_MIN_DIVIDER = 320
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
        private val PROMPT_REFERENCE_REGEX = Regex("""(?<!\S)#([\p{L}\p{N}_.-]+)""")
        private val PROMPT_EXTRA_SPACES_REGEX = Regex("""\s{2,}""")
        private val PROMPT_ALIAS_SEPARATOR_REGEX = Regex("""[\s_-]+""")
    }
}
