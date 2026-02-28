package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextConfig
import com.eacape.speccodingplugin.context.ContextCollector
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextTrimmer
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.engine.CliSlashInvocationKind
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookTriggerContext
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
import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector
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
import com.eacape.speccodingplugin.ui.input.ImageAttachmentPreviewPanel
import com.eacape.speccodingplugin.ui.input.SmartInputField
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.ui.spec.EditSpecWorkflowDialog
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedEvent
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedListener
import com.eacape.speccodingplugin.window.WindowSessionIsolationService
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Paths
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JComponent
import javax.swing.ImageIcon
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultEditorKit

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
    private val changesetStore by lazy { ChangesetStore.getInstance(project) }

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
    private data class SpecWorkflowOption(
        val workflowId: String,
        val title: String,
        val updatedAt: Long,
    )

    private val specWorkflowComboBox = ComboBox<SpecWorkflowOption>()
    private val interactionModeComboBox = ComboBox(ChatInteractionMode.entries.toTypedArray())
    private val statusLabel = JBLabel()
    private val messagesPanel = ChatMessagesListPanel()
    private val conversationHostPanel = JPanel(BorderLayout())
    private val conversationScrollPane by lazy { messagesPanel.getScrollPane() }
    private val specSidebarPanel = ChatSpecSidebarPanel(
        loadWorkflow = { workflowId -> specEngine.loadWorkflow(workflowId) },
        listWorkflows = { specEngine.listWorkflows() },
        onOpenDocument = ::openSpecWorkflowDocument,
        onEditWorkflow = ::editSpecWorkflowMetadata,
    )
    private val chatSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
    private val specSidebarToggleButton = JButton()
    private val contextPreviewPanel = ContextPreviewPanel(project)
    private val imageAttachmentPreviewPanel = ImageAttachmentPreviewPanel(onRemove = ::removeTransientClipboardImageIfNeeded)
    private val composerHintLabel = JBLabel(SpecCodingBundle.message("toolwindow.composer.hint"))
    private val specModeComboForeground = JBColor(
        Color(0x4A4F59), // #4A4F59
        Color(0xC6CBD3),
    )
    private lateinit var inputField: SmartInputField
    private val sendButton = StopAwareButton()
    private var currentAssistantPanel: ChatMessagePanel? = null
    private var statusAutoHideTimer: Timer? = null
    private var dividerPersistTimer: Timer? = null
    private var pasteKeyDispatcher: KeyEventDispatcher? = null
    private val runningWorkflowCommands = ConcurrentHashMap<String, RunningWorkflowCommand>()
    private val transientClipboardImagePaths = mutableSetOf<String>()
    private val pendingPastedTextBlocks = linkedMapOf<String, String>()
    private val userMessageRawContent = mutableMapOf<ChatMessagePanel, String>()
    private var pastedTextSequence = 0
    private var lastComposerTextSnapshot = ""
    private var composerAutoCollapseScheduled = false
    private var suppressComposerAutoCollapse = false

    // Session state
    private val conversationHistory = mutableListOf<LlmMessage>()
    private var currentSessionId: String? = null
    private var isGenerating = false
    private var isRestoringSession = false
    private var suppressSpecWorkflowSelectionEvents = false
    @Volatile
    private var activeOperationJob: Job? = null
    @Volatile
    private var activeLlmRequest: ActiveLlmRequest? = null
    private val stopRequested = AtomicBoolean(false)
    private var activeSpecWorkflowId: String? = null
    private var specSidebarVisible = false
    private var specSidebarDividerLocation = SPEC_SIDEBAR_DEFAULT_DIVIDER
    @Volatile
    private var _isDisposed = false

    private data class ActiveLlmRequest(
        val providerId: String,
        val requestId: String,
    )

    private enum class ChatInteractionMode(
        val key: String,
        val messageKey: String,
    ) {
        VIBE("vibe", "toolwindow.chat.mode.vibe"),
        SPEC("spec", "toolwindow.chat.mode.spec"),
    }

    private var lastInteractionMode: ChatInteractionMode = ChatInteractionMode.VIBE

    init {
        inputField = SmartInputField(
            placeholder = SpecCodingBundle.message("toolwindow.input.placeholder"),
            onSend = { handleSendOrStop() },
            onTrigger = { trigger ->
                val selectedProviderId = providerComboBox.selectedItem as? String
                ApplicationManager.getApplication().executeOnPooledThread {
                    val items = completionProvider.getCompletions(
                        trigger = trigger,
                        selectedProviderId = selectedProviderId,
                    )
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
            onPasteIntercept = {
                if (tryPasteImageAttachmentsFromClipboard()) {
                    true
                } else {
                    tryPasteCollapsedTextMarkerFromClipboard()
                }
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

    fun requestNewSessionFromTitleAction() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            if (isRestoringSession) {
                showStatus(
                    text = SpecCodingBundle.message("toolwindow.status.session.restoring"),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                return@invokeLater
            }
            if (isGenerating) {
                showStatus(
                    text = SpecCodingBundle.message("toolwindow.status.generating"),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                return@invokeLater
            }
            if (currentInteractionMode() != ChatInteractionMode.VIBE) {
                interactionModeComboBox.selectedItem = ChatInteractionMode.VIBE
            }
            startNewSession()
            showStatus(
                text = SpecCodingBundle.message("toolwindow.session.new"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
        }
    }

    fun requestOpenHistoryFromTitleAction() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            if (isRestoringSession) {
                showStatus(
                    text = SpecCodingBundle.message("toolwindow.status.session.restoring"),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                return@invokeLater
            }
            if (isGenerating) {
                showStatus(
                    text = SpecCodingBundle.message("toolwindow.status.generating"),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                return@invokeLater
            }
            openHistoryPanel()
        }
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
                    requestNewSessionFromTitleAction()
                }

                override fun onOpenHistoryRequested() {
                    requestOpenHistoryFromTitleAction()
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
                        val workflowId = event.workflowId?.trim()?.ifBlank { null }
                        val isWorkflowSelected =
                            event.reason == SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED &&
                                currentInteractionMode() == ChatInteractionMode.SPEC &&
                                workflowId != null
                        if (isWorkflowSelected) {
                            startNewSession()
                            activeSpecWorkflowId = workflowId
                            if (specSidebarVisible) {
                                specSidebarPanel.focusWorkflow(workflowId)
                            }
                        } else {
                            workflowId?.let { activeSpecWorkflowId = it }
                            if (specSidebarVisible) {
                                if (workflowId != null && specSidebarPanel.hasFocusedWorkflow(workflowId)) {
                                    specSidebarPanel.focusWorkflow(workflowId)
                                } else {
                                    specSidebarPanel.refreshCurrentWorkflow()
                                }
                            }
                        }

                        refreshSpecWorkflowComboBox(selectWorkflowId = workflowId)
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
        val shellBorderColor = JBColor(
            Color(218, 224, 233),
            Color(78, 84, 93),
        )
        val composerSurfaceColor = JBColor(
            Color(252, 252, 253),
            Color(43, 45, 49),
        )

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

        specWorkflowComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val label = component as? JLabel ?: return component
                val option = value as? SpecWorkflowOption
                val title = option?.title?.trim().orEmpty()
                label.font = specWorkflowComboBox.font
                label.text = if (index == -1) {
                    val maxTextWidth = (specWorkflowComboBox.preferredSize?.width ?: 0) - JBUI.scale(SPEC_WORKFLOW_COMBO_TEXT_OVERHEAD_PX)
                    ellipsizeToWidth(title, maxWidth = maxTextWidth, metrics = label.getFontMetrics(label.font))
                } else {
                    title
                }
                if (!isSelected) {
                    label.foreground = specModeComboForeground
                }
                return label
            }
        }
        specWorkflowComboBox.addActionListener {
            if (suppressSpecWorkflowSelectionEvents) return@addActionListener
            if (currentInteractionMode() != ChatInteractionMode.SPEC) return@addActionListener
            val option = specWorkflowComboBox.selectedItem as? SpecWorkflowOption ?: return@addActionListener
            switchActiveSpecWorkflowFromUser(option.workflowId)
            updateSpecWorkflowComboSize()
            updateComboTooltips()
        }
        specWorkflowComboBox.isVisible = false

        interactionModeComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val label = component as? JLabel ?: return component
                val mode = value as? ChatInteractionMode
                label.font = interactionModeComboBox.font
                label.text = if (mode == null) "" else SpecCodingBundle.message(mode.messageKey)
                if (!isSelected) {
                    label.foreground = specModeComboForeground
                }
                return label
            }
        }
        interactionModeComboBox.selectedItem = ChatInteractionMode.VIBE
        lastInteractionMode = ChatInteractionMode.VIBE
        interactionModeComboBox.addActionListener { onInteractionModeChanged() }
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        configureCompactCombo(interactionModeComboBox, 74)
        configureCompactCombo(specWorkflowComboBox, SPEC_WORKFLOW_COMBO_MIN_WIDTH)
        configureCompactCombo(providerComboBox, 72)
        configureCompactCombo(modelComboBox, 136)
        val italicSmallFont = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
        specWorkflowComboBox.font = italicSmallFont
        interactionModeComboBox.font = italicSmallFont
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
        configureClipboardImagePaste()
        installGlobalPasteKeyDispatcher()
        installComposerAutoCollapseListener()
        logPasteDiagnostic("paste diagnostics active; panel=${this::class.java.simpleName}")
        updateComboTooltips()

        sendButton.addActionListener { handleSendOrStop() }

        // Conversation area fills the top
        conversationHostPanel.isOpaque = true
        conversationScrollPane.border = JBUI.Borders.empty()
        messagesPanel.isOpaque = true
        messagesPanel.background = JBColor(
            java.awt.Color(247, 248, 250),
            java.awt.Color(34, 36, 39),
        )
        conversationHostPanel.background = messagesPanel.background
        conversationHostPanel.border = JBUI.Borders.customLine(shellBorderColor, 1, 1, 0, 1)
        conversationScrollPane.viewport.isOpaque = true
        conversationScrollPane.viewport.background = messagesPanel.background

        chatSplitPane.leftComponent = conversationScrollPane
        chatSplitPane.rightComponent = specSidebarPanel
        specSidebarPanel.minimumSize = Dimension(JBUI.scale(SPEC_SIDEBAR_MIN_WIDTH), 0)
        configureSplitPaneDivider()
        chatSplitPane.resizeWeight = 0.74
        chatSplitPane.isContinuousLayout = true
        chatSplitPane.isOpaque = false
        chatSplitPane.border = JBUI.Borders.empty()
        chatSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { _ ->
            val location = chatSplitPane.dividerLocation
            if (location > 0) {
                specSidebarDividerLocation = location
                scheduleDividerPersist()
            }
        }
        configureSpecSidebarToggleButton()
        val restoredWindowState = windowStateStore.snapshot()
        specSidebarDividerLocation = restoredWindowState.chatSpecSidebarDividerLocation
            .takeIf { it > 0 }
            ?: SPEC_SIDEBAR_DEFAULT_DIVIDER
        applySpecSidebarVisibility(restoredWindowState.chatSpecSidebarVisible, persist = false)
        applyInteractionModeUi(currentInteractionMode())

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
        composerMetaActions.add(specWorkflowComboBox)
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
        composerContainer.background = composerSurfaceColor
        composerContainer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                shellBorderColor,
                1,
                1,
                1,
                1,
            ),
            JBUI.Borders.empty(8, 10),
        )
        composerContainer.add(inputScroll, BorderLayout.CENTER)
        composerContainer.add(composerMetaRow, BorderLayout.SOUTH)

        val bottomPanel = JPanel()
        bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
        bottomPanel.isOpaque = true
        bottomPanel.background = composerSurfaceColor
        bottomPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(shellBorderColor, 1, 1, 1, 1),
            JBUI.Borders.empty(8, 10, 8, 10),
        )
        bottomPanel.add(contextPreviewPanel)
        bottomPanel.add(imageAttachmentPreviewPanel)
        bottomPanel.add(composerContainer)
        bottomPanel.add(controlsRow)

        // Layout
        val content = JPanel(BorderLayout())
        content.isOpaque = false
        content.add(conversationHostPanel, BorderLayout.CENTER)
        content.add(bottomPanel, BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)
    }

    private fun switchActiveSpecWorkflowFromUser(workflowId: String) {
        val normalizedId = workflowId.trim()
        if (normalizedId.isBlank()) return
        if (currentInteractionMode() != ChatInteractionMode.SPEC) return
        if (normalizedId == activeSpecWorkflowId?.trim()?.ifBlank { null }) {
            return
        }
        publishSpecWorkflowChanged(normalizedId, reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED)
    }

    private fun refreshSpecWorkflowComboBox(selectWorkflowId: String?) {
        if (project.isDisposed || _isDisposed) return
        if (currentInteractionMode() != ChatInteractionMode.SPEC) {
            specWorkflowComboBox.isVisible = false
            return
        }

        val targetWorkflowId = selectWorkflowId?.trim()?.ifBlank { null }
        scope.launch(Dispatchers.IO) {
            val options = runCatching { specEngine.listWorkflows() }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { workflowId ->
                    val workflow = specEngine.loadWorkflow(workflowId).getOrNull()
                    SpecWorkflowOption(
                        workflowId = workflowId,
                        title = workflow?.title?.ifBlank { workflowId } ?: workflowId,
                        updatedAt = workflow?.updatedAt ?: 0L,
                    )
                }
                .sortedWith(
                    compareByDescending<SpecWorkflowOption> { it.updatedAt }
                        .thenBy { it.title }
                        .thenBy { it.workflowId },
                )

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) return@invokeLater
                if (currentInteractionMode() != ChatInteractionMode.SPEC) {
                    specWorkflowComboBox.isVisible = false
                    return@invokeLater
                }

                suppressSpecWorkflowSelectionEvents = true
                try {
                    val previousSelection = (specWorkflowComboBox.selectedItem as? SpecWorkflowOption)?.workflowId
                    specWorkflowComboBox.removeAllItems()
                    options.forEach { option -> specWorkflowComboBox.addItem(option) }

                    val candidateSelection = targetWorkflowId
                        ?: activeSpecWorkflowId?.trim()?.ifBlank { null }
                        ?: previousSelection?.trim()?.ifBlank { null }
                    val selectedId = candidateSelection
                        ?.takeIf { desired -> options.any { it.workflowId == desired } }
                        ?: options.firstOrNull()?.workflowId
                    val selectedOption = options.firstOrNull { it.workflowId == selectedId }
                    if (selectedOption != null) {
                        specWorkflowComboBox.selectedItem = selectedOption
                        activeSpecWorkflowId = selectedOption.workflowId
                    }

                    specWorkflowComboBox.isVisible = options.isNotEmpty()
                    specWorkflowComboBox.isEnabled = options.size > 1
                } finally {
                    suppressSpecWorkflowSelectionEvents = false
                }

                updateSpecWorkflowComboSize()
                updateComboTooltips()
            }
        }
    }

    private fun handleSendOrStop() {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
            return
        }
        if (isGenerating) {
            requestStopActiveOperation()
            return
        }
        sendCurrentInput()
    }

    private fun requestStopActiveOperation() {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (!isGenerating && !isRestoringSession) {
            return
        }
        stopRequested.set(true)
        showStatus(SpecCodingBundle.message("toolwindow.status.stopping"))
        activeLlmRequest?.let { request ->
            llmRouter.cancel(providerId = request.providerId, requestId = request.requestId)
        }
        activeOperationJob?.cancel(CancellationException("Stopped by user"))
    }

    private fun requestAutoStopAfterMcpVerification(
        providerId: String,
        requestId: String,
        autoStopIssued: AtomicBoolean,
    ) {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (!autoStopIssued.compareAndSet(false, true)) {
            return
        }
        stopRequested.set(true)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            showStatus(SpecCodingBundle.message("toolwindow.status.mcp.verify.autostop"))
        }
        llmRouter.cancel(providerId = providerId, requestId = requestId)
        activeOperationJob?.cancel(CancellationException("Stopped after MCP verification reached terminal status"))
    }

    private fun looksLikeMcpSignal(event: ChatStreamEvent): Boolean {
        if (containsMcpKeyword(event.detail)) {
            return true
        }
        if (containsMcpKeyword(event.id)) {
            return true
        }
        return event.metadata.entries.any { (key, value) ->
            containsMcpKeyword(key) || containsMcpKeyword(value)
        }
    }

    private fun isMcpVerificationTerminalEvent(event: ChatStreamEvent): Boolean {
        if (event.kind != ChatTraceKind.VERIFY) {
            return false
        }
        return event.status == ChatTraceStatus.DONE || event.status == ChatTraceStatus.ERROR
    }

    private fun containsMcpKeyword(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isBlank()) {
            return false
        }
        return MCP_EVENT_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    private fun resolveProviderIdForRequest(providerId: String?): String {
        val normalized = providerId?.trim().orEmpty()
        if (normalized.isNotBlank()) {
            return normalized
        }
        return llmRouter.defaultProviderId()
    }

    private fun sendCurrentInput() {
        val visibleRawInput = inputField.text.trim()
        prunePendingPastedTextBlocks(visibleRawInput)
        val rawInput = expandPendingPastedTextBlocks(visibleRawInput)
        val selectedImagePaths = imageAttachmentPreviewPanel.getImagePaths()
        if ((visibleRawInput.isBlank() && selectedImagePaths.isEmpty()) || isGenerating || project.isDisposed || _isDisposed) {
            return
        }
        if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
            return
        }

        // Check for slash commands
        if (visibleRawInput.startsWith("/")) {
            if (selectedImagePaths.isNotEmpty()) {
                clearImageAttachments()
                showStatus(
                    SpecCodingBundle.message("toolwindow.image.attach.ignored.command"),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
            }
            handleSlashCommand(rawInput)
            return
        }

        val interactionMode = currentInteractionMode()
        if (interactionMode == ChatInteractionMode.SPEC) {
            val normalizedInput = rawInput.trim()
            val token = normalizedInput.substringBefore(" ").trim().lowercase(Locale.ROOT)
            val workflowId = resolveMostRecentSpecWorkflowIdOrNull()?.also { activeSpecWorkflowId = it }
            val isBareSpecCommand = token in setOf("status", "open", "next", "back", "generate", "complete", "help")
            if (isBareSpecCommand || workflowId == null) {
                if (selectedImagePaths.isNotEmpty()) {
                    clearImageAttachments()
                    showStatus(
                        SpecCodingBundle.message("toolwindow.image.attach.ignored.spec"),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                }
                if (normalizedInput.isBlank()) {
                    return
                }
                handleSpecCommand("/spec $normalizedInput", sessionTitleSeed = normalizedInput)
                return
            }
        }

        val promptReference = resolvePromptReferences(rawInput)
        val baseChatInput = buildChatInput(
            mode = interactionMode,
            userInput = promptReference.cleanedInput.ifBlank {
                rawInput.ifBlank { SpecCodingBundle.message("toolwindow.image.default.prompt") }
            },
            referencedPrompts = promptReference.templates,
        )
        val chatInput = appendImagePathsToPrompt(baseChatInput, selectedImagePaths)
        val visibleInput = buildVisibleInput(visibleRawInput, selectedImagePaths)

        val providerId = providerComboBox.selectedItem as? String
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val operationMode = modeManager.getCurrentMode()
        val sessionId = if (interactionMode == ChatInteractionMode.SPEC) {
            val workflowId = resolveMostRecentSpecWorkflowIdOrNull()
            if (workflowId.isNullOrBlank()) {
                showStatus(
                    SpecCodingBundle.message("toolwindow.spec.command.noActive"),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                return
            }
            activeSpecWorkflowId = workflowId
            ensureActiveSpecSession(titleSeed = visibleInput, providerId = providerId, specTaskId = workflowId)
        } else {
            ensureActiveSession(visibleInput, providerId)
        }
        val explicitItems = loadExplicitItemContents(contextPreviewPanel.getItems())
        val contextSnapshot = collectContextSnapshotSafely(explicitItems)
        contextPreviewPanel.clear()
        clearImageAttachments(purgeTransientFiles = false)

        // Add user message to history
        val userMessage = LlmMessage(LlmRole.USER, chatInput)
        appendToConversationHistory(userMessage)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, visibleInput)
        }

        appendUserMessage(content = visibleInput, rawContent = chatInput)
        clearComposerInput()

        val beforeSnapshot = captureWorkspaceSnapshot()
        val requestId = UUID.randomUUID().toString()
        val resolvedProviderId = resolveProviderIdForRequest(providerId)
        stopRequested.set(false)
        activeLlmRequest = ActiveLlmRequest(providerId = resolvedProviderId, requestId = requestId)
        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        activeOperationJob = scope.launch {
            val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
            val assistantContent = StringBuilder()
            val pendingDelta = StringBuilder()
            val pendingEvents = mutableListOf<ChatStreamEvent>()
            val mcpSignalDetected = AtomicBoolean(false)
            val autoStopIssued = AtomicBoolean(false)
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
                    panel.appendStreamContent(delta, events)
                }
            }
            try {
                projectService.chat(
                    providerId = resolvedProviderId,
                    userInput = chatInput,
                    modelId = modelId,
                    contextSnapshot = contextSnapshot,
                    conversationHistory = conversationHistory.toList(),
                    operationMode = operationMode,
                    planExecuteVerifySections = workflowSectionRenderingEnabledFor(interactionMode),
                    imagePaths = selectedImagePaths,
                    requestId = requestId,
                ) { chunk ->
                    if (chunk.delta.isNotEmpty()) {
                        assistantContent.append(chunk.delta)
                        pendingDelta.append(chunk.delta)
                    }
                    chunk.event
                        ?.let(::sanitizeStreamEvent)
                        ?.let { event ->
                            pendingEvents += event
                            streamedTraceEvents += event
                            if (looksLikeMcpSignal(event)) {
                                mcpSignalDetected.set(true)
                            }
                            if (mcpSignalDetected.get() && isMcpVerificationTerminalEvent(event)) {
                                requestAutoStopAfterMcpVerification(
                                    providerId = resolvedProviderId,
                                    requestId = requestId,
                                    autoStopIssued = autoStopIssued,
                                )
                            }
                        }
                    pendingChunks += 1

                    if (chunk.isLast) {
                        flushPending(force = true)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            currentAssistantPanel?.finishMessage()
                            messagesPanel.scrollToBottom()
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
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(
                            error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"),
                        )
                    }
                } else {
                    val partial = assistantContent.toString()
                    if (partial.isNotBlank()) {
                        val assistantMessage = LlmMessage(LlmRole.ASSISTANT, partial)
                        appendToConversationHistory(assistantMessage)
                        if (sessionId != null) {
                            persistMessage(
                                sessionId = sessionId,
                                role = ConversationRole.ASSISTANT,
                                content = assistantMessage.content,
                                metadataJson = TraceEventMetadataCodec.encode(streamedTraceEvents),
                            )
                        }
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                stopRequested.set(false)

                flushPending(force = true)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    cleanupTransientClipboardImages(selectedImagePaths)
                    currentAssistantPanel?.finishMessage()
                    setSendingState(false)
                }
                runCatching {
                    persistAssistantResponseChangeset(
                        requestText = visibleInput,
                        providerId = providerId,
                        modelId = modelId,
                        beforeSnapshot = beforeSnapshot,
                        hasExecutionTrace = streamedTraceEvents.isNotEmpty(),
                    )
                }.onFailure {
                    logger.warn("Failed to persist assistant response changeset after streaming request", it)
                }
            }
        }
    }

    private data class PromptReferenceResolution(
        val cleanedInput: String,
        val templates: List<PromptTemplate>,
    )

    private fun configureClipboardImagePaste() {
        val focusedInputMap = inputField.getInputMap(JComponent.WHEN_FOCUSED)
        val ancestorInputMap = inputField.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = inputField.actionMap
        val defaultPasteAction = actionMap.get(DefaultEditorKit.pasteAction)
        val shortcutMask = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
            .getOrDefault(InputEvent.CTRL_DOWN_MASK)
        val pasteAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                logPasteDiagnostic(
                    "ACTION_PASTE_IMAGE_OR_TEXT triggered; focusOwner=${inputField.isFocusOwner}; source=${e?.source?.javaClass?.simpleName}",
                )
                if (tryPasteImageAttachmentsFromClipboard()) {
                    logPasteDiagnostic("paste action handled by image attachment flow")
                    return
                }
                val hasStringContent = clipboardHasStringContent()
                logPasteDiagnostic("paste action fallback to text; hasStringContent=$hasStringContent")
                if (tryPasteCollapsedTextMarkerFromClipboard()) {
                    logPasteDiagnostic("paste action collapsed large text into marker")
                    return
                }
                defaultPasteAction?.actionPerformed(e) ?: inputField.paste()
                prunePendingPastedTextBlocks(inputField.text.orEmpty())
                if (!hasStringContent) {
                    showStatus(
                        SpecCodingBundle.message("toolwindow.image.attach.unsupported"),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                    logPasteDiagnostic("paste fallback had no string content; unsupported notice shown")
                }
            }
        }
        focusedInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask),
            ACTION_PASTE_IMAGE_OR_TEXT,
        )
        focusedInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
            ACTION_PASTE_IMAGE_OR_TEXT,
        )
        ancestorInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask),
            ACTION_PASTE_IMAGE_OR_TEXT,
        )
        actionMap.put(ACTION_PASTE_IMAGE_OR_TEXT, pasteAction)
        // Also intercept paste invoked from popup menu / editor kit action.
        actionMap.put(DefaultEditorKit.pasteAction, pasteAction)
    }

    private fun installGlobalPasteKeyDispatcher() {
        val existing = pasteKeyDispatcher
        if (existing != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(existing)
        }
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || !isPasteShortcut(event)) {
                return@KeyEventDispatcher false
            }
            if (project.isDisposed || _isDisposed) {
                return@KeyEventDispatcher false
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@KeyEventDispatcher false
            if (!SwingUtilities.isDescendingFrom(focusOwner, this)) {
                return@KeyEventDispatcher false
            }
            logPasteDiagnostic(
                "global dispatcher captured paste key=${event.keyCode}; focus=${focusOwner.javaClass.name}",
            )
            val handled = tryPasteImageAttachmentsFromClipboard() || tryPasteCollapsedTextMarkerFromClipboard()
            if (handled) {
                event.consume()
                logPasteDiagnostic("global dispatcher consumed paste event for image/text collapse")
            } else {
                logPasteDiagnostic("global dispatcher did not resolve image attachment")
            }
            handled
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        pasteKeyDispatcher = dispatcher
        logPasteDiagnostic("global paste key dispatcher installed")
    }

    private fun isPasteShortcut(event: KeyEvent): Boolean {
        val shortcutMask = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
            .getOrDefault(InputEvent.CTRL_DOWN_MASK)
        val ctrlLikePaste = event.keyCode == KeyEvent.VK_V && (event.modifiersEx and shortcutMask) == shortcutMask
        val shiftInsertPaste = event.keyCode == KeyEvent.VK_INSERT && event.isShiftDown
        return ctrlLikePaste || shiftInsertPaste
    }

    private fun tryPasteImageAttachmentsFromClipboard(): Boolean {
        if (project.isDisposed || _isDisposed) return false
        logPasteDiagnostic("tryPasteImageAttachmentsFromClipboard start")
        val clipboardCandidates = currentClipboardContentsCandidates()
        if (clipboardCandidates.isEmpty()) {
            logPasteDiagnostic("no clipboard candidates available")
            return false
        }

        val detectedImagePaths = linkedSetOf<String>()
        var detectedClipboardImage: Image? = null
        var hasFileListFlavor = false
        clipboardCandidates.forEachIndexed { index, clipboardContent ->
            logPasteDiagnostic("clipboard candidate[$index] flavors=${describeClipboardTransferable(clipboardContent)}")
            detectedImagePaths += extractImagePathsFromClipboardFiles(clipboardContent)
            detectedImagePaths += extractImagePathsFromClipboardText(clipboardContent)
            if (detectedClipboardImage == null) {
                detectedClipboardImage = extractClipboardImage(clipboardContent)
            }
            if (clipboardContent.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                hasFileListFlavor = true
            }
        }
        logPasteDiagnostic(
            "clipboard parse summary: imagePaths=${detectedImagePaths.size}, hasClipboardImage=${detectedClipboardImage != null}, hasFileListFlavor=$hasFileListFlavor",
        )

        if (detectedImagePaths.isNotEmpty()) {
            val imagePaths = detectedImagePaths.toList()
            imageAttachmentPreviewPanel.addImagePaths(imagePaths)
            showStatus(
                SpecCodingBundle.message("toolwindow.image.attach.added", imagePaths.size),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            logPasteDiagnostic("added image paths: ${imagePaths.joinToString(limit = 3, truncated = "...")}")
            return true
        }
        if (hasFileListFlavor) {
            showStatus(
                SpecCodingBundle.message("toolwindow.image.attach.unsupported"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            logPasteDiagnostic("clipboard has file list flavor but no supported image path")
            return true
        }

        val clipboardImage = detectedClipboardImage ?: run {
            logPasteDiagnostic("no raw clipboard image detected")
            return false
        }
        val tempImagePath = persistClipboardImage(clipboardImage)
        if (tempImagePath == null) {
            showStatus(
                SpecCodingBundle.message("toolwindow.image.attach.unsupported"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            logPasteDiagnostic("clipboard image detected but failed to persist temp file")
            return true
        }
        imageAttachmentPreviewPanel.addImagePaths(listOf(tempImagePath))
        transientClipboardImagePaths += normalizedPathKey(tempImagePath)
        showStatus(
            SpecCodingBundle.message("toolwindow.image.attach.added", 1),
            autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
        )
        logPasteDiagnostic("added clipboard image as temp file: $tempImagePath")
        return true
    }

    private fun currentClipboardContentsCandidates(): List<Transferable> {
        val candidates = mutableListOf<Transferable>()

        val systemClipboard = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.onFailure { error ->
            warnPasteDiagnostic("failed to read system clipboard: ${error.message}", error)
        }.getOrNull()
        if (systemClipboard != null) {
            candidates += systemClipboard
        }

        val ideClipboard = runCatching {
            CopyPasteManager.getInstance().contents
        }.onFailure { error ->
            warnPasteDiagnostic("failed to read IDE clipboard: ${error.message}", error)
        }.getOrNull()
        if (ideClipboard != null && candidates.none { it === ideClipboard }) {
            candidates += ideClipboard
        }
        logPasteDiagnostic(
            "clipboard candidates assembled: count=${candidates.size}, system=${systemClipboard != null}, ide=${ideClipboard != null}",
        )
        return candidates
    }

    private fun clipboardHasStringContent(): Boolean {
        return currentClipboardContentsCandidates()
            .any { clipboard -> clipboard.isDataFlavorSupported(DataFlavor.stringFlavor) }
    }

    private fun tryPasteCollapsedTextMarkerFromClipboard(): Boolean {
        val clipboardText = extractClipboardPlainText() ?: return false
        val normalized = normalizeClipboardText(clipboardText)
        val rawText = expandPendingPastedTextBlocks(normalized)
        val lineCount = rawText.lineSequence().count()
        if (!shouldCollapsePastedText(rawText, lineCount)) {
            return false
        }

        val marker = nextPastedTextMarker(lineCount)
        pendingPastedTextBlocks[marker] = rawText
        inputField.replaceSelection(marker)
        prunePendingPastedTextBlocks(inputField.text.orEmpty())
        lastComposerTextSnapshot = inputField.text.orEmpty()
        return true
    }

    private fun extractClipboardPlainText(): String? {
        val candidates = currentClipboardContentsCandidates()
        for (clipboard in candidates) {
            if (!clipboard.isDataFlavorSupported(DataFlavor.stringFlavor)) continue
            val text = runCatching {
                clipboard.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            return text
        }
        return null
    }

    private fun normalizeClipboardText(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun shouldCollapsePastedText(text: String, lineCount: Int): Boolean {
        if (PASTED_TEXT_MARKER_REGEX.containsMatchIn(text)) {
            return true
        }
        if (lineCount >= INPUT_PASTE_COLLAPSE_MIN_LINES) {
            return true
        }
        return lineCount >= INPUT_PASTE_COLLAPSE_MIN_LINES_SOFT &&
            text.length >= INPUT_PASTE_COLLAPSE_MIN_CHARS
    }

    private fun nextPastedTextMarker(lineCount: Int): String {
        pastedTextSequence += 1
        val normalizedLines = lineCount.coerceAtLeast(1)
        return "[Pasted text #$pastedTextSequence +$normalizedLines lines]"
    }

    private fun prunePendingPastedTextBlocks(currentInput: String) {
        if (pendingPastedTextBlocks.isEmpty()) return
        val iterator = pendingPastedTextBlocks.entries.iterator()
        while (iterator.hasNext()) {
            val marker = iterator.next().key
            if (!currentInput.contains(marker)) {
                iterator.remove()
            }
        }
    }

    private fun expandPendingPastedTextBlocks(input: String): String {
        if (pendingPastedTextBlocks.isEmpty() || input.isBlank()) {
            return input
        }
        var expanded = input
        repeat(pendingPastedTextBlocks.size) {
            var changed = false
            pendingPastedTextBlocks.forEach { (marker, rawText) ->
                if (expanded.contains(marker)) {
                    expanded = expanded.replace(marker, rawText)
                    changed = true
                }
            }
            if (!changed) {
                return expanded
            }
        }
        return expanded
    }

    private fun clearComposerInput() {
        pendingPastedTextBlocks.clear()
        pastedTextSequence = 0
        lastComposerTextSnapshot = ""
        inputField.text = ""
    }

    private fun setComposerInput(text: String) {
        pendingPastedTextBlocks.clear()
        pastedTextSequence = 0
        inputField.text = text
        collapseComposerTextIfNeeded(previousSnapshot = "")
        lastComposerTextSnapshot = inputField.text.orEmpty()
    }

    private fun installComposerAutoCollapseListener() {
        inputField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleComposerAutoCollapse()

            override fun removeUpdate(e: DocumentEvent?) = scheduleComposerAutoCollapse()

            override fun changedUpdate(e: DocumentEvent?) = scheduleComposerAutoCollapse()
        })
    }

    private fun scheduleComposerAutoCollapse() {
        if (project.isDisposed || _isDisposed || suppressComposerAutoCollapse) {
            return
        }
        if (composerAutoCollapseScheduled) {
            return
        }
        composerAutoCollapseScheduled = true
        SwingUtilities.invokeLater {
            composerAutoCollapseScheduled = false
            if (project.isDisposed || _isDisposed || suppressComposerAutoCollapse) {
                return@invokeLater
            }
            val previousSnapshot = lastComposerTextSnapshot
            collapseComposerTextIfNeeded(previousSnapshot = previousSnapshot)
            lastComposerTextSnapshot = inputField.text.orEmpty()
        }
    }

    private fun collapseComposerTextIfNeeded(previousSnapshot: String) {
        val currentInput = inputField.text.orEmpty()
        prunePendingPastedTextBlocks(currentInput)
        if (currentInput.isBlank()) {
            pastedTextSequence = 0
            return
        }
        if (tryCollapseMixedMarkerComposerInput(currentInput)) {
            return
        }
        val delta = detectInsertedTextDelta(previousSnapshot, currentInput) ?: return
        val insertedNormalized = normalizeClipboardText(delta.insertedText)
        if (insertedNormalized.isBlank()) {
            return
        }
        val insertedRawText = expandPendingPastedTextBlocks(insertedNormalized)
        val lineCount = insertedRawText.lineSequence().count()
        if (!shouldCollapsePastedText(insertedRawText, lineCount)) {
            return
        }

        val previousNormalized = normalizeClipboardText(previousSnapshot)
        val currentNormalized = normalizeClipboardText(currentInput)
        val previousLines = if (previousNormalized.isBlank()) 0 else previousNormalized.lineSequence().count()
        val currentLines = currentNormalized.lineSequence().count()
        val deltaChars = insertedRawText.length
        val deltaLines = (currentLines - previousLines).coerceAtLeast(0)
        if (previousNormalized.isNotBlank() &&
            deltaChars < INPUT_PASTE_COLLAPSE_ABRUPT_MIN_CHARS &&
            deltaLines < INPUT_PASTE_COLLAPSE_ABRUPT_MIN_LINES &&
            !PASTED_TEXT_MARKER_REGEX.containsMatchIn(insertedRawText)
        ) {
            return
        }

        applyCollapsedMarkerToComposer(
            rawText = insertedRawText,
            lineCount = lineCount,
            replaceStart = delta.start,
            replaceEndExclusive = delta.endExclusive,
        )
    }

    private fun tryCollapseMixedMarkerComposerInput(currentInput: String): Boolean {
        val normalizedInput = normalizeClipboardText(currentInput)
        if (!PASTED_TEXT_MARKER_REGEX.containsMatchIn(normalizedInput)) {
            return false
        }
        val nonMarkerContentExists = normalizedInput
            .lineSequence()
            .map { it.trim() }
            .any { line ->
                line.isNotBlank() && !PASTED_TEXT_MARKER_FULL_LINE_REGEX.matches(line)
            }
        if (!nonMarkerContentExists) {
            return false
        }
        val expandedRaw = expandPendingPastedTextBlocks(normalizedInput)
        val lineCount = expandedRaw.lineSequence().count()
        if (!shouldCollapsePastedText(expandedRaw, lineCount)) {
            return false
        }
        applyCollapsedMarkerToComposer(
            rawText = expandedRaw,
            lineCount = lineCount,
            replaceStart = 0,
            replaceEndExclusive = currentInput.length,
        )
        return true
    }

    private fun applyCollapsedMarkerToComposer(
        rawText: String,
        lineCount: Int,
        replaceStart: Int,
        replaceEndExclusive: Int,
    ) {
        val marker = nextPastedTextMarker(lineCount)
        pendingPastedTextBlocks[marker] = rawText
        val originalInput = inputField.text.orEmpty()
        val safeStart = replaceStart.coerceIn(0, originalInput.length)
        val safeEnd = replaceEndExclusive.coerceIn(safeStart, originalInput.length)
        val replaced = buildString(originalInput.length - (safeEnd - safeStart) + marker.length) {
            append(originalInput, 0, safeStart)
            append(marker)
            append(originalInput, safeEnd, originalInput.length)
        }
        suppressComposerAutoCollapse = true
        try {
            inputField.text = replaced
            inputField.caretPosition = (safeStart + marker.length).coerceAtMost(inputField.text.length)
        } finally {
            suppressComposerAutoCollapse = false
        }
        prunePendingPastedTextBlocks(inputField.text.orEmpty())
    }

    private data class InsertedTextDelta(
        val start: Int,
        val endExclusive: Int,
        val insertedText: String,
    )

    private fun detectInsertedTextDelta(previousText: String, currentText: String): InsertedTextDelta? {
        if (previousText == currentText) {
            return null
        }
        var prefix = 0
        val sharedPrefixLimit = minOf(previousText.length, currentText.length)
        while (prefix < sharedPrefixLimit && previousText[prefix] == currentText[prefix]) {
            prefix += 1
        }

        var previousSuffix = previousText.length - 1
        var currentSuffix = currentText.length - 1
        while (previousSuffix >= prefix &&
            currentSuffix >= prefix &&
            previousText[previousSuffix] == currentText[currentSuffix]
        ) {
            previousSuffix -= 1
            currentSuffix -= 1
        }

        val endExclusive = currentSuffix + 1
        if (endExclusive <= prefix) {
            return null
        }
        val insertedText = currentText.substring(prefix, endExclusive)
        if (insertedText.isBlank()) {
            return null
        }
        return InsertedTextDelta(
            start = prefix,
            endExclusive = endExclusive,
            insertedText = insertedText,
        )
    }

    private fun extractImagePathsFromClipboardFiles(transferable: Transferable): List<String> {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return emptyList()
        }
        val files = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        }.getOrNull().orEmpty()
        return files.mapNotNull { item ->
            val rawPath = when (item) {
                is File -> item.path
                is java.nio.file.Path -> item.toString()
                is String -> item
                else -> null
            } ?: return@mapNotNull null
            normalizeImagePath(rawPath)
        }.distinct()
    }

    private fun extractImagePathsFromClipboardText(transferable: Transferable): List<String> {
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return emptyList()
        }
        val rawText = runCatching {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()?.trim().orEmpty()
        if (rawText.isBlank()) {
            return emptyList()
        }
        return rawText
            .lineSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .mapNotNull { normalizeImagePath(it) }
            .distinct()
            .toList()
    }

    private fun extractClipboardImage(transferable: Transferable): Image? {
        val directImage = if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            runCatching {
                transferable.getTransferData(DataFlavor.imageFlavor) as? Image
            }.onFailure { error ->
                warnPasteDiagnostic("failed to get DataFlavor.imageFlavor: ${error.message}", error)
            }.getOrNull()
        } else {
            null
        }
        if (directImage != null) {
            logPasteDiagnostic("clipboard image resolved via DataFlavor.imageFlavor")
            return directImage
        }

        transferable.transferDataFlavors.forEach { flavor ->
            val data = runCatching { transferable.getTransferData(flavor) }
                .onFailure { error ->
                    warnPasteDiagnostic("failed to read clipboard flavor ${describeDataFlavor(flavor)}: ${error.message}")
                }
                .getOrNull()
                ?: return@forEach
            when (data) {
                is Image -> {
                    logPasteDiagnostic("clipboard image resolved via flavor ${describeDataFlavor(flavor)} as Image")
                    return data
                }
                is ByteArray -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned ByteArray(size=${data.size})")
                    val decoded = decodeImageFromBytes(data)
                    if (decoded != null) return decoded
                }
                is InputStream -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned InputStream")
                    val decoded = decodeImageFromInputStream(data)
                    if (decoded != null) return decoded
                }
                is java.nio.ByteBuffer -> {
                    val remaining = data.remaining()
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned ByteBuffer(remaining=$remaining)")
                    if (remaining > 0) {
                        val bytes = ByteArray(remaining)
                        data.get(bytes)
                        val decoded = decodeImageFromBytes(bytes)
                        if (decoded != null) return decoded
                    }
                }
                is String -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned String(length=${data.length})")
                    val decoded = decodeImageFromDataUrl(data)
                    if (decoded != null) return decoded
                }
                else -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned unsupported type=${data::class.java.name}")
                }
            }
        }
        logPasteDiagnostic("extractClipboardImage completed with no image result")
        return null
    }

    private fun describeClipboardTransferable(transferable: Transferable): String {
        val flavors = transferable.transferDataFlavors
        if (flavors.isEmpty()) return "<none>"
        return flavors.joinToString(", ") { flavor -> describeDataFlavor(flavor) }
    }

    private fun describeDataFlavor(flavor: DataFlavor): String {
        val mime = flavor.mimeType.substringBefore(';').trim()
        val representation = flavor.representationClass?.simpleName ?: "Unknown"
        return "${flavor.humanPresentableName}[$mime->$representation]"
    }

    private fun logPasteDiagnostic(message: String) {
        if (!PASTE_DIAGNOSTICS_ENABLED) return
        logger.warn("[PasteDiag] $message")
    }

    private fun warnPasteDiagnostic(message: String, throwable: Throwable? = null) {
        if (!PASTE_DIAGNOSTICS_ENABLED) return
        if (throwable == null) {
            logger.warn("[PasteDiag] $message")
            return
        }
        logger.warn("[PasteDiag] $message", throwable)
    }

    private fun decodeImageFromBytes(bytes: ByteArray): BufferedImage? {
        if (bytes.isEmpty()) return null
        return runCatching {
            ByteArrayInputStream(bytes).use { stream ->
                ImageIO.read(stream)
            }
        }.getOrNull()
    }

    private fun decodeImageFromInputStream(inputStream: InputStream): BufferedImage? {
        return runCatching {
            inputStream.use { stream ->
                ImageIO.read(stream)
            }
        }.getOrNull()
    }

    private fun decodeImageFromDataUrl(raw: String): BufferedImage? {
        val text = raw.trim()
        if (!text.startsWith("data:image/", ignoreCase = true)) {
            return null
        }
        val commaIndex = text.indexOf(',')
        if (commaIndex <= 0 || commaIndex >= text.lastIndex) {
            return null
        }
        val header = text.substring(0, commaIndex)
        val payload = text.substring(commaIndex + 1)
        if (!header.contains(";base64", ignoreCase = true)) {
            return null
        }
        val bytes = runCatching {
            Base64.getDecoder().decode(payload)
        }.getOrNull() ?: return null
        return decodeImageFromBytes(bytes)
    }

    private fun persistClipboardImage(image: Image): String? {
        val bufferedImage = toBufferedImage(image) ?: return null
        val tempDir = File(System.getProperty("java.io.tmpdir"), CLIPBOARD_IMAGE_TEMP_DIR_NAME)
        if (!tempDir.exists() && !tempDir.mkdirs()) return null

        val tempFile = File(tempDir, "spec-clipboard-${UUID.randomUUID()}.png")
        val written = runCatching {
            ImageIO.write(bufferedImage, "png", tempFile)
        }.getOrElse { false }
        if (!written) return null
        tempFile.deleteOnExit()
        return normalizeImagePath(tempFile.path)
    }

    private fun toBufferedImage(image: Image): BufferedImage? {
        var width = image.getWidth(null)
        var height = image.getHeight(null)
        if (width <= 0 || height <= 0) {
            val icon = ImageIcon(image)
            width = icon.iconWidth
            height = icon.iconHeight
        }
        if (width <= 0 || height <= 0) return null
        if (image is BufferedImage) return image

        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }

    private fun clearImageAttachments(purgeTransientFiles: Boolean = true) {
        val existingImagePaths = imageAttachmentPreviewPanel.getImagePaths()
        imageAttachmentPreviewPanel.clear()
        if (purgeTransientFiles) {
            cleanupTransientClipboardImages(existingImagePaths)
        }
    }

    private fun cleanupTransientClipboardImages(paths: List<String>) {
        paths.forEach { path ->
            removeTransientClipboardImageIfNeeded(path)
        }
    }

    private fun removeTransientClipboardImageIfNeeded(path: String) {
        val key = normalizedPathKey(path)
        if (key.isBlank()) return
        if (!transientClipboardImagePaths.remove(key)) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun normalizedPathKey(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return ""
        val normalized = runCatching {
            Paths.get(trimmed).toAbsolutePath().normalize().toString()
        }.getOrElse { trimmed }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun isSupportedImageExtension(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in SUPPORTED_IMAGE_EXTENSIONS
    }

    private fun normalizeImagePath(rawPath: String): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank()) return null
        val file = File(trimmed).absoluteFile.normalize()
        if (!file.isFile) return null
        if (!isSupportedImageExtension(file.name)) return null
        return file.path
    }

    private fun appendImagePathsToPrompt(prompt: String, imagePaths: List<String>): String {
        if (imagePaths.isEmpty()) return prompt
        val normalizedPrompt = prompt.ifBlank { SpecCodingBundle.message("toolwindow.image.default.prompt") }
        val attachmentBlock = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.image.context.header"))
            imagePaths.forEach { path ->
                appendLine("- $path")
            }
        }.trimEnd()
        return "$normalizedPrompt\n\n$attachmentBlock"
    }

    private fun buildVisibleInput(rawInput: String, imagePaths: List<String>): String {
        if (imagePaths.isEmpty()) {
            return rawInput.ifBlank { SpecCodingBundle.message("toolwindow.image.default.prompt") }
        }
        val names = imagePaths.joinToString(", ") { path ->
            File(path).name.ifBlank { path }
        }
        val attachmentLine = SpecCodingBundle.message("toolwindow.image.visible.entry", names)
        if (rawInput.isBlank()) {
            return attachmentLine
        }
        return "$rawInput\n$attachmentLine"
    }

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
            appendLine(promptBlocks)
            appendLine()
            append(modeAwareInput)
        }
    }

    private fun buildSpecModePrompt(input: String): String {
        val workflowId = activeSpecWorkflowId?.trim()?.ifBlank { null }
        val workflowHint = if (workflowId != null) {
            buildString {
                append("Workflow=$workflowId")
                append(" (docs: .spec-coding/specs/$workflowId/{requirements,design,tasks}.md)")
            }
        } else {
            "No active workflow. If needed, run /spec <requirements> first."
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

        val slashToken = extractSlashCommandToken(trimmedCommand)
        val providerId = providerComboBox.selectedItem as? String
        val providerSlashCommand = resolveProviderSlashCommand(trimmedCommand, providerId)
        if (providerSlashCommand != null) {
            executeProviderSlashCommand(
                slashCommand = trimmedCommand,
                providerId = providerId,
                commandInfo = providerSlashCommand,
            )
            return
        }

        if (slashToken != null && isInteractiveOnlySessionSlashCommand(providerId, slashToken)) {
            addErrorMessage(
                SpecCodingBundle.message(
                    "toolwindow.slash.command.interactive.only",
                    providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                    "/$slashToken",
                )
            )
            return
        }

        if (!isRegisteredSkillSlashCommand(trimmedCommand)) {
            addErrorMessage(
                SpecCodingBundle.message(
                    "toolwindow.slash.command.unsupported.provider",
                    providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                    trimmedCommand,
                )
            )
            return
        }

        val operation = mapSlashCommandToOperation(trimmedCommand)
        if (operation != null && !checkOperationPermission(operation, trimmedCommand)) {
            return
        }

        executeLocalSkillSlashCommand(command = trimmedCommand, providerId = providerId)
    }

    private fun executeLocalSkillSlashCommand(command: String, providerId: String?) {
        val sessionId = ensureActiveSession(command, providerId)

        clearComposerInput()
        appendUserMessage(command)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, command)
        }

        stopRequested.set(false)
        activeLlmRequest = null
        setSendingState(true)

        activeOperationJob = scope.launch {
            try {
                val context = buildSkillContextForSlashCommand()
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
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                stopRequested.set(false)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun handleSpecCommand(command: String, sessionTitleSeed: String = command) {
        val providerId = providerComboBox.selectedItem as? String
        val createsNewWorkflow = isSpecWorkflowCreateCommand(command)
        if (createsNewWorkflow && currentInteractionMode() == ChatInteractionMode.SPEC) {
            startNewSession()
        }
        val sessionId = ensureActiveSpecSession(
            titleSeed = sessionTitleSeed,
            providerId = providerId,
            specTaskId = if (createsNewWorkflow) null else resolveMostRecentSpecWorkflowIdOrNull(),
        )
        val progressPanel = addAssistantMessage()
        val hasProgressEvent = AtomicBoolean(false)

        clearComposerInput()
        appendUserMessage(command)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, command)
        }

        stopRequested.set(false)
        activeLlmRequest = null
        setSendingState(true)

        activeOperationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = executeSpecCommand(command) { event ->
                    val sanitizedEvent = sanitizeStreamEvent(event) ?: return@executeSpecCommand
                    hasProgressEvent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        progressPanel.appendStreamContent("", listOf(sanitizedEvent))
                    }
                }
                bindSessionToSpecTaskIfNeeded(sessionId, result.metadata?.workflowId)
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
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
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
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                stopRequested.set(false)
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
                val workflow = resolveActiveSpecWorkflow()
                    ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))

                if (commandArg.isBlank()) {
                    if (workflow.currentPhase == SpecPhase.SPECIFY) {
                        plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "generate"))
                    } else {
                        val previousPhase = workflow.currentPhase.previous()
                        val previousContent = previousPhase
                            ?.let(workflow::getDocument)
                            ?.content
                            ?.trim()
                            .orEmpty()
                        if (previousContent.isBlank()) {
                            plainSpecResult(
                                SpecCodingBundle.message(
                                    "toolwindow.spec.command.previousRequired",
                                    specPhaseDisplayName(workflow.currentPhase),
                                    previousPhase?.let(::specPhaseDisplayName) ?: SpecCodingBundle.message("common.unknown"),
                                ),
                            )
                        } else {
                            generateSpecForActiveWorkflow(
                                input = "",
                                sourceCommand = command,
                                onProgress = onProgress,
                            )
                        }
                    }
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

        val latestId = runCatching {
            specEngine.listWorkflows()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .sorted()
                .lastOrNull()
        }.getOrNull() ?: return null
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
                    if (contentManager.selectedContent != historyContent && contentManager.contents.contains(historyContent)) {
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

        clearComposerInput()
        appendUserMessage(command)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, command)
        }

        stopRequested.set(false)
        activeLlmRequest = null
        setSendingState(true)

        activeOperationJob = scope.launch {
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
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                stopRequested.set(false)
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
        val snapshot = skillExecutor.discoverAvailableSkills(forceReload = true)
        val skills = snapshot.skills
        val lines = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.skill.available.header", skills.size))
            appendLine(SpecCodingBundle.message("toolwindow.skill.available.roots"))
            snapshot.roots.forEach { root ->
                val scopeLabel = when (root.scope) {
                    com.eacape.speccodingplugin.skill.SkillScope.GLOBAL ->
                        SpecCodingBundle.message("toolwindow.skill.available.scope.global")

                    com.eacape.speccodingplugin.skill.SkillScope.PROJECT ->
                        SpecCodingBundle.message("toolwindow.skill.available.scope.project")
                }
                val rootStatus = if (root.exists) {
                    SpecCodingBundle.message("toolwindow.skill.available.root.exists")
                } else {
                    SpecCodingBundle.message("toolwindow.skill.available.root.missing")
                }
                appendLine(
                    SpecCodingBundle.message(
                        "toolwindow.skill.available.root.item",
                        scopeLabel,
                        root.label,
                        root.path,
                        rootStatus,
                    ),
                )
            }
            skills.forEach { skill ->
                val sourceLabel = when (skill.sourceType) {
                    com.eacape.speccodingplugin.skill.SkillSourceType.BUILTIN ->
                        SpecCodingBundle.message("toolwindow.skill.available.source.builtin")

                    com.eacape.speccodingplugin.skill.SkillSourceType.YAML ->
                        SpecCodingBundle.message("toolwindow.skill.available.source.yaml")

                    com.eacape.speccodingplugin.skill.SkillSourceType.MARKDOWN ->
                        SpecCodingBundle.message("toolwindow.skill.available.source.markdown")
                }
                val sourceWithScope = skill.scope?.let { scope ->
                    val scopeText = when (scope) {
                        com.eacape.speccodingplugin.skill.SkillScope.GLOBAL ->
                            SpecCodingBundle.message("toolwindow.skill.available.scope.global")

                        com.eacape.speccodingplugin.skill.SkillScope.PROJECT ->
                            SpecCodingBundle.message("toolwindow.skill.available.scope.project")
                    }
                    "$sourceLabel / $scopeText"
                } ?: sourceLabel
                appendLine(
                    SpecCodingBundle.message(
                        "toolwindow.skill.available.item.ext",
                        skill.slashCommand,
                        skill.description,
                        sourceWithScope,
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
        userMessageRawContent.clear()
        currentSessionId = null
        activeSpecWorkflowId = null
        sessionIsolationService.clearActiveSession()
        specSidebarPanel.clearFocusedWorkflow()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        clearImageAttachments()
        clearComposerInput()
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

                activeSpecWorkflowId = session.specTaskId?.trim()?.ifBlank { null }
                restoreSessionMessages(messages)
                currentSessionId = session.id
                sessionIsolationService.activateSession(session.id)
                activeSpecWorkflowId?.let { workflowId ->
                    if (specSidebarVisible) {
                        specSidebarPanel.focusWorkflow(workflowId)
                    }
                }
                refreshSpecWorkflowComboBox(selectWorkflowId = activeSpecWorkflowId)
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
        userMessageRawContent.clear()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        clearImageAttachments()
        currentAssistantPanel = null
        val interactionMode = currentInteractionMode()
        val continueHandler = continueHandlerFor(interactionMode)
        val workflowSectionsEnabled = workflowSectionRenderingEnabledFor(interactionMode)

        for (message in messages) {
            when (message.role) {
                ConversationRole.USER -> {
                    appendUserMessage(content = message.content, rawContent = message.content)
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
                             onContinue = continueHandler,
                             onWorkflowFileOpen = ::handleWorkflowFileOpen,
                             onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
                             workflowSectionsEnabled = workflowSectionsEnabled,
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
        refreshContinueActions(interactionMode)
        messagesPanel.scrollToBottom()
    }

    private fun ensureActiveSpecSession(titleSeed: String, providerId: String?, specTaskId: String?): String? {
        currentSessionId?.let { return it }
        sessionIsolationService.activeSessionId()?.let {
            currentSessionId = it
            return it
        }

        val normalizedSpecTaskId = specTaskId?.trim()?.ifBlank { null }
        val workflowTitle = normalizedSpecTaskId
            ?.let { id -> specEngine.loadWorkflow(id).getOrNull()?.title }
            ?.trim()
            ?.ifBlank { null }
        val title = (workflowTitle ?: titleSeed.lines().firstOrNull().orEmpty().trim())
            .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
            .take(80)

        val created = sessionManager.createSession(
            title = title,
            specTaskId = normalizedSpecTaskId,
            modelProvider = providerId,
        )

        return created.fold(
            onSuccess = { session ->
                currentSessionId = session.id
                sessionIsolationService.activateSession(session.id)
                session.id
            },
            onFailure = { error ->
                logger.warn("Failed to create spec session", error)
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

    private fun resolveMostRecentSpecWorkflowIdOrNull(): String? {
        val explicitId = activeSpecWorkflowId?.trim()?.ifBlank { null }
        if (!explicitId.isNullOrBlank()) {
            return explicitId
        }
        return runCatching {
            specEngine.listWorkflows()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .sorted()
                .lastOrNull()
        }.getOrNull()
    }

    private fun bindSessionToSpecTaskIfNeeded(sessionId: String?, workflowId: String?) {
        val normalizedSessionId = sessionId?.trim()?.ifBlank { null } ?: return
        val normalizedWorkflowId = workflowId?.trim()?.ifBlank { null } ?: return
        val currentSpecTaskId = sessionManager.getSession(normalizedSessionId)
            ?.specTaskId
            ?.trim()
            ?.ifBlank { null }
        if (!currentSpecTaskId.isNullOrBlank()) {
            return
        }
        sessionManager.updateSessionSpecTaskId(normalizedSessionId, normalizedWorkflowId)
            .onFailure { error ->
                logger.warn("Failed to bind session to spec workflow: $normalizedWorkflowId", error)
            }
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
            val content = runCatching {
                ReadAction.compute<String, Throwable> {
                    String(vf.contentsToByteArray(), Charsets.UTF_8)
                }
            }.getOrElse { error ->
                logger.warn("Failed to read explicit context file: ${item.filePath}", error)
                return@map item
            }
            item.copy(content = content, tokenEstimate = content.length / 4)
        }
    }

    private fun collectContextSnapshotSafely(explicitItems: List<ContextItem>) =
        runCatching {
            contextCollector.collectForItems(
                explicitItems = explicitItems,
                config = CHAT_CONTEXT_CONFIG,
            )
        }.getOrElse { error ->
            logger.warn("Failed to collect auto context; fallback to explicit context only", error)
            ContextTrimmer.trim(explicitItems, CHAT_CONTEXT_CONFIG.tokenBudget)
        }

    private fun appendUserMessage(content: String, rawContent: String? = null): ChatMessagePanel {
        val panel = ChatMessagePanel(
            ChatMessagePanel.MessageRole.USER, content,
            onDelete = ::handleDeleteMessage,
        )
        panel.finishMessage()
        messagesPanel.addMessage(panel)
        if (rawContent != null) {
            userMessageRawContent[panel] = rawContent
        } else {
            userMessageRawContent.remove(panel)
        }
        return panel
    }

    private fun addAssistantMessage(): ChatMessagePanel {
        val mode = currentInteractionMode()
        val panel = ChatMessagePanel(
            ChatMessagePanel.MessageRole.ASSISTANT,
            onDelete = ::handleDeleteMessage,
            onRegenerate = ::handleRegenerateMessage,
            onContinue = continueHandlerFor(mode),
            onWorkflowFileOpen = ::handleWorkflowFileOpen,
            onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
            workflowSectionsEnabled = workflowSectionRenderingEnabledFor(mode),
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
            onContinueMessage = continueHandlerFor(currentInteractionMode()),
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

    private fun editSpecWorkflowMetadata(workflowId: String) {
        val normalizedId = workflowId.trim()
        if (normalizedId.isBlank() || project.isDisposed || _isDisposed) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val workflowResult = specEngine.loadWorkflow(normalizedId)
            val workflow = workflowResult.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                if (workflow == null) {
                    showStatus(
                        SpecCodingBundle.message(
                            "toolwindow.spec.sidebar.loadFailed",
                            workflowResult.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                    return@invokeLater
                }

                val dialog = EditSpecWorkflowDialog(
                    initialTitle = workflow.title.ifBlank { workflow.id },
                    initialDescription = workflow.description,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLater
                }
                val title = dialog.resultTitle ?: return@invokeLater
                val description = dialog.resultDescription ?: ""

                scope.launch(Dispatchers.IO) {
                    val updateResult = specEngine.updateWorkflowMetadata(
                        workflowId = normalizedId,
                        title = title,
                        description = description,
                    )
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        updateResult.onSuccess { updated ->
                            activeSpecWorkflowId = updated.id
                            if (specSidebarVisible) {
                                if (specSidebarPanel.hasFocusedWorkflow(updated.id)) {
                                    specSidebarPanel.focusWorkflow(updated.id)
                                } else {
                                    specSidebarPanel.refreshCurrentWorkflow()
                                }
                            }
                            publishSpecWorkflowChanged(updated.id, reason = "workflow_metadata_updated")
                        }.onFailure { error ->
                            showStatus(
                                SpecCodingBundle.message(
                                    "spec.workflow.error",
                                    error.message ?: SpecCodingBundle.message("common.unknown"),
                                ),
                                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildSkillContextForSlashCommand(): SkillContext {
        val autoContext = contextCollector.collectContext()
        val selectedCode = autoContext.items
            .firstOrNull { it.type == ContextType.SELECTED_CODE }
            ?.content
        val currentFile = autoContext.items
            .firstOrNull { it.type == ContextType.CURRENT_FILE }
            ?.filePath
        return SkillContext(
            selectedCode = selectedCode,
            currentFile = currentFile,
        )
    }

    private fun resolveProviderSlashCommand(command: String, providerId: String?): CliSlashCommandInfo? {
        val normalizedProvider = providerId?.trim().orEmpty()
        if (normalizedProvider.isBlank()) {
            return null
        }
        val slashToken = extractSlashCommandToken(command) ?: return null
        return CliDiscoveryService.getInstance().listSlashCommands()
            .firstOrNull { item ->
                item.providerId.equals(normalizedProvider, ignoreCase = true) &&
                    item.command.equals(slashToken, ignoreCase = true)
            }
    }

    private fun extractSlashCommandToken(command: String): String? {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/")) {
            return null
        }
        return trimmed
            .removePrefix("/")
            .substringBefore(" ")
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { null }
    }

    private fun isRegisteredSkillSlashCommand(command: String): Boolean {
        val token = extractSlashCommandToken(command) ?: return false
        return skillExecutor.hasSkillSlashCommand(token, forceReload = true)
    }

    private fun executeProviderSlashCommand(
        slashCommand: String,
        providerId: String?,
        commandInfo: CliSlashCommandInfo,
    ) {
        if (isInteractiveOnlyProviderSlashCommand(providerId, commandInfo)) {
            addErrorMessage(
                SpecCodingBundle.message(
                    "toolwindow.slash.command.interactive.only",
                    providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                    "/${commandInfo.command}",
                )
            )
            return
        }
        val shellCommand = buildProviderSlashShellCommand(
            slashCommand = slashCommand,
            providerId = providerId,
            commandInfo = commandInfo,
        ) ?: run {
            addErrorMessage(
                SpecCodingBundle.message(
                    "toolwindow.slash.command.unsupported.provider",
                    providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                    slashCommand,
                )
            )
            return
        }
        val sessionId = ensureActiveSession(slashCommand, providerId)
        clearComposerInput()
        appendUserMessage(slashCommand)
        if (sessionId != null) {
            persistMessage(sessionId, ConversationRole.USER, slashCommand)
        }
        executeShellCommand(
            command = shellCommand,
            requestDescription = "Provider slash command: $slashCommand",
        )
    }

    private fun isInteractiveOnlyProviderSlashCommand(providerId: String?, commandInfo: CliSlashCommandInfo): Boolean {
        val provider = providerId?.trim().orEmpty()
        val command = commandInfo.command.trim().lowercase(Locale.ROOT)
        if (command.isBlank()) {
            return false
        }
        return when {
            provider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) -> {
                command in CLAUDE_INTERACTIVE_ONLY_CLI_COMMANDS
            }

            provider.equals(CodexCliLlmProvider.ID, ignoreCase = true) -> {
                command in CODEX_INTERACTIVE_ONLY_CLI_COMMANDS
            }

            else -> false
        }
    }

    private fun isInteractiveOnlySessionSlashCommand(providerId: String?, slashToken: String): Boolean {
        val normalizedToken = slashToken.trim().lowercase(Locale.ROOT)
        if (normalizedToken.isBlank()) {
            return false
        }
        val provider = providerId?.trim().orEmpty()
        return when {
            provider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) -> {
                normalizedToken in CLAUDE_SESSION_ONLY_SLASH_COMMANDS
            }

            provider.equals(CodexCliLlmProvider.ID, ignoreCase = true) -> {
                normalizedToken in CODEX_SESSION_ONLY_SLASH_COMMANDS
            }

            else -> false
        }
    }

    private fun buildProviderSlashShellCommand(
        slashCommand: String,
        providerId: String?,
        commandInfo: CliSlashCommandInfo,
    ): String? {
        val normalizedProvider = providerId?.trim().orEmpty()
        if (normalizedProvider.isBlank()) {
            return null
        }
        val slashToken = extractSlashCommandToken(slashCommand) ?: return null
        val args = slashCommand.removePrefix("/")
            .trim()
            .substringAfter(" ", "")
            .trim()

        val cliExecutable = when {
            normalizedProvider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) -> {
                CliDiscoveryService.getInstance().claudeInfo.path.ifBlank { "claude" }
            }

            normalizedProvider.equals(CodexCliLlmProvider.ID, ignoreCase = true) -> {
                CliDiscoveryService.getInstance().codexInfo.path.ifBlank { "codex" }
            }

            else -> {
                return null
            }
        }
        val invocationToken = if (
            normalizedProvider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) &&
            commandInfo.invocationKind == CliSlashInvocationKind.OPTION
        ) {
            "--$slashToken"
        } else {
            slashToken
        }
        val executable = quoteShellTokenIfNeeded(cliExecutable)
        return buildString {
            append(executable)
            append(' ')
            append(invocationToken)
            if (args.isNotBlank()) {
                append(' ')
                append(args)
            }
        }.trim()
    }

    private fun quoteShellTokenIfNeeded(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
            return trimmed
        }
        return if (trimmed.any { it.isWhitespace() }) {
            "\"$trimmed\""
        } else {
            trimmed
        }
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
        stopRequested.set(false)
        activeLlmRequest = null
        setSendingState(true)

        activeOperationJob = scope.launch(Dispatchers.IO) {
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
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
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
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                stopRequested.set(false)
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
        sendButton.isEnabled = !isRestoringSession
        refreshActionButtonTexts()
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
        val stopMode = isGenerating && !isRestoringSession
        val tooltipKey = if (stopMode) {
            "toolwindow.stop"
        } else {
            "toolwindow.send"
        }
        val tooltip = SpecCodingBundle.message(tooltipKey)
        sendButton.toolTipText = tooltip
        sendButton.accessibleContext.accessibleName = tooltip
        sendButton.stopMode = stopMode
        sendButton.icon = if (stopMode) {
            null
        } else {
            AllIcons.Actions.Execute
        }
    }

    private fun configureActionButtons() {
        sendButton.icon = AllIcons.Actions.Execute
        sendButton.text = ""
        sendButton.isFocusable = false
        sendButton.isFocusPainted = false
        sendButton.isBorderPainted = false
        sendButton.isOpaque = false
        sendButton.isRolloverEnabled = true
        sendButton.margin = JBUI.emptyInsets()
        sendButton.preferredSize = ACTION_ICON_BUTTON_SIZE
        sendButton.minimumSize = ACTION_ICON_BUTTON_SIZE
        sendButton.maximumSize = ACTION_ICON_BUTTON_SIZE
        sendButton.putClientProperty("JButton.buttonType", "toolbar")
        refreshActionButtonTexts()
    }

    private class StopAwareButton : JButton() {
        private val stopGlyph = JBColor(
            Color(194, 66, 56),
            Color(235, 118, 109),
        )
        private val stopGlyphDisabled = JBColor(
            Color(170, 170, 170),
            Color(126, 126, 126),
        )
        private val stopBgIdle = JBColor(
            Color(194, 66, 56, 24),
            Color(235, 118, 109, 36),
        )
        private val stopBgHover = JBColor(
            Color(194, 66, 56, 34),
            Color(235, 118, 109, 50),
        )
        private val stopBgPressed = JBColor(
            Color(194, 66, 56, 48),
            Color(235, 118, 109, 64),
        )

        var stopMode: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                isContentAreaFilled = !value
                repaint()
            }

        override fun paintComponent(g: Graphics) {
            if (!stopMode) {
                super.paintComponent(g)
                return
            }
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val bgSize = minOf(width, height, JBUI.scale(18))
                val bgX = (width - bgSize) / 2
                val bgY = (height - bgSize) / 2
                val arc = JBUI.scale(8)
                g2.color = when {
                    !isEnabled -> stopBgIdle
                    model.isPressed -> stopBgPressed
                    model.isRollover -> stopBgHover
                    else -> stopBgIdle
                }
                g2.fillRoundRect(bgX, bgY, bgSize, bgSize, arc, arc)

                val glyphSize = maxOf(JBUI.scale(6), minOf(JBUI.scale(8), bgSize - JBUI.scale(8)))
                val glyphX = bgX + (bgSize - glyphSize) / 2
                val glyphY = bgY + (bgSize - glyphSize) / 2
                val glyphArc = JBUI.scale(2)
                g2.color = if (isEnabled) stopGlyph else stopGlyphDisabled
                g2.fillRoundRect(glyphX, glyphY, glyphSize, glyphSize, glyphArc, glyphArc)
            }
            finally {
                g2.dispose()
            }
        }
    }

    private fun configureSpecSidebarToggleButton() {
        specSidebarToggleButton.icon = AllIcons.FileTypes.Text
        specSidebarToggleButton.isFocusable = false
        specSidebarToggleButton.isFocusPainted = false
        specSidebarToggleButton.margin = JBUI.emptyInsets()
        specSidebarToggleButton.preferredSize = ACTION_ICON_BUTTON_SIZE
        specSidebarToggleButton.minimumSize = ACTION_ICON_BUTTON_SIZE
        specSidebarToggleButton.maximumSize = ACTION_ICON_BUTTON_SIZE
        specSidebarToggleButton.text = ""
        specSidebarToggleButton.putClientProperty("JButton.buttonType", "toolbar")
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
            AllIcons.Actions.Close
        } else {
            AllIcons.FileTypes.Text
        }
        specSidebarToggleButton.toolTipText = tooltip
        specSidebarToggleButton.accessibleContext.accessibleName = tooltip
    }

    private fun currentInteractionMode(): ChatInteractionMode {
        return interactionModeComboBox.selectedItem as? ChatInteractionMode ?: ChatInteractionMode.VIBE
    }

    private fun continueHandlerFor(mode: ChatInteractionMode): ((ChatMessagePanel) -> Unit)? {
        return if (mode == ChatInteractionMode.SPEC) ::handleContinueMessage else null
    }

    private fun workflowSectionRenderingEnabledFor(mode: ChatInteractionMode): Boolean {
        return mode == ChatInteractionMode.SPEC
    }

    private fun refreshContinueActions(mode: ChatInteractionMode) {
        val handler = continueHandlerFor(mode)
        val workflowSectionsEnabled = workflowSectionRenderingEnabledFor(mode)
        messagesPanel.getAllMessages()
            .filter { message -> message.role == ChatMessagePanel.MessageRole.ASSISTANT }
            .forEach { message ->
                message.updateContinueAction(handler)
                message.setWorkflowSectionsEnabled(workflowSectionsEnabled)
            }
    }

    private fun onInteractionModeChanged() {
        val mode = currentInteractionMode()
        val previousMode = lastInteractionMode
        if (mode == previousMode) {
            return
        }
        lastInteractionMode = mode
        if (mode == ChatInteractionMode.SPEC) {
            val workflowId = resolveMostRecentSpecWorkflowIdOrNull()
            startNewSession()
            activeSpecWorkflowId = workflowId
            refreshSpecWorkflowComboBox(selectWorkflowId = workflowId)
        }
        applyInteractionModeUi(mode)
        emitChatModeChangedHook(previousMode = previousMode, currentMode = mode)
    }

    private fun emitChatModeChangedHook(
        previousMode: ChatInteractionMode,
        currentMode: ChatInteractionMode,
    ) {
        HookManager.getInstance(project).trigger(
            event = HookEvent.CHAT_MODE_CHANGED,
            triggerContext = HookTriggerContext(
                specStage = currentMode.name,
                metadata = mapOf(
                    "projectName" to project.name,
                    "previousMode" to previousMode.name,
                    "currentMode" to currentMode.name,
                ),
            ),
        )
    }

    private fun applyInteractionModeUi(mode: ChatInteractionMode) {
        refreshContinueActions(mode)
        specSidebarToggleButton.isVisible = mode == ChatInteractionMode.SPEC
        specWorkflowComboBox.isVisible = mode == ChatInteractionMode.SPEC && specWorkflowComboBox.itemCount > 0
        if (mode != ChatInteractionMode.SPEC) {
            applySpecSidebarVisibility(visible = false, persist = false)
            return
        }
        applySpecSidebarVisibility(visible = windowStateStore.snapshot().chatSpecSidebarVisible, persist = false)
    }

    private fun configureSplitPaneDivider() {
        chatSplitPane.ui = SidebarGripSplitPaneUI()
        chatSplitPane.dividerSize = JBUI.scale(SPEC_SIDEBAR_DIVIDER_SIZE)
        chatSplitPane.isOneTouchExpandable = false
        (chatSplitPane.ui as? BasicSplitPaneUI)?.divider?.let { divider ->
            divider.cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            divider.background = JBColor(
                Color(236, 240, 246),
                Color(74, 80, 89),
            )
            divider.border = JBUI.Borders.customLine(
                JBColor(
                    Color(217, 223, 232),
                    Color(87, 94, 105),
                ),
                0,
                1,
                0,
                1,
            )
        }
    }

    private fun scheduleDividerPersist() {
        if (!specSidebarVisible) return
        dividerPersistTimer?.stop()
        val timer = Timer(SPEC_SIDEBAR_DIVIDER_PERSIST_DEBOUNCE_MILLIS) {
            dividerPersistTimer = null
            if (!project.isDisposed && !_isDisposed && specSidebarVisible) {
                windowStateStore.updateChatSpecSidebar(true, specSidebarDividerLocation)
            }
        }
        timer.isRepeats = false
        dividerPersistTimer = timer
        timer.start()
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

    private fun updateSpecWorkflowComboSize() {
        if (currentInteractionMode() != ChatInteractionMode.SPEC) {
            return
        }
        val title = (specWorkflowComboBox.selectedItem as? SpecWorkflowOption)
            ?.title
            ?.trim()
            .orEmpty()
        val metrics = specWorkflowComboBox.getFontMetrics(specWorkflowComboBox.font)
        val padding = JBUI.scale(SPEC_WORKFLOW_COMBO_TEXT_OVERHEAD_PX)
        val desiredWidth = if (title.isBlank()) {
            JBUI.scale(SPEC_WORKFLOW_COMBO_MIN_WIDTH)
        } else {
            (metrics.stringWidth(title) + padding).coerceIn(
                JBUI.scale(SPEC_WORKFLOW_COMBO_MIN_WIDTH),
                JBUI.scale(SPEC_WORKFLOW_COMBO_MAX_WIDTH),
            )
        }
        val height = JBUI.scale(28)
        val size = Dimension(desiredWidth, height)
        specWorkflowComboBox.preferredSize = size
        specWorkflowComboBox.minimumSize = size
        specWorkflowComboBox.maximumSize = size
        specWorkflowComboBox.revalidate()
        specWorkflowComboBox.repaint()
    }

    private fun ellipsizeToWidth(text: String, maxWidth: Int, metrics: FontMetrics): String {
        if (maxWidth <= 0) return ""
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return ""
        if (metrics.stringWidth(normalized) <= maxWidth) return normalized
        val ellipsis = "…"
        val ellipsisWidth = metrics.stringWidth(ellipsis)
        val available = (maxWidth - ellipsisWidth).coerceAtLeast(0)
        var low = 0
        var high = normalized.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val width = metrics.stringWidth(normalized.substring(0, mid))
            if (width <= available) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        val prefix = normalized.substring(0, low).trimEnd()
        return if (prefix.isEmpty()) ellipsis else prefix + ellipsis
    }

    private fun updateComboTooltips() {
        providerComboBox.toolTipText = providerDisplayName(providerComboBox.selectedItem as? String)
        modelComboBox.toolTipText = (modelComboBox.selectedItem as? ModelInfo)?.name
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        val selectedWorkflow = specWorkflowComboBox.selectedItem as? SpecWorkflowOption
        val workflowId = selectedWorkflow?.workflowId?.trim().orEmpty()
        val workflowTitle = selectedWorkflow?.title?.trim().orEmpty()
        specWorkflowComboBox.toolTipText = workflowId.ifBlank { null }?.let { id ->
            if (workflowTitle.isBlank() || workflowTitle == id) {
                SpecCodingBundle.message("toolwindow.spec.sidebar.workflow", id)
            } else {
                "$workflowTitle\n${SpecCodingBundle.message("toolwindow.spec.sidebar.workflow", id)}"
            }
        }
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
        val focus = if (currentInteractionMode() == ChatInteractionMode.SPEC) {
            resolveMostRecentSpecWorkflowIdOrNull()
                ?.let { workflowId ->
                    sanitizeContinueFocus(
                        "读 .spec-coding/specs/$workflowId/tasks.md，执行第一个未完成任务(- [ ])并更新勾选",
                    )
                }
                ?: resolveContinueFocus(panel)
        } else {
            resolveContinueFocus(panel)
        }
        val continuePrompt = if (!focus.isNullOrBlank()) {
            SpecCodingBundle.message("toolwindow.continue.dynamicPrompt", focus)
        } else {
            SpecCodingBundle.message("toolwindow.continue.defaultPrompt")
        }
        setComposerInput(continuePrompt)
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

        executeShellCommand(
            command = normalizedCommand,
            requestDescription = "Workflow quick action command: $normalizedCommand",
        )
    }

    private fun executeShellCommand(command: String, requestDescription: String) {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank() || project.isDisposed || _isDisposed) {
            return
        }
        val request = OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = requestDescription,
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
        val beforeSnapshot = captureWorkspaceSnapshot()
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

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            showStatus(SpecCodingBundle.message("chat.workflow.action.runCommand.running", command))
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
        persistWorkflowCommandChangeset(command, execution, beforeSnapshot)
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
            if (!isGenerating && !isRestoringSession) {
                hideStatus()
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

    private fun captureWorkspaceSnapshot(): WorkspaceChangesetCollector.Snapshot? {
        val basePath = project.basePath ?: return null
        val root = runCatching { Paths.get(basePath).toAbsolutePath().normalize() }.getOrNull() ?: return null
        return runCatching {
            WorkspaceChangesetCollector.capture(root)
        }.onFailure {
            logger.debug("Failed to capture workspace snapshot", it)
        }.getOrNull()
    }

    private fun persistWorkflowCommandChangeset(
        command: String,
        execution: WorkflowCommandExecutionResult,
        beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
    ) {
        val before = beforeSnapshot ?: return
        val basePath = project.basePath ?: return
        val root = runCatching { Paths.get(basePath).toAbsolutePath().normalize() }.getOrNull() ?: return

        val after = runCatching {
            WorkspaceChangesetCollector.capture(root)
        }.onFailure {
            logger.debug("Failed to capture workspace snapshot after command", it)
        }.getOrNull() ?: return

        val changes = WorkspaceChangesetCollector.diff(root, before, after)

        val status = when {
            execution.stoppedByUser -> "stopped"
            execution.timedOut -> "timeout"
            execution.success -> "success"
            execution.error != null -> "error"
            else -> "failed"
        }
        val metadata = linkedMapOf(
            "source" to "workflow-command",
            "command" to command.take(WORKFLOW_CHANGESET_COMMAND_MAX_LENGTH),
            "status" to status,
        )
        execution.exitCode?.let { metadata["exitCode"] = it.toString() }
        if (execution.timedOut) metadata["timedOut"] = "true"
        if (execution.stoppedByUser) metadata["stoppedByUser"] = "true"
        if (execution.outputTruncated) metadata["outputTruncated"] = "true"
        if (changes.isEmpty()) metadata["noFileChange"] = "true"

        val changeset = Changeset(
            id = UUID.randomUUID().toString(),
            description = "Command: ${command.take(WORKFLOW_CHANGESET_COMMAND_MAX_LENGTH)}",
            changes = changes,
            metadata = metadata,
        )
        runCatching {
            changesetStore.save(changeset)
        }.onFailure {
            logger.warn("Failed to persist workflow command changeset", it)
        }
    }

    private fun persistAssistantResponseChangeset(
        requestText: String,
        providerId: String?,
        modelId: String?,
        beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
        hasExecutionTrace: Boolean,
    ) {
        val before = beforeSnapshot ?: return
        val basePath = project.basePath ?: return
        val root = runCatching { Paths.get(basePath).toAbsolutePath().normalize() }.getOrNull() ?: return

        val after = runCatching {
            WorkspaceChangesetCollector.capture(root)
        }.onFailure {
            logger.debug("Failed to capture workspace snapshot after assistant response", it)
        }.getOrNull() ?: return

        val changes = WorkspaceChangesetCollector.diff(root, before, after)
        if (changes.isEmpty() && !hasExecutionTrace) {
            return
        }

        val requestSummary = requestText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(ASSISTANT_CHANGESET_REQUEST_MAX_LENGTH)
            .ifBlank { SpecCodingBundle.message("common.unknown") }

        val metadata = linkedMapOf(
            "source" to "assistant-response",
            "request" to requestSummary,
        )
        providerId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { metadata["provider"] = it }
        modelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { metadata["model"] = it }
        if (hasExecutionTrace) {
            metadata["trace"] = "true"
        }
        if (changes.isEmpty()) {
            metadata["status"] = "no-file-change"
        }

        val changeset = Changeset(
            id = UUID.randomUUID().toString(),
            description = "Response: $requestSummary",
            changes = changes,
            metadata = metadata,
        )
        runCatching {
            changesetStore.save(changeset)
        }.onFailure {
            logger.warn("Failed to persist assistant response changeset", it)
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
        val icon = when {
            execution.stoppedByUser -> {
                "⏹"
            }

            execution.timedOut -> {
                "⏱"
            }

            execution.error != null -> {
                "⚠"
            }

            execution.success -> {
                "✅"
            }

            else -> {
                "❌"
            }
        }
        val statusText = when {
            execution.stoppedByUser -> SpecCodingBundle.message("chat.workflow.action.stopCommand.stopped", command)
            execution.timedOut -> SpecCodingBundle.message(
                "chat.workflow.action.runCommand.timeout",
                WORKFLOW_COMMAND_TIMEOUT_SECONDS,
                command,
            )
            execution.error != null -> SpecCodingBundle.message("chat.workflow.action.runCommand.error", command)
            execution.success -> SpecCodingBundle.message(
                "chat.workflow.action.runCommand.success",
                execution.exitCode ?: 0,
                command,
            )
            else -> SpecCodingBundle.message(
                "chat.workflow.action.runCommand.failed",
                execution.exitCode ?: -1,
                command,
            )
        }

        var output = sanitizeDisplayText(execution.output, dropGarbledLines = false)
            .ifBlank { SpecCodingBundle.message("chat.workflow.action.runCommand.noOutput") }
        if (execution.outputTruncated) {
            output += "\n${SpecCodingBundle.message("chat.workflow.action.runCommand.outputTruncated", WORKFLOW_COMMAND_OUTPUT_MAX_CHARS)}"
        }

        return buildString {
            appendLine("$icon $statusText")
            appendLine("${SpecCodingBundle.message("chat.workflow.action.runCommand.outputLabel")}：")
            append(output)
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
        userMessageRawContent.remove(panel)
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

        val userInput = userMessageRawContent[userPanel] ?: userPanel.getContent()

        // 删除当前 assistant 消息
        messagesPanel.removeMessage(panel)
        rebuildConversationHistory()

        // 重新发送
        val providerId = providerComboBox.selectedItem as? String
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val operationMode = modeManager.getCurrentMode()
        val beforeSnapshot = captureWorkspaceSnapshot()
        val requestId = UUID.randomUUID().toString()
        val resolvedProviderId = resolveProviderIdForRequest(providerId)
        stopRequested.set(false)
        activeLlmRequest = ActiveLlmRequest(providerId = resolvedProviderId, requestId = requestId)
        setSendingState(true)
        currentAssistantPanel = addAssistantMessage()

        activeOperationJob = scope.launch {
            val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
            val assistantContent = StringBuilder()
            val pendingDelta = StringBuilder()
            val pendingEvents = mutableListOf<ChatStreamEvent>()
            val mcpSignalDetected = AtomicBoolean(false)
            val autoStopIssued = AtomicBoolean(false)
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
                    panelForUpdate.appendStreamContent(delta, events)
                }
            }
            try {
                projectService.chat(
                    providerId = resolvedProviderId,
                    userInput = userInput,
                    modelId = modelId,
                    conversationHistory = conversationHistory.toList(),
                    operationMode = operationMode,
                    planExecuteVerifySections = workflowSectionRenderingEnabledFor(currentInteractionMode()),
                    requestId = requestId,
                ) { chunk ->
                    if (chunk.delta.isNotEmpty()) {
                        assistantContent.append(chunk.delta)
                        pendingDelta.append(chunk.delta)
                    }
                    chunk.event
                        ?.let(::sanitizeStreamEvent)
                        ?.let { event ->
                            pendingEvents += event
                            streamedTraceEvents += event
                            if (looksLikeMcpSignal(event)) {
                                mcpSignalDetected.set(true)
                            }
                            if (mcpSignalDetected.get() && isMcpVerificationTerminalEvent(event)) {
                                requestAutoStopAfterMcpVerification(
                                    providerId = resolvedProviderId,
                                    requestId = requestId,
                                    autoStopIssued = autoStopIssued,
                                )
                            }
                        }
                    pendingChunks += 1

                    if (chunk.isLast) {
                        flushPending(force = true)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            currentAssistantPanel?.finishMessage()
                            messagesPanel.scrollToBottom()
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
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
                    }
                } else {
                    val partial = assistantContent.toString()
                    if (partial.isNotBlank()) {
                        val assistantMessage = LlmMessage(LlmRole.ASSISTANT, partial)
                        appendToConversationHistory(assistantMessage)
                        currentSessionId?.let { sessionId ->
                            persistMessage(
                                sessionId = sessionId,
                                role = ConversationRole.ASSISTANT,
                                content = assistantMessage.content,
                                metadataJson = TraceEventMetadataCodec.encode(streamedTraceEvents),
                            )
                        }
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                stopRequested.set(false)

                flushPending(force = true)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    currentAssistantPanel?.finishMessage()
                    setSendingState(false)
                }
                runCatching {
                    persistAssistantResponseChangeset(
                        requestText = userInput,
                        providerId = providerId,
                        modelId = modelId,
                        beforeSnapshot = beforeSnapshot,
                        hasExecutionTrace = streamedTraceEvents.isNotEmpty(),
                    )
                }.onFailure {
                    logger.warn("Failed to persist assistant response changeset after regenerate request", it)
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
            val content = if (panel.role == ChatMessagePanel.MessageRole.USER) {
                userMessageRawContent[panel] ?: panel.getContent()
            } else {
                panel.getContent()
            }
            appendToConversationHistory(LlmMessage(role, content))
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
        pasteKeyDispatcher?.let { dispatcher ->
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
        pasteKeyDispatcher = null
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        dividerPersistTimer?.stop()
        dividerPersistTimer = null
        runningWorkflowCommands.values.forEach { running ->
            runCatching {
                running.stopRequested.set(true)
                if (running.process.isAlive) {
                    running.process.destroyForcibly()
                }
            }
        }
        runningWorkflowCommands.clear()
        clearImageAttachments()
        pendingPastedTextBlocks.clear()
        userMessageRawContent.clear()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        scope.cancel()
    }

    private class SidebarGripSplitPaneUI : BasicSplitPaneUI() {
        override fun createDefaultDivider(): BasicSplitPaneDivider {
            return object : BasicSplitPaneDivider(this) {
                override fun paint(g: Graphics) {
                    super.paint(g)
                    val g2 = g as? Graphics2D ?: return
                    val horizontal = splitPane?.orientation == JSplitPane.HORIZONTAL_SPLIT
                    val w = width
                    val h = height
                    if (w <= 0 || h <= 0) return

                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (horizontal) {
                        paintVerticalGrip(g2, w, h)
                    } else {
                        paintHorizontalGrip(g2, w, h)
                    }
                }

                private fun paintVerticalGrip(g2: Graphics2D, w: Int, h: Int) {
                    val trackWidth = JBUI.scale(3)
                    val trackHeight = JBUI.scale(30)
                    val trackX = (w - trackWidth) / 2
                    val trackY = (h - trackHeight) / 2
                    g2.color = JBColor(
                        Color(225, 232, 242),
                        Color(92, 99, 111),
                    )
                    g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackWidth, trackWidth)

                    val dotSize = JBUI.scale(2)
                    val dotStep = JBUI.scale(5)
                    val dotX = (w - dotSize) / 2
                    val dotStart = trackY + JBUI.scale(5)
                    val dotEnd = trackY + trackHeight - dotSize - JBUI.scale(4)
                    g2.color = JBColor(
                        Color(152, 166, 186),
                        Color(167, 179, 196),
                    )
                    var y = dotStart
                    while (y <= dotEnd) {
                        g2.fillOval(dotX, y, dotSize, dotSize)
                        y += dotStep
                    }
                }

                private fun paintHorizontalGrip(g2: Graphics2D, w: Int, h: Int) {
                    val trackWidth = JBUI.scale(30)
                    val trackHeight = JBUI.scale(3)
                    val trackX = (w - trackWidth) / 2
                    val trackY = (h - trackHeight) / 2
                    g2.color = JBColor(
                        Color(225, 232, 242),
                        Color(92, 99, 111),
                    )
                    g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackHeight, trackHeight)

                    val dotSize = JBUI.scale(2)
                    val dotStep = JBUI.scale(5)
                    val dotY = (h - dotSize) / 2
                    val dotStart = trackX + JBUI.scale(5)
                    val dotEnd = trackX + trackWidth - dotSize - JBUI.scale(4)
                    g2.color = JBColor(
                        Color(152, 166, 186),
                        Color(167, 179, 196),
                    )
                    var x = dotStart
                    while (x <= dotEnd) {
                        g2.fillOval(x, dotY, dotSize, dotSize)
                        x += dotStep
                    }
                }
            }
        }
    }

    private fun isSpecWorkflowCreateCommand(command: String): Boolean {
        val args = command.removePrefix("/spec").trim()
        if (args.isBlank() || args.equals("help", ignoreCase = true)) {
            return false
        }
        val token = args.substringBefore(" ").trim().lowercase(Locale.ROOT)
        return token !in setOf("status", "open", "next", "back", "generate", "complete")
    }

    companion object {
        private val CHAT_CONTEXT_CONFIG = ContextConfig(
            includeCurrentFile = false,
            includeSelectedCode = false,
            includeContainingScope = false,
            preferGraphRelatedContext = false,
        )
        private val CLAUDE_INTERACTIVE_ONLY_CLI_COMMANDS = setOf(
            "agents",
            "auth",
            "doctor",
            "install",
            "mcp",
            "plugin",
            "setup-token",
            "update",
            "upgrade",
        )
        private val CODEX_INTERACTIVE_ONLY_CLI_COMMANDS = setOf(
            "app-server",
            "cloud",
            "completion",
            "debug",
            "fork",
            "login",
            "logout",
            "mcp",
            "mcp-server",
            "resume",
            "sandbox",
        )
        private val CLAUDE_SESSION_ONLY_SLASH_COMMANDS = setOf(
            "compact",
        )
        private val CODEX_SESSION_ONLY_SLASH_COMMANDS = setOf(
            "compact",
        )
        private val MCP_EVENT_KEYWORDS = listOf(
            "mcp",
            "mcp__",
            "mcp-",
            "model context protocol",
            "modelcontextprotocol",
        )

        private const val HISTORY_CONTENT_KEY = "SpecCoding.HistoryContent"
        private val HISTORY_CONTENT_DATA_KEY = Key.create<String>(HISTORY_CONTENT_KEY)
        private val ACTION_ICON_BUTTON_SIZE = JBDimension(28, 24)
        private const val SPEC_WORKFLOW_COMBO_MIN_WIDTH = 120
        private const val SPEC_WORKFLOW_COMBO_MAX_WIDTH = 220
        private const val SPEC_WORKFLOW_COMBO_TEXT_OVERHEAD_PX = 56
        private const val MAX_CONVERSATION_HISTORY = 240
        private const val MAX_RESTORED_MESSAGES = 240
        private const val SESSION_LOAD_FETCH_LIMIT = 5000
        private const val WORKFLOW_COMMAND_TIMEOUT_SECONDS = 120L
        private const val WORKFLOW_COMMAND_JOIN_TIMEOUT_MILLIS = 2000L
        private const val WORKFLOW_COMMAND_STOP_GRACE_SECONDS = 3L
        private const val WORKFLOW_COMMAND_OUTPUT_MAX_CHARS = 12_000
        private const val WORKFLOW_CHANGESET_COMMAND_MAX_LENGTH = 120
        private const val ASSISTANT_CHANGESET_REQUEST_MAX_LENGTH = 120
        private const val STREAM_BATCH_CHUNK_COUNT = 4
        private const val STREAM_BATCH_CHAR_COUNT = 240
        private const val STREAM_BATCH_INTERVAL_NANOS = 120_000_000L
        private const val ACTION_PASTE_IMAGE_OR_TEXT = "specCoding.pasteImageOrText"
        private const val PASTE_DIAGNOSTICS_ENABLED = true
        private const val INPUT_PASTE_COLLAPSE_MIN_LINES = 48
        private const val INPUT_PASTE_COLLAPSE_MIN_LINES_SOFT = 24
        private const val INPUT_PASTE_COLLAPSE_MIN_CHARS = 1200
        private const val INPUT_PASTE_COLLAPSE_ABRUPT_MIN_LINES = 6
        private const val INPUT_PASTE_COLLAPSE_ABRUPT_MIN_CHARS = 160
        private const val CLIPBOARD_IMAGE_TEMP_DIR_NAME = "spec-coding-plugin"
        private const val SPEC_CARD_PREVIEW_MAX_LINES = 18
        private const val SPEC_CARD_PREVIEW_MAX_CHARS = 1800
        private const val SPEC_SIDEBAR_DEFAULT_DIVIDER = 760
        private const val SPEC_SIDEBAR_MIN_WIDTH = 220
        private const val SPEC_SIDEBAR_MIN_DIVIDER = 320
        private const val SPEC_SIDEBAR_DIVIDER_SIZE = 8
        private const val SPEC_SIDEBAR_DIVIDER_PERSIST_DEBOUNCE_MILLIS = 140
        private const val STATUS_SESSION_LOADED_AUTO_HIDE_MILLIS = 2200
        private const val STATUS_SHORT_HINT_AUTO_HIDE_MILLIS = 1800
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
        private val PASTED_TEXT_MARKER_REGEX = Regex("""\[Pasted text #\d+ \+\d+ lines]""")
        private val PASTED_TEXT_MARKER_FULL_LINE_REGEX = Regex("""^\[Pasted text #\d+ \+\d+ lines]$""")
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
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "png",
            "jpg",
            "jpeg",
            "webp",
            "gif",
            "bmp",
        )
    }
}
