package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphRenderer
import com.eacape.speccodingplugin.context.CodeGraphService
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.spec.*
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.RefreshFeedback
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.ui.worktree.NewWorktreeDialog
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.icons.AllIcons
import com.intellij.CommonBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants

class SpecWorkflowPanel(
    private val project: Project
) : JBPanel<SpecWorkflowPanel>(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val specEngine = SpecEngine.getInstance(project)
    private val specDeltaService = SpecDeltaService.getInstance(project)
    private val specTasksService = SpecTasksService.getInstance(project)
    private val specRelatedFilesService = SpecRelatedFilesService.getInstance(project)
    private val specVerificationService = SpecVerificationService.getInstance(project)
    private val artifactService = SpecArtifactService(project)
    private val codeGraphService = CodeGraphService.getInstance(project)
    private val worktreeManager = WorktreeManager.getInstance(project)
    private val llmRouter = LlmRouter.getInstance()
    private val modelRegistry = ModelRegistry.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryListener: () -> Unit = {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()
        invokeLaterSafe {
            refreshProviderCombo(preserveSelection = true)
        }
    }

    @Volatile
    private var _isDisposed = false

    private val phaseIndicator = SpecPhaseIndicatorPanel()
    private val listPanel: SpecWorkflowListPanel
    private val detailPanel: SpecDetailPanel
    private val overviewPanel = SpecWorkflowOverviewPanel(
        onStageSelected = ::onOverviewStageSelected,
        onWorkbenchActionRequested = ::onWorkbenchActionRequested,
        onTemplateSwitchRequested = ::onTemplateSwitchRequested,
        onTemplateRollbackRequested = ::onTemplateSwitchRollbackRequested,
    )
    private val tasksPanel = SpecWorkflowTasksPanel(
        onTransitionStatus = ::onTaskStatusTransitionRequested,
        onUpdateDependsOn = ::onTaskDependsOnUpdateRequested,
        onUpdateRelatedFiles = ::onTaskRelatedFilesUpdateRequested,
        onCompleteWithRelatedFiles = ::onTaskCompleteRequested,
        suggestRelatedFiles = { taskId, existingRelatedFiles ->
            specRelatedFilesService.suggestRelatedFiles(taskId, existingRelatedFiles)
        },
        showHeader = false,
    )
    private val verifyDeltaPanel = SpecWorkflowVerifyDeltaPanel(
        onRunVerifyRequested = ::onRunVerificationRequested,
        onOpenVerificationRequested = ::onOpenVerificationRequested,
        onCompareBaselineRequested = ::onCompareDeltaBaselineRequested,
        onPinBaselineRequested = ::onPinDeltaBaselineRequested,
        showHeader = false,
    )
    private val gateDetailsPanel = SpecWorkflowGateDetailsPanel(
        project = project,
        showHeader = false,
    )
    private val statusLabel = JBLabel("")
    private val statusChipPanel = JPanel(BorderLayout())
    private val modelLabel = JBLabel(SpecCodingBundle.message("toolwindow.model.label"))
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private val createWorktreeButton = JButton()
    private val mergeWorktreeButton = JButton()
    private val deltaButton = JButton()
    private val codeGraphButton = JButton()
    private val archiveButton = JButton()
    private val backToListButton = JButton()
    private val refreshButton = JButton()
    private lateinit var centerContentPanel: JPanel
    private lateinit var listSectionContainer: JPanel
    private lateinit var workspacePanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane
    private val workspaceCardLayout = CardLayout()
    private val workspaceCardPanel = JPanel(workspaceCardLayout)
    private val workspaceSummaryTitleLabel = JBLabel()
    private val workspaceSummaryMetaLabel = JBLabel()
    private val workspaceSummaryFocusLabel = JBLabel()
    private val workspaceSummaryHintLabel = JBLabel()
    private val workspaceStageMetric = createWorkspaceSummaryMetric()
    private val workspaceGateMetric = createWorkspaceSummaryMetric()
    private val workspaceTasksMetric = createWorkspaceSummaryMetric()
    private val workspaceVerifyMetric = createWorkspaceSummaryMetric()
    private lateinit var overviewSection: SpecCollapsibleWorkspaceSection
    private lateinit var tasksSection: SpecCollapsibleWorkspaceSection
    private lateinit var gateSection: SpecCollapsibleWorkspaceSection
    private lateinit var verifySection: SpecCollapsibleWorkspaceSection
    private lateinit var documentsSection: SpecCollapsibleWorkspaceSection
    private val workspaceSectionItems = mutableMapOf<SpecWorkflowWorkspaceSectionId, JPanel>()
    private val workspaceSectionOverrides = mutableMapOf<SpecWorkflowWorkspaceSectionId, Boolean>()
    private var workspaceSectionPresetToken: String? = null
    private var currentOverviewState: SpecWorkflowOverviewState? = null
    private var currentWorkbenchState: SpecWorkflowStageWorkbenchState? = null
    private var currentVerifyDeltaState: SpecWorkflowVerifyDeltaState? = null
    private var currentGateResult: GateResult? = null
    private var currentStructuredTasks: List<StructuredTask> = emptyList()
    private val pendingClarificationRetryByWorkflowId = mutableMapOf<String, ClarificationRetryPayload>()
    private var activeGenerationJob: Job? = null
    private var activeGenerationRequest: ActiveGenerationRequest? = null
    private var pendingDocumentReloadJob: Job? = null

    private var selectedWorkflowId: String? = null
    private var highlightedWorkflowId: String? = null
    private var currentWorkflow: SpecWorkflow? = null
    private var focusedStage: StageId? = null
    private var isWorkspaceMode: Boolean = false
    private var detailDividerLocation: Int = 210

    init {
        border = JBUI.Borders.empty(8)

        listPanel = SpecWorkflowListPanel(
            onWorkflowFocused = ::onWorkflowFocusedByUser,
            onOpenWorkflow = ::onWorkflowOpenedByUser,
            onCreateWorkflow = ::onCreateWorkflow,
            onEditWorkflow = ::onEditWorkflow,
            onDeleteWorkflow = ::onDeleteWorkflow
        )

        detailPanel = SpecDetailPanel(
            onGenerate = ::onGenerate,
            canGenerateWithEmptyInput = ::canGenerateWithEmptyInput,
            onClarificationConfirm = ::onClarificationConfirm,
            onClarificationRegenerate = ::onClarificationRegenerate,
            onClarificationSkip = ::onClarificationSkip,
            onClarificationCancel = ::onClarificationCancel,
            onNextPhase = ::onNextPhase,
            onGoBack = ::onGoBack,
            onComplete = ::onComplete,
            onPauseResume = ::onPauseResume,
            onOpenInEditor = ::onOpenInEditor,
            onOpenArtifactInEditor = ::onOpenArtifactInEditor,
            onShowHistoryDiff = ::onShowHistoryDiff,
            onSaveDocument = ::onSaveDocument,
            onClarificationDraftAutosave = ::onClarificationDraftAutosave,
        )

        setupUI()
        CliDiscoveryService.getInstance().addDiscoveryListener(discoveryListener)
        subscribeToLocaleEvents()
        subscribeToToolWindowControlEvents()
        subscribeToWorkflowEvents()
        subscribeToDocumentFileEvents()
        refreshWorkflows()
    }

    private fun setupUI() {
        refreshButton.addActionListener { refreshWorkflows(showRefreshFeedback = true) }
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        createWorktreeButton.isVisible = false
        mergeWorktreeButton.isVisible = false
        deltaButton.isEnabled = false
        codeGraphButton.isEnabled = true
        archiveButton.isEnabled = false
        backToListButton.isEnabled = false
        createWorktreeButton.addActionListener { onCreateWorktree() }
        mergeWorktreeButton.addActionListener { onMergeWorktree() }
        deltaButton.addActionListener { onShowDelta() }
        codeGraphButton.addActionListener { onShowCodeGraph() }
        archiveButton.addActionListener { onArchiveWorkflow() }
        backToListButton.addActionListener { onBackToWorkflowListRequested() }

        configureToolbarIconButton(
            button = backToListButton,
            icon = AllIcons.Actions.Back,
            tooltipKey = "spec.workflow.backToList",
        )
        styleToolbarButton(backToListButton)

        applyToolbarButtonPresentation()
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG
        setupGenerationControls()

        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(providerComboBox)
            add(modelLabel)
            add(modelComboBox)
            add(statusChipPanel)
        }
        val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            add(refreshButton)
            add(deltaButton)
            add(codeGraphButton)
            add(archiveButton)
        }
        val toolbarRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, JBUI.scale(1), 0)
            add(controlsRow, BorderLayout.CENTER)
            add(actionsRow, BorderLayout.EAST)
        }
        val modelHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBScrollPane(toolbarRow).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    viewport.isOpaque = false
                    isOpaque = false
                    SpecUiStyle.applySlimHorizontalScrollBar(this, height = 7)
                },
                BorderLayout.CENTER,
            )
        }
        statusChipPanel.isOpaque = true
        statusChipPanel.background = STATUS_CHIP_BG
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = STATUS_CHIP_BORDER,
            arc = JBUI.scale(12),
            top = 1,
            left = 8,
            bottom = 1,
            right = 8,
        )
        statusChipPanel.removeAll()
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)
        val toolbarCard = JPanel(BorderLayout(0, JBUI.scale(1))).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(14),
                top = 3,
                left = 8,
                bottom = 3,
                right = 8,
            )
            add(modelHost, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(6)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        listSectionContainer = createSectionContainer(
            listPanel,
            backgroundColor = LIST_SECTION_BG,
            borderColor = LIST_SECTION_BORDER,
        )
        workspacePanelContainer = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_COLUMN_BG
            add(buildWorkspaceCardPanel(), BorderLayout.CENTER)
        }

        mainSplitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            listSectionContainer,
            workspacePanelContainer,
        ).apply {
            dividerLocation = detailDividerLocation
            resizeWeight = 0.26
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_SECTION_BG
            SpecUiStyle.applyChatLikeSpecDivider(
                splitPane = this,
                dividerSize = JBUI.scale(4),
            )
        }
        mainSplitPane.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    clampDividerLocation(mainSplitPane)
                }
            },
        )
        mainSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (isWorkspaceMode && mainSplitPane.dividerLocation > 0) {
                detailDividerLocation = mainSplitPane.dividerLocation
            }
        }
        centerContentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        add(centerContentPanel, BorderLayout.CENTER)
        setStatusText(null)
        showWorkspaceEmptyState()
    }

    private fun clampDividerLocation(split: JSplitPane) {
        if (!isWorkspaceMode || split.parent == null) return
        val total = split.width - split.dividerSize
        if (total <= 0) return
        val minLeft = JBUI.scale(120)
        val minRight = JBUI.scale(240)
        val maxLeft = (total - minRight).coerceAtLeast(minLeft)
        val current = split.dividerLocation
        val clamped = current.coerceIn(minLeft, maxLeft)
        if (clamped != current) {
            split.dividerLocation = clamped
        }
        detailDividerLocation = clamped
    }

    private fun showWorkflowListOnlyMode() {
        isWorkspaceMode = false
        reparentToCenter(listSectionContainer)
    }

    private fun showWorkflowWorkspaceMode() {
        isWorkspaceMode = true
        reparentToCenter(workspacePanelContainer)
    }

    private fun reparentToCenter(component: Component) {
        detachFromParent(component)
        if (centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === component) {
            return
        }
        centerContentPanel.removeAll()
        centerContentPanel.add(component, BorderLayout.CENTER)
        centerContentPanel.revalidate()
        centerContentPanel.repaint()
    }

    private fun detachFromParent(component: Component) {
        component.parent?.remove(component)
    }

    private fun createWorkspaceSummaryMetric(): WorkspaceSummaryMetric {
        val titleLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = WORKSPACE_SUMMARY_LABEL_FG
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val valueLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val root = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            isVisible = false
            add(titleLabel)
            add(valueLabel)
        }
        return WorkspaceSummaryMetric(
            root = root,
            titleLabel = titleLabel,
            valueLabel = valueLabel,
        )
    }

    private fun workspaceChipColors(tone: WorkspaceChipTone): WorkspaceChipColors {
        return when (tone) {
            WorkspaceChipTone.INFO -> WorkspaceChipColors(
                foreground = WORKSPACE_INFO_CHIP_FG,
            )

            WorkspaceChipTone.SUCCESS -> WorkspaceChipColors(
                foreground = WORKSPACE_SUCCESS_CHIP_FG,
            )

            WorkspaceChipTone.WARNING -> WorkspaceChipColors(
                foreground = WORKSPACE_WARNING_CHIP_FG,
            )

            WorkspaceChipTone.ERROR -> WorkspaceChipColors(
                foreground = WORKSPACE_ERROR_CHIP_FG,
            )

            WorkspaceChipTone.MUTED -> WorkspaceChipColors(
                foreground = WORKSPACE_MUTED_CHIP_FG,
            )
        }
    }

    private fun buildWorkspaceCardPanel(): JPanel {
        val sectionsStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }

        val summaryCard = buildWorkspaceSummaryCard()
        sectionsStack.add(prepareWorkspaceStackItem(summaryCard))
        sectionsStack.add(Box.createVerticalStrut(JBUI.scale(6)))

        overviewSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.OVERVIEW,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.overview") },
            content = overviewPanel,
        )
        tasksSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.TASKS,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.tasks") },
            content = tasksPanel,
        )
        gateSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.GATE,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.gate") },
            content = gateDetailsPanel,
        )
        verifySection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.VERIFY,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.verify") },
            content = verifyDeltaPanel,
        )
        documentsSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.documents") },
            content = detailPanel,
        )

        workspaceSectionItems.clear()
        listOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to overviewSection,
            SpecWorkflowWorkspaceSectionId.TASKS to tasksSection,
            SpecWorkflowWorkspaceSectionId.GATE to gateSection,
            SpecWorkflowWorkspaceSectionId.VERIFY to verifySection,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to documentsSection,
        ).forEachIndexed { index, (sectionId, section) ->
            val item = createWorkspaceSectionItem(
                content = section,
                addBottomGap = index < 4,
            )
            workspaceSectionItems[sectionId] = item
            sectionsStack.add(prepareWorkspaceStackItem(item))
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBScrollPane(sectionsStack).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    viewport.isOpaque = false
                    isOpaque = false
                },
                BorderLayout.CENTER,
            )
        }

        workspaceCardPanel.apply {
            isOpaque = false
            add(buildWorkspaceEmptyState(), WORKSPACE_CARD_EMPTY)
            add(contentPanel, WORKSPACE_CARD_CONTENT)
        }
        return workspaceCardPanel
    }

    private fun prepareWorkspaceStackItem(component: Component): Component {
        if (component is JPanel) {
            component.alignmentX = Component.LEFT_ALIGNMENT
            component.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        return component
    }

    private fun createWorkspaceSectionItem(
        content: Component,
        addBottomGap: Boolean,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            if (addBottomGap) {
                border = JBUI.Borders.emptyBottom(6)
            }
            add(
                createSectionContainer(
                    content,
                    padding = WORKSPACE_SECTION_CARD_PADDING,
                    backgroundColor = DETAIL_SECTION_BG,
                    borderColor = DETAIL_SECTION_BORDER,
                ),
                BorderLayout.CENTER,
            )
        }
    }

    private fun buildWorkspaceSummaryCard(): JPanel {
        workspaceSummaryTitleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
        workspaceSummaryTitleLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        workspaceSummaryMetaLabel.font = JBUI.Fonts.smallFont()
        workspaceSummaryMetaLabel.foreground = WORKSPACE_SUMMARY_META_FG
        workspaceSummaryFocusLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12.5f)
        workspaceSummaryFocusLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        workspaceSummaryHintLabel.font = JBUI.Fonts.smallFont()
        workspaceSummaryHintLabel.foreground = WORKSPACE_SUMMARY_META_FG

        val titleStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(workspaceSummaryTitleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(workspaceSummaryMetaLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(workspaceSummaryFocusLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(workspaceSummaryHintLabel)
        }
        val headerRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(backToListButton)
                },
                BorderLayout.WEST,
            )
            add(titleStack, BorderLayout.CENTER)
        }
        val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(workspaceStageMetric.root)
            add(workspaceGateMetric.root)
            add(workspaceTasksMetric.root)
            add(workspaceVerifyMetric.root)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            name = "workspaceSummaryCard"
            isOpaque = true
            background = WORKSPACE_SUMMARY_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = WORKSPACE_SUMMARY_BORDER,
                arc = JBUI.scale(16),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(
                JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(headerRow, BorderLayout.NORTH)
                    add(chipRow, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun buildWorkspaceEmptyState(): JPanel {
        val titleLabel = JBLabel(SpecCodingBundle.message("spec.detail.noWorkflow")).apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
            foreground = WORKSPACE_EMPTY_TITLE_FG
        }
        val descriptionLabel = JBLabel(
            "<html>${SpecCodingBundle.message("spec.toolwindow.overview.empty")}</html>",
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = WORKSPACE_EMPTY_DESCRIPTION_FG
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = DETAIL_SECTION_BORDER,
                arc = JBUI.scale(16),
                top = 18,
                left = 18,
                bottom = 18,
                right = 18,
            )
            add(titleLabel, BorderLayout.NORTH)
            add(descriptionLabel, BorderLayout.CENTER)
        }
    }

    private fun createWorkspaceSection(
        id: SpecWorkflowWorkspaceSectionId,
        titleProvider: () -> String,
        content: Component,
    ): SpecCollapsibleWorkspaceSection {
        return SpecCollapsibleWorkspaceSection(
            titleProvider = titleProvider,
            content = content,
            expandedInitially = true,
            onExpandedChanged = { expanded ->
                workspaceSectionOverrides[id] = expanded
            },
        )
    }

    private fun showWorkspaceEmptyState() {
        showWorkflowListOnlyMode()
        backToListButton.isEnabled = false
        workspaceCardLayout.show(workspaceCardPanel, WORKSPACE_CARD_EMPTY)
        workspaceSectionOverrides.clear()
        workspaceSectionPresetToken = null
        currentOverviewState = null
        currentWorkbenchState = null
        currentVerifyDeltaState = null
        currentGateResult = null
        currentStructuredTasks = emptyList()
        focusedStage = null
        workspaceSummaryTitleLabel.text = ""
        workspaceSummaryMetaLabel.text = ""
        workspaceSummaryFocusLabel.text = ""
        workspaceSummaryHintLabel.text = ""
        clearWorkspaceMetric(workspaceStageMetric)
        clearWorkspaceMetric(workspaceGateMetric)
        clearWorkspaceMetric(workspaceTasksMetric)
        clearWorkspaceMetric(workspaceVerifyMetric)
        if (::overviewSection.isInitialized) {
            workspaceSections().values.forEach { section ->
                section.setSummary(null)
                section.setExpanded(true, notify = false)
            }
        }
        workspaceSectionItems.values.forEach { item ->
            item.isVisible = true
        }
    }

    private fun showWorkspaceContent() {
        showWorkflowWorkspaceMode()
        backToListButton.isEnabled = true
        workspaceCardLayout.show(workspaceCardPanel, WORKSPACE_CARD_CONTENT)
    }


    private fun styleToolbarButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.insets(0, 0, 0, 0) else JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        SpecUiStyle.applyRoundRect(button, arc = 10)
        installToolbarButtonCursorTracking(button)
        if (iconOnly) {
            installToolbarIconButtonStateTracking(button)
            applyToolbarIconButtonVisualState(button)
            val size = JBUI.scale(22)
            button.preferredSize = JBUI.size(size, size)
            button.minimumSize = button.preferredSize
        } else {
            button.background = BUTTON_BG
            button.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(1, 5, 1, 5),
            )
            val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
            val insets = button.insets
            val lafWidth = button.preferredSize?.width ?: 0
            val width = maxOf(
                lafWidth,
                textWidth + insets.left + insets.right + JBUI.scale(10),
                JBUI.scale(40),
            )
            button.preferredSize = JBUI.size(width, JBUI.scale(26))
            button.minimumSize = button.preferredSize
        }
    }

    private fun installToolbarIconButtonStateTracking(button: JButton) {
        if (button.getClientProperty("spec.toolbar.iconStyleInstalled") == true) return
        button.putClientProperty("spec.toolbar.iconStyleInstalled", true)
        button.isRolloverEnabled = true
        button.addChangeListener { applyToolbarIconButtonVisualState(button) }
        button.addPropertyChangeListener("enabled") { applyToolbarIconButtonVisualState(button) }
    }

    private fun installToolbarButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("spec.toolbar.cursorTrackingInstalled") == true) return
        button.putClientProperty("spec.toolbar.cursorTrackingInstalled", true)
        updateToolbarButtonCursor(button)
        button.addPropertyChangeListener("enabled") { updateToolbarButtonCursor(button) }
    }

    private fun updateToolbarButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun applyToolbarIconButtonVisualState(button: JButton) {
        val model = button.model
        val background = when {
            !button.isEnabled -> ICON_BUTTON_BG_DISABLED
            model.isPressed || model.isSelected -> ICON_BUTTON_BG_ACTIVE
            model.isRollover -> ICON_BUTTON_BG_HOVER
            else -> ICON_BUTTON_BG
        }
        val borderColor = when {
            !button.isEnabled -> ICON_BUTTON_BORDER_DISABLED
            model.isPressed || model.isSelected -> ICON_BUTTON_BORDER_ACTIVE
            model.isRollover -> ICON_BUTTON_BORDER_HOVER
            else -> ICON_BUTTON_BORDER
        }
        val borderThickness = if (model.isPressed || model.isSelected) JBUI.scale(2) else 1
        button.background = background
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(borderColor, JBUI.scale(10), thickness = borderThickness),
            JBUI.Borders.empty(1, 1, 1, 1),
        )
    }

    private fun applyToolbarButtonPresentation() {
        configureToolbarIconButton(
            button = refreshButton,
            icon = AllIcons.General.InlineRefresh,
            tooltipKey = "spec.workflow.refresh",
        )
        configureToolbarIconButton(
            button = createWorktreeButton,
            icon = WORKFLOW_ICON_CREATE_WORKTREE,
            tooltipKey = "spec.workflow.createWorktree",
        )
        configureToolbarIconButton(
            button = mergeWorktreeButton,
            icon = WORKFLOW_ICON_MERGE_WORKTREE,
            tooltipKey = "spec.workflow.mergeWorktree",
        )
        configureToolbarIconButton(
            button = deltaButton,
            icon = WORKFLOW_ICON_DELTA,
            tooltipKey = "spec.workflow.delta",
        )
        configureToolbarIconButton(
            button = codeGraphButton,
            icon = WORKFLOW_ICON_GRAPH,
            tooltipKey = "spec.workflow.codeGraph",
        )
        configureToolbarIconButton(
            button = archiveButton,
            icon = WORKFLOW_ICON_ARCHIVE,
            tooltipKey = "spec.workflow.archive",
        )
    }

    private fun configureToolbarIconButton(button: JButton, icon: Icon, tooltipKey: String) {
        val tooltip = SpecCodingBundle.message(tooltipKey)
        button.icon = icon
        button.disabledIcon = IconLoader.getDisabledIcon(icon)
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
    }

    private fun setupGenerationControls() {
        modelLabel.font = JBUI.Fonts.smallFont()
        modelLabel.foreground = STATUS_TEXT_FG

        providerComboBox.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayName(value)
        }
        modelComboBox.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = toUiLowercase(value?.name ?: "")
        }
        providerComboBox.addActionListener {
            refreshModelCombo(preserveSelection = true)
        }
        modelComboBox.addActionListener {
            updateSelectorTooltips()
        }
        configureToolbarCombo(providerComboBox, minWidth = 56, maxWidth = 160)
        configureToolbarCombo(modelComboBox, minWidth = 72, maxWidth = 260)
        refreshProviderCombo(preserveSelection = false)
    }

    private fun configureToolbarCombo(comboBox: ComboBox<*>, minWidth: Int, maxWidth: Int) {
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = BUTTON_BG
        comboBox.foreground = BUTTON_FG
        comboBox.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(0, 5, 0, 5),
        )
        comboBox.isOpaque = true
        comboBox.font = JBUI.Fonts.smallFont()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = comboBox,
            minWidth = JBUI.scale(minWidth),
            maxWidth = JBUI.scale(maxWidth),
            height = JBUI.scale(24),
        )
    }

    private fun refreshProviderCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val previousSelection = if (preserveSelection) providerComboBox.selectedItem as? String else null
        val providers = llmRouter.availableUiProviders()
            .ifEmpty { llmRouter.availableProviders() }
            .ifEmpty { listOf(MockLlmProvider.ID) }

        providerComboBox.removeAllItems()
        providers.forEach { providerComboBox.addItem(it) }

        val preferred = when {
            !previousSelection.isNullOrBlank() -> previousSelection
            settings.defaultProvider.isNotBlank() -> settings.defaultProvider
            else -> llmRouter.defaultProviderId()
        }
        val selectedProvider = providers.firstOrNull { it == preferred } ?: providers.firstOrNull()
        providerComboBox.selectedItem = selectedProvider
        refreshModelCombo(preserveSelection = preserveSelection)
    }

    private fun refreshModelCombo(preserveSelection: Boolean) {
        val selectedProvider = providerComboBox.selectedItem as? String
        val previousModelId = if (preserveSelection) {
            (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        } else {
            ""
        }
        modelComboBox.removeAllItems()

        if (selectedProvider.isNullOrBlank()) {
            modelComboBox.isEnabled = false
            updateSelectorTooltips()
            return
        }

        val settings = SpecCodingSettingsState.getInstance()
        val savedModelId = settings.selectedCliModel.trim()
        val models = modelRegistry.getModelsForProvider(selectedProvider)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toMutableList()

        if (models.isEmpty() && savedModelId.isNotBlank() && selectedProvider == settings.defaultProvider) {
            models += ModelInfo(
                id = savedModelId,
                name = savedModelId,
                provider = selectedProvider,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }

        models.forEach { modelComboBox.addItem(it) }
        val preferredModelId = when {
            previousModelId.isNotBlank() -> previousModelId
            savedModelId.isNotBlank() -> savedModelId
            else -> ""
        }
        val selectedModel = models.firstOrNull { it.id == preferredModelId } ?: models.firstOrNull()
        if (selectedModel != null) {
            modelComboBox.selectedItem = selectedModel
        }
        modelComboBox.isEnabled = models.isNotEmpty()
        updateSelectorTooltips()
    }

    private fun updateSelectorTooltips() {
        providerComboBox.toolTipText = providerDisplayName(providerComboBox.selectedItem as? String)
        modelComboBox.toolTipText = (modelComboBox.selectedItem as? ModelInfo)?.name
    }

    private fun providerDisplayName(providerId: String?): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> "claude"
            CodexCliLlmProvider.ID -> "codex"
            MockLlmProvider.ID -> toUiLowercase(SpecCodingBundle.message("toolwindow.model.mock"))
            null -> ""
            else -> toUiLowercase(providerId)
        }
    }

    private fun toUiLowercase(value: String): String = value.lowercase(Locale.ROOT)

    private fun setStatusText(text: String?) {
        val value = text?.trim().orEmpty()
        statusLabel.text = value
        statusChipPanel.isVisible = value.isNotEmpty()
        statusChipPanel.revalidate()
        statusChipPanel.repaint()
    }

    private fun createSectionContainer(
        content: Component,
        padding: Int = 2,
        backgroundColor: Color = PANEL_SECTION_BG,
        borderColor: Color = PANEL_SECTION_BORDER,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = backgroundColor
            border = SpecUiStyle.roundedCardBorder(
                lineColor = borderColor,
                arc = JBUI.scale(14),
                top = padding,
                left = padding,
                bottom = padding,
                right = padding,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun refreshWorkspacePresentation() {
        val workflow = currentWorkflow
        val overviewState = currentOverviewState
        val verifyDeltaState = currentVerifyDeltaState
        if (workflow == null || overviewState == null || verifyDeltaState == null) {
            if (selectedWorkflowId == null) {
                showWorkspaceEmptyState()
            }
            return
        }
        updateWorkspacePresentation(
            workflow = workflow,
            overviewState = overviewState,
            tasks = currentStructuredTasks,
            verifyDeltaState = verifyDeltaState,
            gateResult = currentGateResult,
        )
    }

    private fun updateWorkspacePresentation(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ) {
        val previousWorkbenchState = currentWorkbenchState
        val workbenchState = resolveWorkbenchState(
            workflow = workflow,
            state = SpecWorkflowStageWorkbenchBuilder.build(
                workflow = workflow,
                overviewState = overviewState,
                tasks = tasks,
                verifyDeltaState = verifyDeltaState,
                gateResult = gateResult,
                focusedStage = focusedStage,
            ),
        )
        focusedStage = workbenchState.focusedStage
        currentOverviewState = overviewState
        currentWorkbenchState = workbenchState
        currentVerifyDeltaState = verifyDeltaState
        currentGateResult = gateResult
        currentStructuredTasks = tasks

        showWorkspaceContent()
        overviewPanel.updateOverview(
            state = overviewState,
            workbenchState = workbenchState,
        )
        val guidance = SpecWorkflowStageGuidanceBuilder.build(
            state = overviewState,
            workbenchState = workbenchState,
        )
        workspaceSummaryTitleLabel.text = workflow.title.ifBlank { workflow.id }
        val nextStageText = overviewState.nextStage
            ?.let(SpecWorkflowOverviewPresenter::stageLabel)
            ?: SpecCodingBundle.message("spec.toolwindow.overview.nextStage.none")
        workspaceSummaryMetaLabel.text = buildString {
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.workflow"))
            append(": ")
            append(workflow.id)
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.template"))
            append(": ")
            append(SpecWorkflowOverviewPresenter.templateLabel(workflow.template))
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.next"))
            append(": ")
            append(nextStageText)
        }
        workspaceSummaryFocusLabel.text = guidance.headline
        workspaceSummaryHintLabel.text = guidance.summary

        updateWorkspaceMetric(
            metric = workspaceStageMetric,
            title = if (workbenchState.focusedStage == workbenchState.currentStage) {
                SpecCodingBundle.message("spec.toolwindow.overview.currentStage")
            } else {
                SpecCodingBundle.message("spec.toolwindow.overview.secondary.focus")
            },
            value = buildStageChipText(workbenchState),
            tone = when (workflow.status) {
                WorkflowStatus.COMPLETED -> WorkspaceChipTone.SUCCESS
                WorkflowStatus.PAUSED -> WorkspaceChipTone.WARNING
                WorkflowStatus.FAILED -> WorkspaceChipTone.ERROR
                WorkflowStatus.IN_PROGRESS -> WorkspaceChipTone.INFO
            },
        )
        updateWorkspaceMetric(
            metric = workspaceGateMetric,
            title = SpecCodingBundle.message("spec.toolwindow.section.gate"),
            value = buildGateChipText(gateResult),
            tone = when (gateResult?.status) {
                GateStatus.ERROR -> WorkspaceChipTone.ERROR
                GateStatus.WARNING -> WorkspaceChipTone.WARNING
                GateStatus.PASS -> WorkspaceChipTone.SUCCESS
                null -> WorkspaceChipTone.MUTED
            },
        )
        updateWorkspaceMetric(
            metric = workspaceTasksMetric,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.title"),
            value = buildTasksChipText(tasks),
            tone = when {
                tasks.any { it.status == TaskStatus.BLOCKED } -> WorkspaceChipTone.WARNING
                tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.COMPLETED } -> WorkspaceChipTone.SUCCESS
                else -> WorkspaceChipTone.INFO
            },
        )
        updateWorkspaceMetric(
            metric = workspaceVerifyMetric,
            title = SpecCodingBundle.message("spec.toolwindow.section.verify"),
            value = buildVerifyChipText(verifyDeltaState),
            tone = when (verifyDeltaState.verificationHistory.firstOrNull()?.conclusion) {
                VerificationConclusion.FAIL -> WorkspaceChipTone.ERROR
                VerificationConclusion.WARN -> WorkspaceChipTone.WARNING
                VerificationConclusion.PASS -> WorkspaceChipTone.SUCCESS
                null -> WorkspaceChipTone.MUTED
            },
        )

        overviewSection.setSummary(
            buildOverviewSectionSummary(overviewState, workbenchState, nextStageText),
        )
        tasksSection.setSummary(buildTasksSectionSummary(tasks))
        gateSection.setSummary(buildGateSectionSummary(gateResult))
        verifySection.setSummary(buildVerifySectionSummary(verifyDeltaState))
        documentsSection.setSummary(
            buildDocumentsSectionSummary(workbenchState),
        )
        detailPanel.updateWorkbenchState(
            state = workbenchState,
            syncSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
                previousWorkbenchState?.currentStage != workbenchState.currentStage,
        )
        syncWorkbenchTaskSelection(
            previousWorkbenchState = previousWorkbenchState,
            workbenchState = workbenchState,
        )
        applyWorkspaceSectionVisibility(workbenchState)
        applyWorkspaceSectionPreset(workflow, workbenchState)
    }

    private fun syncWorkbenchTaskSelection(
        previousWorkbenchState: SpecWorkflowStageWorkbenchState?,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        if (workbenchState.focusedStage != StageId.IMPLEMENT) {
            return
        }
        val taskId = workbenchState.implementationFocus?.taskId ?: return
        val shouldSyncSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
            previousWorkbenchState?.implementationFocus?.taskId != taskId
        if (shouldSyncSelection) {
            tasksPanel.selectTask(taskId)
        }
    }

    private fun applyWorkspaceSectionPreset(
        workflow: SpecWorkflow,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        val token = "${workflow.id}:${workflow.currentStage.name}:${workbenchState.focusedStage.name}:${workflow.status.name}"
        if (workspaceSectionPresetToken != token) {
            workspaceSectionPresetToken = token
            workspaceSectionOverrides.clear()
            val expanded = SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = workbenchState.focusedStage,
                status = workflow.status,
            )
            workspaceSections().forEach { (sectionId, section) ->
                section.setExpanded(expanded.contains(sectionId), notify = false)
            }
            return
        }

        workspaceSections().forEach { (sectionId, section) ->
            workspaceSectionOverrides[sectionId]?.let { expanded ->
                section.setExpanded(expanded, notify = false)
            }
        }
    }

    private fun applyWorkspaceSectionVisibility(workbenchState: SpecWorkflowStageWorkbenchState) {
        val visibleSections = workbenchState.visibleSections
        workspaceSectionItems.forEach { (sectionId, item) ->
            item.isVisible = visibleSections.contains(sectionId)
        }
        workspaceCardPanel.revalidate()
        workspaceCardPanel.repaint()
    }

    private fun workspaceSections(): Map<SpecWorkflowWorkspaceSectionId, SpecCollapsibleWorkspaceSection> {
        if (!::overviewSection.isInitialized) {
            return emptyMap()
        }
        return linkedMapOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to overviewSection,
            SpecWorkflowWorkspaceSectionId.TASKS to tasksSection,
            SpecWorkflowWorkspaceSectionId.GATE to gateSection,
            SpecWorkflowWorkspaceSectionId.VERIFY to verifySection,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to documentsSection,
        )
    }

    private fun buildStageChipText(workbenchState: SpecWorkflowStageWorkbenchState): String {
        val stageLabel = SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage)
        val checksText = "${workbenchState.progress.completedCheckCount}/${workbenchState.progress.totalCheckCount}"
        val progressText = "${workbenchState.progress.stepIndex}/${workbenchState.progress.totalSteps}"
        val stageStatus = SpecWorkflowOverviewPresenter.progressLabel(workbenchState.progress.stageStatus)
        return if (workbenchState.focusedStage == workbenchState.currentStage) {
            "$stageLabel / $checksText / $progressText / $stageStatus"
        } else {
            "$stageLabel / $checksText / $progressText"
        }
    }

    private fun buildGateChipText(gateResult: GateResult?): String {
        return when (gateResult?.status) {
            GateStatus.PASS -> SpecCodingBundle.message("spec.toolwindow.gate.status.pass")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.toolwindow.gate.status.warning")
            GateStatus.ERROR -> SpecCodingBundle.message("spec.toolwindow.gate.status.error")
            null -> SpecCodingBundle.message("spec.toolwindow.gate.status.unavailable")
        }
    }

    private fun buildTasksChipText(tasks: List<StructuredTask>): String {
        if (tasks.isEmpty()) {
            return "0/0"
        }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        return "$completed/${tasks.size}"
    }

    private fun buildOverviewSectionSummary(
        overviewState: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
        nextStageText: String,
    ): String {
        return buildString {
            append(SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage))
            append(" | ")
            append(workbenchState.progress.completedCheckCount)
            append("/")
            append(workbenchState.progress.totalCheckCount)
            append(" ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.checks.short"))
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.next"))
            append(": ")
            append(nextStageText)
        }
    }

    private fun buildTasksSectionSummary(tasks: List<StructuredTask>): String {
        if (tasks.isEmpty()) {
            return SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow")
        }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        val blocked = tasks.count { it.status == TaskStatus.BLOCKED }
        return SpecCodingBundle.message(
            "spec.toolwindow.tasks.summary",
            tasks.size,
            completed,
            blocked,
        )
    }

    private fun buildGateSectionSummary(gateResult: GateResult?): String {
        if (gateResult == null) {
            return SpecCodingBundle.message("spec.toolwindow.gate.summary.none")
        }
        return SpecCodingBundle.message(
            "spec.toolwindow.gate.summary",
            gateResult.aggregation.errorCount,
            gateResult.aggregation.warningCount,
            gateResult.aggregation.totalViolationCount,
        )
    }

    private fun buildVerifyChipText(state: SpecWorkflowVerifyDeltaState): String {
        val runCount = state.verificationHistory.size
        return "$runCount / ${verificationStatusText(state)}"
    }

    private fun buildVerifySectionSummary(state: SpecWorkflowVerifyDeltaState): String {
        val latest = state.verificationHistory.firstOrNull()?.conclusion?.name
            ?: SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
        return when {
            !state.verifyEnabled -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary.disabled",
                state.baselineChoices.size,
            )

            state.verificationHistory.isEmpty() -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary.noRuns",
                state.baselineChoices.size,
            )

            else -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary",
                state.verificationHistory.size,
                latest,
                state.baselineChoices.size,
            )
        }
    }

    private fun buildDocumentsSectionSummary(workbenchState: SpecWorkflowStageWorkbenchState): String {
        return buildString {
            append(SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage))
            append(" | ")
            append(workbenchState.artifactBinding.fileName ?: workbenchState.artifactBinding.title)
        }
    }

    private fun resolveWorkbenchState(
        workflow: SpecWorkflow,
        state: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowStageWorkbenchState {
        val binding = state.artifactBinding
        val resolvedBinding = when {
            binding.documentPhase != null -> binding.copy(
                available = workflow.documents.containsKey(binding.documentPhase),
                previewContent = workflow.documents[binding.documentPhase]?.content,
            )

            !binding.fileName.isNullOrBlank() -> {
                val path = runCatching {
                    artifactService.locateArtifact(workflow.id, binding.fileName)
                }.getOrNull()
                val available = path?.let(Files::exists) == true
                val previewContent = if (available) {
                    path?.let { artifactPath ->
                        runCatching { Files.readString(artifactPath, StandardCharsets.UTF_8) }.getOrNull()
                    }
                } else {
                    null
                }
                binding.copy(
                    available = available,
                    previewContent = previewContent,
                )
            }

            else -> binding
        }
        return state.copy(artifactBinding = resolvedBinding)
    }

    private fun updateWorkspaceMetric(
        metric: WorkspaceSummaryMetric,
        title: String,
        value: String,
        tone: WorkspaceChipTone,
    ) {
        val colors = workspaceChipColors(tone)
        metric.titleLabel.text = "$title:"
        metric.valueLabel.text = value
        metric.valueLabel.foreground = colors.foreground
        metric.root.isVisible = value.isNotBlank()
    }

    private fun clearWorkspaceMetric(metric: WorkspaceSummaryMetric) {
        metric.titleLabel.text = ""
        metric.valueLabel.text = ""
        metric.root.isVisible = false
    }

    private fun phaseLabel(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }

    private fun workspaceWorkflowStatusText(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun verificationStatusText(state: SpecWorkflowVerifyDeltaState): String {
        if (!state.verifyEnabled) {
            return SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.disabled")
        }
        return when (state.verificationHistory.firstOrNull()?.conclusion) {
            VerificationConclusion.PASS -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pass")
            VerificationConclusion.WARN -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.warn")
            VerificationConclusion.FAIL -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.fail")
            null -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
        }
    }

    private fun clearOpenedWorkflowUi(resetHighlight: Boolean = false) {
        selectedWorkflowId = null
        currentWorkflow = null
        focusedStage = null
        currentWorkbenchState = null
        phaseIndicator.reset()
        overviewPanel.showEmpty()
        tasksPanel.showEmpty()
        gateDetailsPanel.showEmpty()
        verifyDeltaPanel.showEmpty()
        detailPanel.showEmpty()
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        deltaButton.isEnabled = false
        archiveButton.isEnabled = false
        if (resetHighlight) {
            highlightedWorkflowId = null
            listPanel.setSelectedWorkflow(null)
        }
        showWorkspaceEmptyState()
    }

    fun refreshWorkflows(selectWorkflowId: String? = null, showRefreshFeedback: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val ids = specEngine.listWorkflows()
            val items = ids.mapNotNull { id ->
                specEngine.loadWorkflow(id).getOrNull()?.let { wf ->
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = wf.id,
                        title = wf.title.ifBlank { wf.id },
                        description = wf.description,
                        currentPhase = wf.currentPhase,
                        currentStageLabel = SpecWorkflowOverviewPresenter.stageLabel(wf.currentStage),
                        status = wf.status,
                        updatedAt = wf.updatedAt,
                        changeIntent = wf.changeIntent,
                        baselineWorkflowId = wf.baselineWorkflowId,
                    )
                }
            }
            invokeLaterSafe {
                listPanel.updateWorkflows(items)
                setStatusText(null)
                val targetOpenedWorkflowId = selectWorkflowId
                    ?: selectedWorkflowId?.takeIf { target -> items.any { it.workflowId == target } }
                val targetHighlightedWorkflowId = targetOpenedWorkflowId
                    ?: highlightedWorkflowId?.takeIf { target -> items.any { it.workflowId == target } }
                highlightedWorkflowId = targetHighlightedWorkflowId
                listPanel.setSelectedWorkflow(targetHighlightedWorkflowId)
                if (targetOpenedWorkflowId != null) {
                    selectWorkflow(targetOpenedWorkflowId)
                } else {
                    clearOpenedWorkflowUi(resetHighlight = targetHighlightedWorkflowId == null)
                }
                if (showRefreshFeedback) {
                    val successText = SpecCodingBundle.message("common.refresh.success")
                    RefreshFeedback.flashButtonSuccess(refreshButton, successText)
                    RefreshFeedback.flashLabelSuccess(statusLabel, successText, STATUS_SUCCESS_FG)
                }
            }
        }
    }

    private fun onWorkflowFocusedByUser(workflowId: String) {
        highlightedWorkflowId = workflowId
        if (selectedWorkflowId != null && selectedWorkflowId != workflowId) {
            clearOpenedWorkflowUi(resetHighlight = false)
        }
    }

    private fun onWorkflowOpenedByUser(workflowId: String) {
        highlightedWorkflowId = workflowId
        selectWorkflow(workflowId)
        publishWorkflowSelection(workflowId)
    }

    private fun onBackToWorkflowListRequested() {
        clearOpenedWorkflowUi(resetHighlight = false)
    }

    private fun publishWorkflowSelection(workflowId: String) {
        runCatching {
            project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC).onWorkflowChanged(
                SpecWorkflowChangedEvent(
                    workflowId = workflowId,
                    reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
                ),
            )
        }.onFailure { error ->
            logger.warn("Failed to publish workflow selection event: $workflowId", error)
        }
    }

    private fun selectWorkflow(workflowId: String) {
        val previousSelectedWorkflowId = selectedWorkflowId
        selectedWorkflowId = workflowId
        if (previousSelectedWorkflowId != workflowId) {
            focusedStage = null
        }
        overviewPanel.showLoading()
        verifyDeltaPanel.showLoading()
        tasksPanel.showLoading()
        gateDetailsPanel.showLoading()
        showWorkspaceContent()
        scope.launch(Dispatchers.IO) {
            val wf = specEngine.reloadWorkflow(workflowId).getOrNull()
            val uiSnapshot = wf?.let(::buildWorkflowUiSnapshot)
            val tasksResult = runCatching { specTasksService.parse(workflowId) }
            currentWorkflow = wf
            invokeLaterSafe {
                if (selectedWorkflowId != workflowId) {
                    return@invokeLaterSafe
                }
                if (wf != null) {
                    syncClarificationRetryFromWorkflow(wf)
                    phaseIndicator.updatePhase(wf)
                    val snapshot = uiSnapshot ?: buildWorkflowUiSnapshot(wf)
                    overviewPanel.updateOverview(snapshot.overviewState)
                    verifyDeltaPanel.updateState(snapshot.verifyDeltaState)
                    gateDetailsPanel.updateGateResult(
                        workflowId = workflowId,
                        gateResult = snapshot.gateResult,
                        refreshedAtMillis = snapshot.refreshedAtMillis,
                    )
                    detailPanel.updateWorkflow(wf)
                    tasksResult.onSuccess { tasks ->
                        tasksPanel.updateTasks(
                            workflowId = workflowId,
                            tasks = tasks,
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = tasks,
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                    }.onFailure { error ->
                        tasksPanel.updateTasks(
                            workflowId = workflowId,
                            tasks = emptyList(),
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = emptyList(),
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                        val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                    if (previousSelectedWorkflowId != workflowId) {
                        restorePendingClarificationState(workflowId)
                    }
                    createWorktreeButton.isEnabled = true
                    mergeWorktreeButton.isEnabled = true
                    deltaButton.isEnabled = true
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                } else {
                    clearOpenedWorkflowUi(resetHighlight = false)
                }
            }
        }
    }

    private fun onCreateWorkflow() {
        val dialog = NewSpecWorkflowDialog(workflowOptions = listPanel.workflowOptionsForCreate())
        if (dialog.showAndGet()) {
            val title = dialog.resultTitle ?: return
            val desc = dialog.resultDescription ?: ""
            val changeIntent = dialog.resultChangeIntent
            val baselineWorkflowId = dialog.resultBaselineWorkflowId
            scope.launch(Dispatchers.IO) {
                specEngine.createWorkflow(
                    title = title,
                    description = desc,
                    changeIntent = changeIntent,
                    baselineWorkflowId = baselineWorkflowId,
                ).onSuccess { wf ->
                    invokeLaterSafe {
                        highlightedWorkflowId = wf.id
                        refreshWorkflows(selectWorkflowId = wf.id)
                        publishWorkflowSelection(wf.id)
                    }
                }.onFailure { e ->
                    logger.warn("Failed to create workflow", e)
                    invokeLaterSafe {
                        val message = compactErrorMessage(e, SpecCodingBundle.message("common.unknown"))
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                }
            }
        }
    }

    private fun onEditWorkflow(workflowId: String) {
        scope.launch(Dispatchers.IO) {
            val workflow = specEngine.loadWorkflow(workflowId).getOrNull()
            if (workflow == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
                }
                return@launch
            }

            invokeLaterSafe {
                val dialog = EditSpecWorkflowDialog(
                    initialTitle = workflow.title.ifBlank { workflow.id },
                    initialDescription = workflow.description,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLaterSafe
                }
                val title = dialog.resultTitle ?: return@invokeLaterSafe
                val description = dialog.resultDescription ?: ""

                scope.launch(Dispatchers.IO) {
                    val result = specEngine.updateWorkflowMetadata(
                        workflowId = workflowId,
                        title = title,
                        description = description,
                    )

                    invokeLaterSafe {
                        result.onSuccess { updated ->
                            setStatusText(null)
                            val reopenWorkspace = selectedWorkflowId == workflowId
                            highlightedWorkflowId = workflowId
                            if (reopenWorkspace) {
                                currentWorkflow = updated
                                phaseIndicator.updatePhase(updated)
                                detailPanel.updateWorkflow(updated)
                                archiveButton.isEnabled = updated.status == WorkflowStatus.COMPLETED
                            }
                            refreshWorkflows(selectWorkflowId = workflowId.takeIf { reopenWorkspace })
                        }.onFailure { error ->
                            val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                        }
                    }
                }
            }
        }
    }

    private fun onDeleteWorkflow(workflowId: String) {
        scope.launch(Dispatchers.IO) {
            specEngine.deleteWorkflow(workflowId)
            invokeLaterSafe {
                if (selectedWorkflowId == workflowId) {
                    clearOpenedWorkflowUi(resetHighlight = false)
                }
                refreshWorkflows()
            }
        }
    }

    private fun onCreateWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
            return
        }

        val dialog = NewWorktreeDialog(
            specTaskId = workflow.id,
            specTitle = workflow.title.ifBlank { workflow.id },
            baseBranch = worktreeManager.suggestBaseBranch(),
        )
        if (!dialog.showAndGet()) {
            return
        }

        val specTaskId = dialog.resultSpecTaskId ?: workflow.id
        val shortName = dialog.resultShortName ?: return
        val baseBranch = dialog.resultBaseBranch ?: "main"

        scope.launch(Dispatchers.IO) {
            val created = worktreeManager.createWorktree(specTaskId, shortName, baseBranch)
            created.onSuccess { binding ->
                val switched = worktreeManager.switchWorktree(binding.id)
                invokeLaterSafe {
                    if (switched.isSuccess) {
                        setStatusText(SpecCodingBundle.message("spec.workflow.worktree.created", binding.branchName))
                    } else {
                        val message = compactErrorMessage(
                            switched.exceptionOrNull(),
                            SpecCodingBundle.message("common.unknown"),
                        )
                        setStatusText(SpecCodingBundle.message("spec.workflow.worktree.switchFailed", message))
                    }
                }
            }.onFailure { error ->
                invokeLaterSafe {
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.worktree.createFailed",
                        message,
                    ))
                }
            }
        }
    }

    private fun onMergeWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
            return
        }

        scope.launch(Dispatchers.IO) {
            val binding = worktreeManager.listBindings(includeInactive = true)
                .firstOrNull { it.specTaskId == workflow.id && it.status == WorktreeStatus.ACTIVE }
                ?: worktreeManager.listBindings(includeInactive = true)
                    .firstOrNull { it.specTaskId == workflow.id }

            if (binding == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.workflow.worktree.noBinding"))
                }
                return@launch
            }

            val mergeResult = worktreeManager.mergeWorktree(binding.id, binding.baseBranch)
            invokeLaterSafe {
                mergeResult.onSuccess {
                    setStatusText(SpecCodingBundle.message("spec.workflow.worktree.merged", it.targetBranch))
                }.onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.worktree.mergeFailed",
                        message,
                    ))
                }
            }
        }
    }

    private fun canGenerateWithEmptyInput(): Boolean {
        val workflowId = selectedWorkflowId ?: return false
        return pendingClarificationRetryByWorkflowId[workflowId]
            ?.input
            ?.isNotBlank() == true
    }

    private fun onGenerate(input: String) {
        val context = resolveGenerationContext() ?: return
        val pendingRetry = pendingClarificationRetryByWorkflowId[context.workflowId]
        val clarificationRound = (pendingRetry?.clarificationRound ?: 0) + 1
        val effectiveInput = input.ifBlank { pendingRetry?.input.orEmpty() }
        val seededContext = when {
            input.isNotBlank() -> input
            !pendingRetry?.confirmedContext.isNullOrBlank() -> pendingRetry?.confirmedContext.orEmpty()
            else -> effectiveInput
        }
        val shouldResumeWithConfirmedContext = pendingRetry?.confirmed == true &&
            input.isBlank() &&
            pendingRetry.confirmedContext.isNotBlank()
        if (shouldResumeWithConfirmedContext) {
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
            runGeneration(
                workflowId = context.workflowId,
                input = effectiveInput,
                options = context.options.copy(
                    confirmedContext = pendingRetry.confirmedContext,
                ),
            )
            return
        }
        if (pendingRetry == null) {
            detailPanel.clearProcessTimeline()
        }
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.round", clarificationRound),
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )
        if (pendingRetry != null) {
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
        }
        rememberClarificationRetry(
            workflowId = context.workflowId,
            input = effectiveInput,
            confirmedContext = seededContext,
            clarificationRound = clarificationRound,
            lastError = pendingRetry?.lastError,
            confirmed = false,
        )
        requestClarificationDraft(
            context = context,
            input = effectiveInput,
            options = context.options.copy(
                confirmedContext = pendingRetry?.confirmedContext,
            ),
            suggestedDetails = seededContext,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = clarificationRound,
        )
    }

    private fun onClarificationConfirm(
        input: String,
        confirmedContext: String,
    ) {
        val context = resolveGenerationContext()
        if (context == null) {
            detailPanel.unlockClarificationChecklistInteractions()
            return
        }
        detailPanel.lockClarificationChecklistInteractions()
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.confirmed"),
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )
        rememberClarificationRetry(
            workflowId = context.workflowId,
            input = input,
            confirmedContext = confirmedContext,
            clarificationRound = pendingClarificationRetryByWorkflowId[context.workflowId]?.clarificationRound,
            confirmed = true,
        )
        runGeneration(
            workflowId = context.workflowId,
            input = input,
            options = context.options.copy(confirmedContext = confirmedContext),
        )
    }

    private fun onClarificationRegenerate(
        input: String,
        currentDraft: String,
    ) {
        val context = resolveGenerationContext() ?: return
        val pendingRetry = pendingClarificationRetryByWorkflowId[context.workflowId]
        val clarificationRound = (pendingRetry?.clarificationRound ?: 0) + 1
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.regenerate", clarificationRound),
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )
        requestClarificationDraft(
            context = context,
            input = input,
            options = context.options.copy(confirmedContext = currentDraft),
            suggestedDetails = currentDraft,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = clarificationRound,
        )
    }

    private fun onClarificationSkip(input: String) {
        val context = resolveGenerationContext() ?: return
        clearClarificationRetry(context.workflowId)
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.skipped"),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        setStatusText(SpecCodingBundle.message("spec.workflow.clarify.skippedProceed"))
        runGeneration(
            workflowId = context.workflowId,
            input = input,
            options = context.options,
        )
    }

    private fun onClarificationCancel() {
        cancelActiveGenerationRequest("Clarification cancelled by user")
        selectedWorkflowId?.let { workflowId ->
            clearClarificationRetry(workflowId)
        }
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.cancelled"),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        setStatusText(SpecCodingBundle.message("spec.workflow.clarify.cancelled"))
    }

    private fun onClarificationDraftAutosave(
        input: String,
        confirmedContext: String,
        questionsMarkdown: String,
        structuredQuestions: List<String>,
    ) {
        val workflowId = selectedWorkflowId ?: return
        val activeWorkflowId = currentWorkflow?.id
        if (activeWorkflowId != null && activeWorkflowId != workflowId) {
            return
        }
        rememberClarificationRetry(
            workflowId = workflowId,
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
            persist = false,
        )
    }

    private fun requestClarificationDraft(
        context: GenerationContext,
        input: String,
        options: GenerationOptions = context.options,
        suggestedDetails: String = input,
        seedQuestionsMarkdown: String? = null,
        seedStructuredQuestions: List<String> = emptyList(),
        clarificationRound: Int = 1,
    ) {
        cancelActiveGenerationRequest("Superseded by new clarification request")
        val requestOptions = withGenerationRequestId(
            workflowId = context.workflowId,
            phase = context.phase,
            options = options,
        )
        val activeRequest = ActiveGenerationRequest(
            workflowId = context.workflowId,
            providerId = requestOptions.providerId,
            requestId = requestOptions.requestId.orEmpty(),
        )
        activeGenerationRequest = activeRequest
        activeGenerationJob = scope.launch(Dispatchers.IO) {
            try {
                val safeSuggestedDetails = suggestedDetails.ifBlank { input }
                val fallbackPhase = context.phase
                invokeLaterSafe {
                    if (selectedWorkflowId != context.workflowId) {
                        return@invokeLaterSafe
                    }
                    detailPanel.showClarificationGenerating(
                        phase = fallbackPhase,
                        input = input,
                        suggestedDetails = safeSuggestedDetails,
                    )
                    seedQuestionsMarkdown
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            detailPanel.appendProcessTimelineEntry(
                                text = SpecCodingBundle.message("spec.workflow.process.clarify.lastRoundReused"),
                                state = SpecDetailPanel.ProcessTimelineState.INFO,
                            )
                        }
                    detailPanel.appendProcessTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                        state = SpecDetailPanel.ProcessTimelineState.DONE,
                    )
                    detailPanel.appendProcessTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.request", clarificationRound),
                        state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                    )
                    setStatusText(SpecCodingBundle.message("spec.workflow.clarify.generating"))
                }
                val draftResult = try {
                    Result.success(
                        specEngine.draftCurrentPhaseClarification(
                            workflowId = context.workflowId,
                            input = input,
                            options = requestOptions,
                        ).getOrThrow(),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                val draft = draftResult.getOrNull()
                val draftError = draftResult.exceptionOrNull()
                if (draft == null) {
                    logger.warn("Failed to draft clarification for workflow=${context.workflowId}", draftError)
                }
                invokeLaterSafe {
                    if (selectedWorkflowId != context.workflowId) {
                        return@invokeLaterSafe
                    }
                    val markdown = buildClarificationMarkdown(draft, draftError)
                    val structuredQuestions = if (draft != null) {
                        draft.questions
                    } else {
                        seedStructuredQuestions
                    }
                    detailPanel.showClarificationDraft(
                        phase = draft?.phase ?: fallbackPhase,
                        input = input,
                        questionsMarkdown = markdown,
                        suggestedDetails = safeSuggestedDetails,
                        structuredQuestions = structuredQuestions,
                    )
                    val errorText = compactErrorMessage(draftError, SpecCodingBundle.message("common.unknown"))
                    rememberClarificationRetry(
                        workflowId = context.workflowId,
                        input = input,
                        confirmedContext = safeSuggestedDetails,
                        questionsMarkdown = markdown,
                        structuredQuestions = structuredQuestions,
                        clarificationRound = clarificationRound,
                        lastError = draftError?.let { errorText },
                    )
                    if (draft == null) {
                        detailPanel.appendProcessTimelineEntry(
                            text = SpecCodingBundle.message("spec.workflow.process.clarify.failed", errorText),
                            state = SpecDetailPanel.ProcessTimelineState.FAILED,
                        )
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", errorText))
                    } else {
                        detailPanel.appendProcessTimelineEntry(
                            text = SpecCodingBundle.message("spec.workflow.process.clarify.ready"),
                            state = SpecDetailPanel.ProcessTimelineState.DONE,
                        )
                        setStatusText(null)
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveGenerationRequest(activeRequest)) {
                    handleGenerationInterrupted(
                        workflowId = context.workflowId,
                        input = input,
                        options = requestOptions,
                        processMessageKey = "spec.workflow.process.clarify.failed",
                    )
                }
                throw cancel
            } finally {
                clearActiveGenerationRequest(activeRequest)
            }
        }
    }

    private fun runGeneration(
        workflowId: String,
        input: String,
        options: GenerationOptions,
    ) {
        cancelActiveGenerationRequest("Superseded by new generation request")
        val requestOptions = withGenerationRequestId(
            workflowId = workflowId,
            phase = currentWorkflow?.currentPhase ?: SpecPhase.SPECIFY,
            options = options,
        )
        val activeRequest = ActiveGenerationRequest(
            workflowId = workflowId,
            providerId = requestOptions.providerId,
            requestId = requestOptions.requestId.orEmpty(),
        )
        activeGenerationRequest = activeRequest
        activeGenerationJob = scope.launch(Dispatchers.IO) {
            try {
                var modelCallRecorded = false
                var normalizeRecorded = false
                specEngine.generateCurrentPhase(workflowId, input, requestOptions).collect { progress ->
                    invokeLaterSafe {
                        when (progress) {
                            is SpecGenerationProgress.Started -> {
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message("spec.workflow.process.generate.prepare"),
                                    state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                )
                                detailPanel.showGenerating(0.0)
                            }
                            is SpecGenerationProgress.Generating -> {
                                if (!modelCallRecorded) {
                                    detailPanel.appendProcessTimelineEntry(
                                        text = SpecCodingBundle.message(
                                            "spec.workflow.process.generate.call",
                                            (progress.progress * 100).toInt().coerceIn(0, 100),
                                        ),
                                        state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                    )
                                    modelCallRecorded = true
                                }
                                if (progress.progress >= 0.5 && !normalizeRecorded) {
                                    detailPanel.appendProcessTimelineEntry(
                                        text = SpecCodingBundle.message("spec.workflow.process.generate.normalize"),
                                        state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                    )
                                    normalizeRecorded = true
                                }
                                detailPanel.showGenerating(progress.progress)
                            }
                            is SpecGenerationProgress.Completed -> {
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message("spec.workflow.process.generate.validate"),
                                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                                )
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message("spec.workflow.process.generate.save"),
                                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                                )
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message("spec.workflow.process.generate.completed"),
                                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                                )
                                clearClarificationRetry(workflowId)
                                detailPanel.exitClarificationMode(clearInput = true)
                                reloadCurrentWorkflow()
                            }
                            is SpecGenerationProgress.ValidationFailed -> {
                                val firstValidationError = progress.validation.errors.firstOrNull()
                                    ?: SpecCodingBundle.message("common.unknown")
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message("spec.workflow.process.generate.validate"),
                                    state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                )
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message(
                                        "spec.workflow.process.generate.validationFailed",
                                        firstValidationError,
                                    ),
                                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                                )
                                rememberClarificationRetry(
                                    workflowId = workflowId,
                                    input = input,
                                    confirmedContext = requestOptions.confirmedContext,
                                    clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
                                    lastError = firstValidationError,
                                )
                                setStatusText(buildValidationFailureStatus(progress.validation))
                                reloadCurrentWorkflow { updated ->
                                    detailPanel.showValidationFailureInteractive(
                                        phase = updated.currentPhase,
                                        validation = progress.validation,
                                    )
                                }
                            }
                            is SpecGenerationProgress.Failed -> {
                                detailPanel.appendProcessTimelineEntry(
                                    text = SpecCodingBundle.message(
                                        "spec.workflow.process.generate.failed",
                                        progress.error,
                                    ),
                                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                                )
                                rememberClarificationRetry(
                                    workflowId = workflowId,
                                    input = input,
                                    confirmedContext = requestOptions.confirmedContext,
                                    clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
                                    lastError = progress.error,
                                )
                                detailPanel.showGenerationFailed()
                                setStatusText(SpecCodingBundle.message("spec.workflow.error", progress.error))
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveGenerationRequest(activeRequest)) {
                    handleGenerationInterrupted(
                        workflowId = workflowId,
                        input = input,
                        options = requestOptions,
                        processMessageKey = "spec.workflow.process.generate.failed",
                    )
                }
                throw cancel
            } finally {
                clearActiveGenerationRequest(activeRequest)
            }
        }
    }

    private fun handleGenerationInterrupted(
        workflowId: String,
        input: String,
        options: GenerationOptions,
        processMessageKey: String,
    ) {
        val interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted")
        invokeLaterSafe {
            if (selectedWorkflowId != workflowId) {
                return@invokeLaterSafe
            }
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message(processMessageKey, interruptedMessage),
                state = SpecDetailPanel.ProcessTimelineState.FAILED,
            )
            rememberClarificationRetry(
                workflowId = workflowId,
                input = input,
                confirmedContext = options.confirmedContext,
                clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
                lastError = interruptedMessage,
            )
            detailPanel.showGenerationFailed()
            setStatusText(SpecCodingBundle.message("spec.workflow.error", interruptedMessage))
        }
    }

    private fun withGenerationRequestId(
        workflowId: String,
        phase: SpecPhase,
        options: GenerationOptions,
    ): GenerationOptions {
        val existing = options.requestId?.trim().orEmpty()
        if (existing.isNotBlank()) {
            return options.copy(requestId = existing)
        }
        val phaseToken = phase.name.lowercase(Locale.ROOT)
        val randomToken = UUID.randomUUID().toString().substring(0, 8)
        val requestId = "spec-$workflowId-$phaseToken-${System.currentTimeMillis()}-$randomToken"
        return options.copy(requestId = requestId)
    }

    private fun cancelActiveGenerationRequest(reason: String) {
        val activeRequest = activeGenerationRequest
        if (activeRequest != null && activeRequest.requestId.isNotBlank()) {
            cancelRequestAcrossProviders(
                providerId = activeRequest.providerId,
                requestId = activeRequest.requestId,
            )
        }
        activeGenerationJob?.cancel(CancellationException(reason))
        activeGenerationJob = null
        activeGenerationRequest = null
    }

    private fun cancelRequestAcrossProviders(
        providerId: String?,
        requestId: String,
    ) {
        llmRouter.cancel(providerId = providerId, requestId = requestId)
        llmRouter.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
        llmRouter.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
    }

    private fun isActiveGenerationRequest(request: ActiveGenerationRequest): Boolean {
        val active = activeGenerationRequest ?: return false
        return active.workflowId == request.workflowId && active.requestId == request.requestId
    }

    private fun clearActiveGenerationRequest(request: ActiveGenerationRequest) {
        if (!isActiveGenerationRequest(request)) {
            return
        }
        activeGenerationJob = null
        activeGenerationRequest = null
    }

    private fun resolveGenerationContext(): GenerationContext? {
        val wfId = selectedWorkflowId ?: return null
        val workflow = currentWorkflow?.takeIf { it.id == wfId }
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
            return null
        }
        val providerId = providerComboBox.selectedItem as? String
        if (providerId.isNullOrBlank()) {
            setStatusText(SpecCodingBundle.message("spec.workflow.generation.providerRequired"))
            return null
        }
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        if (modelId.isBlank()) {
            setStatusText(SpecCodingBundle.message("spec.workflow.generation.modelRequired", providerDisplayName(providerId)))
            return null
        }
        return GenerationContext(
            workflowId = wfId,
            phase = workflow.currentPhase,
            options = GenerationOptions(
                providerId = providerId,
                model = modelId,
            ),
        )
    }

    private fun buildClarificationMarkdown(
        draft: SpecClarificationDraft?,
        error: Throwable? = null,
    ): String {
        if (draft == null) {
            val base = SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
            val reason = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
            return buildString {
                appendLine(base)
                appendLine()
                appendLine("```text")
                appendLine(reason)
                appendLine("```")
            }.trimEnd()
        }
        if (draft.rawContent.isNotBlank()) {
            return draft.rawContent
        }
        if (draft.questions.isNotEmpty()) {
            return buildString {
                appendLine("## ${SpecCodingBundle.message("spec.detail.clarify.questions.title")}")
                draft.questions.forEachIndexed { index, q ->
                    appendLine("${index + 1}. $q")
                }
            }.trimEnd()
        }
        return SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
    }

    private data class GenerationContext(
        val workflowId: String,
        val phase: SpecPhase,
        val options: GenerationOptions,
    )

    private data class ClarificationRetryPayload(
        val input: String,
        val confirmedContext: String,
        val questionsMarkdown: String,
        val structuredQuestions: List<String>,
        val clarificationRound: Int,
        val lastError: String?,
        val confirmed: Boolean,
    )

    private data class ActiveGenerationRequest(
        val workflowId: String,
        val providerId: String?,
        val requestId: String,
    )

    private fun rememberClarificationRetry(
        workflowId: String,
        input: String,
        confirmedContext: String?,
        questionsMarkdown: String? = null,
        structuredQuestions: List<String>? = null,
        clarificationRound: Int? = null,
        lastError: String? = null,
        confirmed: Boolean? = null,
        persist: Boolean = true,
    ) {
        val previous = pendingClarificationRetryByWorkflowId[workflowId]
        val normalizedInput = normalizeRetryText(input)
        val normalizedContext = confirmedContext?.let { normalizeRetryText(it) }
        val normalizedQuestions = questionsMarkdown?.let { normalizeRetryText(it) }
        val normalizedError = lastError
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val mergedInput = normalizedInput.ifBlank { previous?.input.orEmpty() }
        val mergedContext = when {
            normalizedContext != null -> normalizedContext
            else -> previous?.confirmedContext.orEmpty()
        }
        val mergedQuestions = normalizedQuestions
            ?: previous?.questionsMarkdown
            .orEmpty()
        val mergedStructuredQuestions = structuredQuestions
            ?.map { normalizeRetryText(it) }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: previous?.structuredQuestions
            .orEmpty()
        val mergedRound = clarificationRound
            ?: previous?.clarificationRound
            ?: 1
        val mergedError = normalizedError ?: previous?.lastError
        val mergedConfirmed = confirmed ?: previous?.confirmed ?: false
        if (mergedInput.isBlank() && mergedContext.isBlank() && mergedQuestions.isBlank() && mergedStructuredQuestions.isEmpty()) {
            pendingClarificationRetryByWorkflowId.remove(workflowId)
            if (persist) {
                persistClarificationRetryState(workflowId, null)
            }
            return
        }
        val payload = ClarificationRetryPayload(
            input = mergedInput,
            confirmedContext = mergedContext,
            questionsMarkdown = mergedQuestions,
            structuredQuestions = mergedStructuredQuestions,
            clarificationRound = mergedRound,
            lastError = mergedError,
            confirmed = mergedConfirmed,
        )
        pendingClarificationRetryByWorkflowId[workflowId] = payload
        if (persist) {
            persistClarificationRetryState(workflowId, payload)
        }
    }

    private fun normalizeRetryText(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun clearClarificationRetry(workflowId: String, persist: Boolean = true) {
        pendingClarificationRetryByWorkflowId.remove(workflowId)
        if (persist) {
            persistClarificationRetryState(workflowId, null)
        }
    }

    private fun syncClarificationRetryFromWorkflow(workflow: SpecWorkflow) {
        val workflowId = workflow.id
        val state = workflow.clarificationRetryState
        if (state == null) {
            pendingClarificationRetryByWorkflowId.remove(workflowId)
            return
        }
        pendingClarificationRetryByWorkflowId[workflowId] = state.toPayload()
    }

    private fun persistClarificationRetryState(
        workflowId: String,
        payload: ClarificationRetryPayload?,
    ) {
        scope.launch(Dispatchers.IO) {
            specEngine.saveClarificationRetryState(
                workflowId = workflowId,
                state = payload?.toState(),
            ).onFailure { error ->
                logger.warn("Failed to persist clarification retry state for workflow=$workflowId", error)
            }
        }
    }

    private fun ClarificationRetryPayload.toState(): ClarificationRetryState {
        return ClarificationRetryState(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
        )
    }

    private fun ClarificationRetryState.toPayload(): ClarificationRetryPayload {
        return ClarificationRetryPayload(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
        )
    }

    private fun restorePendingClarificationState(workflowId: String) {
        val payload = pendingClarificationRetryByWorkflowId[workflowId] ?: return
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message(
                "spec.workflow.process.retryRestored",
                payload.clarificationRound,
            ),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        payload.lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { error ->
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryLastError", error),
                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                )
            }
    }

    private fun onShowDelta() {
        val targetWorkflow = currentWorkflow
        if (targetWorkflow == null) {
            setStatusText(SpecCodingBundle.message("spec.delta.error.noCurrentWorkflow"))
            return
        }

        scope.launch(Dispatchers.IO) {
            val candidateWorkflows = specEngine.listWorkflows()
                .filter { it != targetWorkflow.id }
                .mapNotNull { workflowId ->
                    specEngine.loadWorkflow(workflowId).getOrNull()?.let { workflow ->
                        SpecBaselineSelectDialog.WorkflowOption(
                            workflowId = workflow.id,
                            title = workflow.title.ifBlank { workflow.id },
                            description = workflow.description,
                        )
                    }
                }

            if (candidateWorkflows.isEmpty()) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.delta.emptyCandidates"))
                }
                return@launch
            }

            invokeLaterSafe {
                val selectDialog = SpecBaselineSelectDialog(
                    workflowOptions = candidateWorkflows,
                    currentWorkflowId = targetWorkflow.id,
                )
                if (!selectDialog.showAndGet()) {
                    return@invokeLaterSafe
                }

                val baselineWorkflowId = selectDialog.selectedBaselineWorkflowId
                if (baselineWorkflowId.isNullOrBlank()) {
                    setStatusText(SpecCodingBundle.message("spec.delta.selectBaseline.required"))
                    return@invokeLaterSafe
                }

                scope.launch(Dispatchers.IO) {
                    val result = specDeltaService.compareByWorkflowId(
                        baselineWorkflowId = baselineWorkflowId,
                        targetWorkflowId = targetWorkflow.id,
                    )

                    invokeLaterSafe {
                        result.onSuccess { delta ->
                            showDeltaDialog(targetWorkflow, delta)
                            setStatusText(SpecCodingBundle.message("spec.delta.generated"))
                        }.onFailure { error ->
                            setStatusText(SpecCodingBundle.message(
                                "spec.workflow.error",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun onRunVerificationRequested(workflowId: String) {
        SpecWorkflowActionSupport.runVerificationWorkflow(
            project = project,
            verificationService = specVerificationService,
            tasksService = specTasksService,
            workflowId = workflowId,
            onCompleted = { result ->
                reloadCurrentWorkflow()
                setStatusText(result.summary)
            },
        )
    }

    private fun onOpenVerificationRequested(workflowId: String) {
        val path = artifactService.locateArtifact(workflowId, StageId.VERIFY)
        if (!Files.exists(path) || !SpecWorkflowActionSupport.openFile(project, path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    private fun onCompareDeltaBaselineRequested(
        workflowId: String,
        choice: SpecWorkflowDeltaBaselineChoice,
    ) {
        val targetWorkflow = currentWorkflow?.takeIf { workflow -> workflow.id == workflowId } ?: return
        scope.launch(Dispatchers.IO) {
            val result = when (choice) {
                is SpecWorkflowReferenceBaselineChoice -> specDeltaService.compareByWorkflowId(
                    baselineWorkflowId = choice.workflowId,
                    targetWorkflowId = workflowId,
                )

                is SpecWorkflowPinnedDeltaBaselineChoice -> specDeltaService.compareByDeltaBaseline(
                    workflowId = workflowId,
                    baselineId = choice.baseline.baselineId,
                )
            }
            invokeLaterSafe {
                result.onSuccess { delta ->
                    showDeltaDialog(targetWorkflow, delta)
                    setStatusText(SpecCodingBundle.message("spec.delta.generated"))
                }.onFailure { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                    )
                }
            }
        }
    }

    private fun onPinDeltaBaselineRequested(workflowId: String) {
        scope.launch(Dispatchers.IO) {
            val snapshot = specEngine.listWorkflowSnapshots(workflowId).firstOrNull()
            if (snapshot == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.unavailable"))
                }
                return@launch
            }
            val label = SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.pin.autoLabel",
                snapshot.snapshotId,
            )
            val result = specEngine.pinDeltaBaseline(
                workflowId = workflowId,
                snapshotId = snapshot.snapshotId,
                label = label,
            )
            invokeLaterSafe {
                result.onSuccess { baseline ->
                    reloadCurrentWorkflow()
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.toolwindow.verifyDelta.pin.saved",
                            baseline.label ?: baseline.baselineId,
                        ),
                    )
                }.onFailure { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                    )
                }
            }
        }
    }

    private fun showDeltaDialog(
        targetWorkflow: SpecWorkflow,
        delta: SpecWorkflowDelta,
    ) {
        SpecDeltaDialog(
            project = project,
            delta = delta,
            onOpenHistoryDiff = { phase ->
                onShowHistoryDiffForWorkflow(
                    workflowId = targetWorkflow.id,
                    phase = phase,
                    currentDoc = targetWorkflow.documents[phase],
                )
            },
            onExportReport = { format -> specDeltaService.exportReport(delta, format) },
            onReportExported = { export ->
                setStatusText(SpecCodingBundle.message("spec.delta.export.done", export.fileName))
            },
        ).show()
    }

    private fun onArchiveWorkflow() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.selectFirst"))
            return
        }
        if (workflow.status != WorkflowStatus.COMPLETED) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.onlyCompleted"))
            return
        }
        if (!SpecWorkflowActionSupport.confirmArchive(project, workflow.id)) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = specEngine.archiveWorkflow(workflow.id)
            invokeLaterSafe {
                result.onSuccess {
                    if (selectedWorkflowId == workflow.id) {
                        clearOpenedWorkflowUi(resetHighlight = false)
                    }
                    refreshWorkflows()
                    setStatusText(SpecCodingBundle.message("spec.workflow.archive.done", workflow.id))
                }.onFailure { error ->
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.archive.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
        }
    }

    private fun onTaskStatusTransitionRequested(taskId: String, to: TaskStatus) {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.status.progress"),
            task = {
                specTasksService.transitionStatus(
                    workflowId = workflowId,
                    taskId = taskId,
                    to = to,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.status.updated", taskId, to.name))
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskDependsOnUpdateRequested(taskId: String, dependsOn: List<String>) {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.progress"),
            task = {
                specTasksService.updateDependsOn(
                    workflowId = workflowId,
                    taskId = taskId,
                    dependsOn = dependsOn,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.updated", taskId))
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskRelatedFilesUpdateRequested(taskId: String, files: List<String>) {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.progress"),
            task = {
                specTasksService.updateRelatedFiles(
                    workflowId = workflowId,
                    taskId = taskId,
                    files = files,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.updated", taskId))
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskCompleteRequested(taskId: String, files: List<String>) {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
            task = {
                specTasksService.updateRelatedFiles(
                    workflowId = workflowId,
                    taskId = taskId,
                    files = files,
                )
                specTasksService.transitionStatus(
                    workflowId = workflowId,
                    taskId = taskId,
                    to = TaskStatus.COMPLETED,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.complete.updated", taskId))
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onAdvanceStageRequested() {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.ADVANCE,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeAdvanceStage(workflowId)
                    }

                    GateStatus.PASS -> executeAdvanceStage(workflowId)
                }
            },
        )
    }

    private fun onJumpStageRequested() {
        val workflowMeta = currentWorkflow?.toWorkflowMeta() ?: return
        val targets = SpecWorkflowActionSupport.jumpTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.jump.none.title"),
                SpecCodingBundle.message("spec.action.jump.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.jump.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> previewAndJumpToStage(workflowMeta.workflowId, targetStage) },
        )
    }

    private fun onRollbackStageRequested() {
        val workflowMeta = currentWorkflow?.toWorkflowMeta() ?: return
        val targets = SpecWorkflowActionSupport.rollbackTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.rollback.none.title"),
                SpecCodingBundle.message("spec.action.rollback.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.rollback.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> executeRollbackStage(workflowMeta.workflowId, targetStage) },
        )
    }

    private fun onTemplateSwitchRequested(targetTemplate: WorkflowTemplate) {
        val workflow = currentWorkflow ?: return
        if (workflow.template == targetTemplate) {
            return
        }
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.switch.preview"),
            task = {
                specEngine.previewTemplateSwitch(
                    workflowId = workflow.id,
                    toTemplate = targetTemplate,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                val summary = buildTemplateSwitchPreviewSummary(workflow, preview)
                val hasBlockingImpact = preview.artifactImpacts.any { impact ->
                    impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH
                }
                if (hasBlockingImpact) {
                    Messages.showErrorDialog(
                        project,
                        summary,
                        SpecCodingBundle.message("spec.action.template.switch.confirm.title"),
                    )
                    return@runBackground
                }
                val choice = Messages.showDialog(
                    project,
                    summary,
                    SpecCodingBundle.message("spec.action.template.switch.confirm.title"),
                    arrayOf(
                        SpecCodingBundle.message("spec.action.template.switch.confirm.apply"),
                        CommonBundle.getCancelButtonText(),
                    ),
                    0,
                    Messages.getQuestionIcon(),
                )
                if (choice != 0) {
                    return@runBackground
                }
                executeTemplateSwitch(workflow.id, preview.previewId, preview.toTemplate)
            },
        )
    }

    private fun onTemplateSwitchRollbackRequested(historyEntry: TemplateSwitchHistoryEntry) {
        val workflowId = selectedWorkflowId ?: return
        val choice = Messages.showDialog(
            project,
            buildTemplateSwitchRollbackSummary(historyEntry),
            SpecCodingBundle.message("spec.action.template.rollback.confirm.title"),
            arrayOf(
                SpecCodingBundle.message("spec.action.template.rollback.confirm.rollback"),
                CommonBundle.getCancelButtonText(),
            ),
            0,
            Messages.getWarningIcon(),
        )
        if (choice != 0) {
            return
        }
        executeTemplateSwitchRollback(workflowId, historyEntry.switchId)
    }

    private fun previewAndJumpToStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.JUMP,
                    targetStage = targetStage,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeJumpStage(workflowId, targetStage)
                    }

                    GateStatus.PASS -> executeJumpStage(workflowId, targetStage)
                }
            },
        )
    }

    private fun executeAdvanceStage(workflowId: String) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.executing"),
            task = { specEngine.advanceWorkflow(workflowId) { true }.getOrThrow() },
            onSuccess = { result ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.advance.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
            },
        )
    }

    private fun executeJumpStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.executing"),
            task = { specEngine.jumpToStage(workflowId, targetStage) { true }.getOrThrow() },
            onSuccess = { result ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.jump.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
            },
        )
    }

    private fun executeRollbackStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.rollback.executing"),
            task = { specEngine.rollbackToStage(workflowId, targetStage).getOrThrow() },
            onSuccess = { meta ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.rollback.success",
                        SpecWorkflowActionSupport.stageLabel(meta.currentStage),
                    ),
                )
            },
        )
    }

    private fun executeTemplateSwitch(
        workflowId: String,
        previewId: String,
        targetTemplate: WorkflowTemplate,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.switch.executing"),
            task = { specEngine.applyTemplateSwitch(workflowId, previewId).getOrThrow() },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.template.switch.success",
                        SpecWorkflowOverviewPresenter.templateLabel(targetTemplate),
                    ),
                )
                publishWorkflowSelection(workflowId)
                refreshWorkflows(selectWorkflowId = workflowId)
            },
        )
    }

    private fun executeTemplateSwitchRollback(
        workflowId: String,
        switchId: String,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.rollback.executing"),
            task = { specEngine.rollbackTemplateSwitch(workflowId, switchId).getOrThrow() },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.template.rollback.success",
                        SpecWorkflowOverviewPresenter.templateLabel(result.workflow.template),
                    ),
                )
                publishWorkflowSelection(workflowId)
                refreshWorkflows(selectWorkflowId = workflowId)
            },
        )
    }

    private fun handleStageTransitionCompleted(workflowId: String, successMessage: String) {
        SpecWorkflowActionSupport.notifySuccess(project, successMessage)
        publishWorkflowSelection(workflowId)
        refreshWorkflows(selectWorkflowId = workflowId)
    }

    private fun buildTemplateSwitchPreviewSummary(
        workflow: SpecWorkflow,
        preview: TemplateSwitchPreview,
    ): String {
        val lines = mutableListOf<String>()
        lines += SpecCodingBundle.message("spec.action.template.switch.summary.workflow", workflow.id)
        lines += SpecCodingBundle.message(
            "spec.action.template.switch.summary.templates",
            SpecWorkflowOverviewPresenter.templateLabel(preview.fromTemplate),
            SpecWorkflowOverviewPresenter.templateLabel(preview.toTemplate),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.switch.summary.stage",
            SpecWorkflowActionSupport.stageLabel(preview.currentStage),
            SpecWorkflowActionSupport.stageLabel(preview.resultingStage),
        )
        if (preview.currentStageChanged) {
            lines += SpecCodingBundle.message("spec.action.template.switch.summary.stageChanged")
        }
        lines += ""
        lines += SpecCodingBundle.message(
            "spec.action.template.switch.summary.addedStages",
            formatStageList(preview.addedActiveStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.switch.summary.deactivatedStages",
            formatStageList(preview.deactivatedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.switch.summary.gateAddedStages",
            formatStageList(preview.gateAddedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.switch.summary.gateRemovedStages",
            formatStageList(preview.gateRemovedStages),
        )
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.switch.summary.artifacts")
        preview.artifactImpacts.forEach { impact ->
            lines += SpecCodingBundle.message(
                "spec.action.template.switch.summary.artifact",
                impact.fileName,
                SpecWorkflowActionSupport.stageLabel(impact.stageId),
                templateSwitchStrategyLabel(impact.strategy),
            )
        }
        if (preview.artifactImpacts.any { impact -> impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH }) {
            lines += ""
            lines += SpecCodingBundle.message("spec.action.template.switch.summary.blocked")
        }
        return lines.joinToString("\n")
    }

    private fun buildTemplateSwitchRollbackSummary(historyEntry: TemplateSwitchHistoryEntry): String {
        return listOf(
            SpecCodingBundle.message(
                "spec.action.template.rollback.summary.templates",
                SpecWorkflowOverviewPresenter.templateLabel(historyEntry.fromTemplate),
                SpecWorkflowOverviewPresenter.templateLabel(historyEntry.toTemplate),
            ),
            SpecCodingBundle.message(
                "spec.action.template.rollback.summary.occurredAt",
                historyEntry.occurredAt,
            ),
            historyEntry.generatedArtifacts
                .takeIf { artifacts -> artifacts.isNotEmpty() }
                ?.joinToString(", ")
                ?.let { generatedArtifacts ->
                    SpecCodingBundle.message(
                        "spec.action.template.rollback.summary.generatedArtifacts",
                        generatedArtifacts,
                    )
                },
            SpecCodingBundle.message("spec.action.template.rollback.summary.note"),
        ).filterNotNull().joinToString("\n")
    }

    private fun formatStageList(stages: List<StageId>): String {
        if (stages.isEmpty()) {
            return SpecCodingBundle.message("spec.action.template.switch.summary.none")
        }
        return stages.joinToString(", ") { stage ->
            SpecWorkflowActionSupport.stageLabel(stage)
        }
    }

    private fun templateSwitchStrategyLabel(strategy: TemplateSwitchArtifactStrategy): String {
        return when (strategy) {
            TemplateSwitchArtifactStrategy.REUSE_EXISTING ->
                SpecCodingBundle.message("spec.action.template.switch.strategy.reuse")
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON ->
                SpecCodingBundle.message("spec.action.template.switch.strategy.generate")
            TemplateSwitchArtifactStrategy.BLOCK_SWITCH ->
                SpecCodingBundle.message("spec.action.template.switch.strategy.block")
        }
    }

    private fun onShowCodeGraph() {
        setStatusText(SpecCodingBundle.message("code.graph.status.generating"))
        scope.launch(Dispatchers.Default) {
            val result = codeGraphService.buildFromActiveEditor()
            invokeLaterSafe {
                result.onSuccess { snapshot ->
                    if (snapshot.edges.isEmpty()) {
                        setStatusText(SpecCodingBundle.message("code.graph.status.empty"))
                        return@onSuccess
                    }

                    val summary = CodeGraphRenderer.renderSummary(snapshot)
                    val mermaid = CodeGraphRenderer.renderMermaid(snapshot)
                    CodeGraphDialog(summary = summary, mermaid = mermaid).show()
                    setStatusText(SpecCodingBundle.message(
                        "code.graph.status.generated",
                        snapshot.nodes.size,
                        snapshot.edges.size,
                    ))
                }.onFailure { error ->
                    setStatusText(SpecCodingBundle.message(
                        "code.graph.status.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
        }
    }

    private fun onNextPhase() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.proceedToNextPhase(wfId).onSuccess {
                invokeLaterSafe {
                    detailPanel.clearInput()
                    reloadCurrentWorkflow(followCurrentPhase = true)
                }
            }.onFailure { e ->
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    ))
                }
            }
        }
    }

    private fun onGoBack() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.goBackToPreviousPhase(wfId).onSuccess {
                invokeLaterSafe {
                    detailPanel.clearInput()
                    reloadCurrentWorkflow(followCurrentPhase = true)
                }
            }.onFailure { e ->
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    ))
                }
            }
        }
    }

    private fun subscribeToLocaleEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun subscribeToToolWindowControlEvents() {
        project.messageBus.connect(this).subscribe(
            SpecToolWindowControlListener.TOPIC,
            object : SpecToolWindowControlListener {
                override fun onCreateWorkflowRequested() {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        onCreateWorkflow()
                    }
                }

                override fun onSelectWorkflowRequested(workflowId: String) {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        refreshWorkflows(selectWorkflowId = workflowId)
                    }
                }
            },
        )
    }

    private fun subscribeToWorkflowEvents() {
        project.messageBus.connect(this).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    if (event.reason == SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED) {
                        return
                    }
                    refreshWorkflows(selectWorkflowId = event.workflowId)
                }
            },
        )
    }

    private fun subscribeToDocumentFileEvents() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val workflowId = selectedWorkflowId ?: return
                    val basePath = project.basePath ?: return
                    if (!containsCurrentWorkflowDocumentChange(events, basePath, workflowId)) {
                        return
                    }
                    scheduleDocumentReload(workflowId)
                }
            },
        )
    }

    private fun scheduleDocumentReload(workflowId: String) {
        pendingDocumentReloadJob?.cancel()
        pendingDocumentReloadJob = scope.launch {
            delay(DOCUMENT_RELOAD_DEBOUNCE_MILLIS)
            if (_isDisposed || project.isDisposed || selectedWorkflowId != workflowId) {
                return@launch
            }
            reloadCurrentWorkflow()
        }
    }

    private fun containsCurrentWorkflowDocumentChange(
        events: List<VFileEvent>,
        basePath: String,
        workflowId: String,
    ): Boolean {
        if (events.isEmpty()) return false
        val normalizedBasePath = basePath
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)
        val targetPrefix = "$normalizedBasePath/.spec-coding/specs/$workflowId/"
        return events.any { event ->
            val normalizedPath = event.path
                .replace('\\', '/')
                .lowercase(Locale.ROOT)
            if (!normalizedPath.startsWith(targetPrefix)) {
                return@any false
            }
            val fileName = normalizedPath.substringAfterLast('/')
            SPEC_DOCUMENT_FILE_NAMES.contains(fileName)
        }
    }

    private fun refreshLocalizedTexts() {
        listPanel.refreshLocalizedTexts()
        detailPanel.refreshLocalizedTexts()
        overviewPanel.refreshLocalizedTexts()
        verifyDeltaPanel.refreshLocalizedTexts()
        tasksPanel.refreshLocalizedTexts()
        gateDetailsPanel.refreshLocalizedTexts()
        if (::overviewSection.isInitialized) {
            overviewSection.refreshLocalizedTexts()
            tasksSection.refreshLocalizedTexts()
            gateSection.refreshLocalizedTexts()
            verifySection.refreshLocalizedTexts()
            documentsSection.refreshLocalizedTexts()
        }
        applyToolbarButtonPresentation()
        modelLabel.text = SpecCodingBundle.message("toolwindow.model.label")
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)
        refreshProviderCombo(preserveSelection = true)
        refreshWorkspacePresentation()
        setStatusText(null)
    }

    private fun onComplete() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.completeWorkflow(wfId).onSuccess {
                invokeLaterSafe {
                    reloadCurrentWorkflow()
                    refreshWorkflows()
                }
            }
        }
    }

    private fun onPauseResume() {
        val wfId = selectedWorkflowId ?: return
        val isPaused = currentWorkflow?.status == WorkflowStatus.PAUSED
        scope.launch(Dispatchers.IO) {
            val result = if (isPaused)
                specEngine.resumeWorkflow(wfId)
            else
                specEngine.pauseWorkflow(wfId)
            result.onSuccess {
                invokeLaterSafe {
                    reloadCurrentWorkflow()
                    refreshWorkflows()
                }
            }
        }
    }

    private fun onOpenInEditor(phase: SpecPhase) {
        val wfId = selectedWorkflowId ?: return
        val basePath = project.basePath ?: return
        val filePath = "$basePath/.spec-coding/specs/$wfId/${phase.outputFileName}"
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun onOpenArtifactInEditor(fileName: String) {
        val workflowId = selectedWorkflowId ?: return
        val path = runCatching { artifactService.locateArtifact(workflowId, fileName) }.getOrNull() ?: return
        if (!Files.exists(path) || !SpecWorkflowActionSupport.openFile(project, path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    private fun onShowHistoryDiff(phase: SpecPhase) {
        val workflow = currentWorkflow ?: return
        onShowHistoryDiffForWorkflow(
            workflowId = workflow.id,
            phase = phase,
            currentDoc = workflow.documents[phase],
        )
    }

    private fun onSaveDocument(
        phase: SpecPhase,
        content: String,
        onDone: (Result<SpecWorkflow>) -> Unit,
    ) {
        val wfId = selectedWorkflowId
        if (wfId.isNullOrBlank()) {
            onDone(Result.failure(IllegalStateException(SpecCodingBundle.message("spec.detail.noWorkflow"))))
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = specEngine.updateDocumentContent(
                workflowId = wfId,
                phase = phase,
                content = content,
            )
            currentWorkflow = result.getOrNull() ?: currentWorkflow
            invokeLaterSafe {
                result.onSuccess { wf ->
                    phaseIndicator.updatePhase(wf)
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                    setStatusText(null)
                }.onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                }
                onDone(result)
            }
        }
    }

    private fun onShowHistoryDiffForWorkflow(
        workflowId: String,
        phase: SpecPhase,
        currentDoc: SpecDocument?,
    ) {
        if (currentDoc == null) {
            setStatusText(SpecCodingBundle.message("spec.history.noCurrentDocument"))
            return
        }

        scope.launch(Dispatchers.IO) {
            val history = specEngine.listDocumentHistory(workflowId, phase)
            val snapshots = history.mapNotNull { entry ->
                specEngine.loadDocumentSnapshot(workflowId, phase, entry.snapshotId).getOrNull()?.let { snapshotDoc ->
                    SpecHistoryDiffDialog.SnapshotVersion(
                        snapshotId = entry.snapshotId,
                        createdAt = entry.createdAt,
                        document = snapshotDoc,
                    )
                }
            }

            invokeLaterSafe {
                if (snapshots.isEmpty()) {
                    setStatusText(SpecCodingBundle.message("spec.history.noSnapshot"))
                    return@invokeLaterSafe
                }

                SpecHistoryDiffDialog(
                    phase = phase,
                    currentDocument = currentDoc,
                    snapshots = snapshots,
                    onDeleteSnapshot = { snapshot ->
                        specEngine.deleteDocumentSnapshot(workflowId, phase, snapshot.snapshotId).isSuccess
                    },
                    onPruneSnapshots = { keepLatest ->
                        specEngine.pruneDocumentHistory(workflowId, phase, keepLatest).getOrElse { -1 }
                    },
                    onExportSummary = { content ->
                        exportHistoryDiffSummary(workflowId, phase, content)
                    },
                ).show()

                setStatusText(SpecCodingBundle.message(
                    "spec.history.diff.opened",
                    phase.displayName,
                    snapshots.size,
                ))
            }
        }
    }

    private fun exportHistoryDiffSummary(
        workflowId: String,
        phase: SpecPhase,
        content: String,
    ): Result<String> {
        return runCatching {
            val basePath = project.basePath
                ?: throw IllegalStateException(SpecCodingBundle.message("history.error.projectBasePathUnavailable"))
            val exportDir = Path.of(basePath, ".spec-coding", "exports")
            Files.createDirectories(exportDir)

            val safeWorkflowId = workflowId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "spec-history-diff-${safeWorkflowId}-${phase.name.lowercase()}-${System.currentTimeMillis()}.md"
            val target = exportDir.resolve(fileName)
            Files.writeString(target, content, StandardCharsets.UTF_8)
            fileName
        }
    }

    private fun reloadCurrentWorkflow(
        followCurrentPhase: Boolean = false,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        val wfId = selectedWorkflowId ?: return
        invokeLaterSafe {
            showWorkspaceContent()
            overviewPanel.showLoading()
            verifyDeltaPanel.showLoading()
            tasksPanel.showLoading()
            gateDetailsPanel.showLoading()
        }
        scope.launch(Dispatchers.IO) {
            val wf = specEngine.reloadWorkflow(wfId).getOrNull()
            val uiSnapshot = wf?.let(::buildWorkflowUiSnapshot)
            val tasksResult = runCatching { specTasksService.parse(wfId) }
            currentWorkflow = wf
            invokeLaterSafe {
                if (wf != null) {
                    syncClarificationRetryFromWorkflow(wf)
                    phaseIndicator.updatePhase(wf)
                    val snapshot = uiSnapshot ?: buildWorkflowUiSnapshot(wf)
                    overviewPanel.updateOverview(snapshot.overviewState)
                    verifyDeltaPanel.updateState(snapshot.verifyDeltaState)
                    gateDetailsPanel.updateGateResult(
                        workflowId = wf.id,
                        gateResult = snapshot.gateResult,
                        refreshedAtMillis = snapshot.refreshedAtMillis,
                    )
                    detailPanel.updateWorkflow(wf, followCurrentPhase = followCurrentPhase)
                    tasksResult.onSuccess { tasks ->
                        tasksPanel.updateTasks(
                            workflowId = wf.id,
                            tasks = tasks,
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = tasks,
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                    }.onFailure { error ->
                        tasksPanel.updateTasks(
                            workflowId = wf.id,
                            tasks = emptyList(),
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = emptyList(),
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                        val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                    onUpdated?.invoke(wf)
                } else {
                    clearOpenedWorkflowUi(resetHighlight = false)
                }
            }
        }
    }

    private enum class WorkspaceChipTone {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
        MUTED,
    }

    private data class WorkspaceChipColors(
        val foreground: Color,
    )

    private data class WorkspaceSummaryMetric(
        val root: JPanel,
        val titleLabel: JBLabel,
        val valueLabel: JBLabel,
    )

    private data class WorkflowUiSnapshot(
        val overviewState: SpecWorkflowOverviewState,
        val verifyDeltaState: SpecWorkflowVerifyDeltaState,
        val gateResult: GateResult?,
        val refreshedAtMillis: Long,
    )

    private fun buildWorkflowUiSnapshot(workflow: SpecWorkflow): WorkflowUiSnapshot {
        val refreshedAtMillis = System.currentTimeMillis()
        val gatePreview = if (workflow.status == WorkflowStatus.COMPLETED || workflow.currentStage == StageId.ARCHIVE) {
            null
        } else {
            specEngine.previewStageTransition(
                workflowId = workflow.id,
                transitionType = StageTransitionType.ADVANCE,
            ).getOrElse { error ->
                logger.debug("Unable to preview advance gate for workflow ${workflow.id}", error)
                null
            }
        }
        val latestTemplateSwitch = runCatching {
            specEngine.listTemplateSwitchHistory(workflow.id).getOrThrow()
                .firstOrNull { entry -> !entry.rolledBack }
        }.getOrElse { error ->
            logger.debug("Unable to load template switch history for workflow ${workflow.id}", error)
            null
        }
        return WorkflowUiSnapshot(
            overviewState = SpecWorkflowOverviewPresenter.buildState(
                workflow = workflow,
                gatePreview = gatePreview,
                latestTemplateSwitch = latestTemplateSwitch,
                refreshedAtMillis = refreshedAtMillis,
            ),
            verifyDeltaState = buildVerifyDeltaState(
                workflow = workflow,
                refreshedAtMillis = refreshedAtMillis,
            ),
            gateResult = gatePreview?.gateResult,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun buildVerifyDeltaState(
        workflow: SpecWorkflow,
        refreshedAtMillis: Long,
    ): SpecWorkflowVerifyDeltaState {
        val verificationHistory = runCatching {
            specVerificationService.listRunHistory(workflow.id)
        }.getOrElse { error ->
            logger.debug("Unable to load verification history for workflow ${workflow.id}", error)
            emptyList()
        }
        val baselineChoices = buildList {
            workflow.baselineWorkflowId
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != workflow.id }
                ?.let { baselineWorkflowId ->
                    val baselineTitle = specEngine.loadWorkflow(baselineWorkflowId).getOrNull()
                        ?.title
                        ?.ifBlank { baselineWorkflowId }
                        ?: baselineWorkflowId
                    add(
                        SpecWorkflowReferenceBaselineChoice(
                            workflowId = baselineWorkflowId,
                            title = baselineTitle,
                        ),
                    )
                }
            addAll(
                runCatching {
                    specEngine.listDeltaBaselines(workflow.id)
                }.getOrElse { error ->
                    logger.debug("Unable to load delta baselines for workflow ${workflow.id}", error)
                    emptyList()
                }.map(::SpecWorkflowPinnedDeltaBaselineChoice),
            )
        }
        val canPinBaseline = runCatching {
            specEngine.listWorkflowSnapshots(workflow.id).isNotEmpty()
        }.getOrElse { error ->
            logger.debug("Unable to inspect workflow snapshots for ${workflow.id}", error)
            false
        }
        val deltaSummary = baselineChoices.firstOrNull()?.let { preferredChoice ->
            runCatching {
                when (preferredChoice) {
                    is SpecWorkflowReferenceBaselineChoice -> specDeltaService.compareByWorkflowId(
                        baselineWorkflowId = preferredChoice.workflowId,
                        targetWorkflowId = workflow.id,
                    )

                    is SpecWorkflowPinnedDeltaBaselineChoice -> specDeltaService.compareByDeltaBaseline(
                        workflowId = workflow.id,
                        baselineId = preferredChoice.baseline.baselineId,
                    )
                }.getOrThrow()
            }.map(::buildPreferredDeltaSummary).getOrElse { error ->
                logger.debug("Unable to build delta summary for workflow ${workflow.id}", error)
                null
            }
        }
        return SpecWorkflowVerifyDeltaState(
            workflowId = workflow.id,
            verifyEnabled = workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true,
            verificationDocumentAvailable = hasVerificationArtifact(workflow.id),
            verificationHistory = verificationHistory,
            baselineChoices = baselineChoices,
            deltaSummary = deltaSummary,
            preferredBaselineChoiceId = baselineChoices.firstOrNull()?.stableId,
            canPinBaseline = canPinBaseline,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun hasVerificationArtifact(workflowId: String): Boolean {
        return runCatching {
            Files.exists(artifactService.locateArtifact(workflowId, StageId.VERIFY))
        }.getOrDefault(false)
    }

    private fun buildValidationFailureStatus(validation: ValidationResult): String {
        val firstError = validation.errors.firstOrNull()
        if (firstError.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.workflow.validation.failed.unknown")
        }
        return if (validation.errors.size > 1) {
            SpecCodingBundle.message(
                "spec.workflow.validation.failed.more",
                firstError,
                validation.errors.size - 1,
            )
        } else {
            SpecCodingBundle.message("spec.workflow.validation.failed", firstError)
        }
    }

    private fun compactErrorMessage(error: Throwable?, fallback: String, maxLength: Int = 220): String {
        val compact = generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                throwable.message
                    ?.replace('\n', ' ')
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull { candidate -> isMeaningfulErrorMessage(candidate) }
            ?: fallback
        if (compact.length <= maxLength) {
            return compact
        }
        return compact.take(maxLength - 1).trimEnd() + "…"
    }

    private fun isMeaningfulErrorMessage(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.lowercase(Locale.ROOT) in PLACEHOLDER_ERROR_MESSAGES) {
            return false
        }
        if (normalized.length <= 3 && PLACEHOLDER_SYMBOLS_REGEX.matches(normalized)) {
            return false
        }
        return ERROR_TEXT_CONTENT_REGEX.containsMatchIn(normalized)
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!_isDisposed && !project.isDisposed) action()
        }
    }

    internal fun isListModeForTest(): Boolean {
        return centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === listSectionContainer
    }

    internal fun isDetailModeForTest(): Boolean {
        return centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === workspacePanelContainer
    }

    internal fun selectedWorkflowIdForTest(): String? = selectedWorkflowId

    internal fun highlightedWorkflowIdForTest(): String? = highlightedWorkflowId

    internal fun workflowIdsForTest(): List<String> {
        return listPanel.itemsForTest().map { it.workflowId }
    }

    internal fun openWorkflowForTest(workflowId: String) {
        highlightedWorkflowId = workflowId
        listPanel.setSelectedWorkflow(workflowId)
        onWorkflowOpenedByUser(workflowId)
    }

    internal fun clickBackToListForTest() {
        backToListButton.doClick()
    }

    internal fun isBackButtonInlineForTest(): Boolean {
        return javax.swing.SwingUtilities.isDescendingFrom(backToListButton, workspaceCardPanel)
    }

    internal fun visibleWorkspaceSectionIdsForTest(): Set<SpecWorkflowWorkspaceSectionId> {
        return workspaceSectionItems
            .filterValues { it.isVisible }
            .keys
            .toCollection(linkedSetOf())
    }

    private fun focusStage(stageId: StageId) {
        focusedStage = stageId
        val workflow = currentWorkflow ?: return
        val overviewState = currentOverviewState ?: buildWorkflowUiSnapshot(workflow).overviewState
        val verifyState = currentVerifyDeltaState ?: buildVerifyDeltaState(
            workflow = workflow,
            refreshedAtMillis = System.currentTimeMillis(),
        )
        updateWorkspacePresentation(
            workflow = workflow,
            overviewState = overviewState,
            tasks = currentStructuredTasks,
            verifyDeltaState = verifyState,
            gateResult = currentGateResult,
        )
    }

    internal fun focusStageForTest(stageId: StageId) {
        focusStage(stageId)
    }

    private fun onOverviewStageSelected(stageId: StageId) {
        focusStage(stageId)
    }

    private fun onWorkbenchActionRequested(action: SpecWorkflowWorkbenchAction) {
        when (action.kind) {
            SpecWorkflowWorkbenchActionKind.ADVANCE -> onAdvanceStageRequested()
            SpecWorkflowWorkbenchActionKind.JUMP -> {
                val workflowId = currentWorkflow?.id
                val targetStage = action.targetStage
                if (workflowId != null && targetStage != null) {
                    previewAndJumpToStage(workflowId, targetStage)
                } else {
                    onJumpStageRequested()
                }
            }

            SpecWorkflowWorkbenchActionKind.ROLLBACK -> {
                val workflowId = currentWorkflow?.id
                val targetStage = action.targetStage
                if (workflowId != null && targetStage != null) {
                    executeRollbackStage(workflowId, targetStage)
                } else {
                    onRollbackStageRequested()
                }
            }

            SpecWorkflowWorkbenchActionKind.START_TASK -> {
                val taskId = action.taskId ?: return
                tasksPanel.selectTask(taskId)
                onTaskStatusTransitionRequested(taskId, TaskStatus.IN_PROGRESS)
            }

            SpecWorkflowWorkbenchActionKind.COMPLETE_TASK -> {
                val taskId = action.taskId ?: return
                tasksPanel.selectTask(taskId)
                if (!tasksPanel.requestCompletionForTask(taskId)) {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.complete.failed", taskId))
                }
            }

            SpecWorkflowWorkbenchActionKind.RUN_VERIFY -> {
                val workflowId = currentWorkflow?.id ?: return
                onRunVerificationRequested(workflowId)
            }

            SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN -> {
                val workflowId = currentWorkflow?.id ?: return
                onPreviewVerificationPlanRequested(workflowId)
            }

            SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION -> {
                val workflowId = currentWorkflow?.id ?: return
                onOpenVerificationRequested(workflowId)
            }

            SpecWorkflowWorkbenchActionKind.SHOW_DELTA -> onShowDelta()

            SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW -> onComplete()

            SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW -> onArchiveWorkflow()
        }
    }

    private fun onPreviewVerificationPlanRequested(workflowId: String) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.preview"),
            task = {
                val plan = specVerificationService.preview(workflowId)
                val scopeTasks = specTasksService.parse(workflowId).sortedBy(StructuredTask::id)
                SpecWorkflowActionSupport.verificationPlanSummary(plan, scopeTasks)
            },
            onSuccess = { summary ->
                SpecWorkflowActionSupport.showInfo(
                    project = project,
                    title = SpecCodingBundle.message("spec.action.verify.confirm.title"),
                    message = summary,
                )
            },
        )
    }

    private fun buildPreferredDeltaSummary(delta: SpecWorkflowDelta): String {
        return SpecCodingBundle.message(
            "spec.delta.summary",
            delta.baselineWorkflowId,
            delta.targetWorkflowId,
            delta.count(SpecDeltaStatus.ADDED),
            delta.count(SpecDeltaStatus.MODIFIED),
            delta.count(SpecDeltaStatus.REMOVED),
            delta.count(SpecDeltaStatus.UNCHANGED),
        )
    }

    internal fun focusedStageForTest(): StageId? = currentWorkbenchState?.focusedStage

    internal fun selectedDocumentPhaseForTest(): String? = detailPanel.selectedPhaseNameForTest()

    internal fun currentDocumentPreviewTextForTest(): String = detailPanel.currentPreviewTextForTest()

    internal fun currentDocumentMetaTextForTest(): String = detailPanel.currentDocumentMetaTextForTest()

    internal fun workspaceSummarySnapshotForTest(): Map<String, String> {
        return mapOf(
            "stageTitle" to workspaceStageMetric.titleLabel.text.orEmpty(),
            "stageValue" to workspaceStageMetric.valueLabel.text.orEmpty(),
            "gateTitle" to workspaceGateMetric.titleLabel.text.orEmpty(),
            "gateValue" to workspaceGateMetric.valueLabel.text.orEmpty(),
            "tasksTitle" to workspaceTasksMetric.titleLabel.text.orEmpty(),
            "tasksValue" to workspaceTasksMetric.valueLabel.text.orEmpty(),
            "verifyTitle" to workspaceVerifyMetric.titleLabel.text.orEmpty(),
            "verifyValue" to workspaceVerifyMetric.valueLabel.text.orEmpty(),
            "focusTitle" to workspaceSummaryFocusLabel.text.orEmpty(),
            "focusHint" to workspaceSummaryHintLabel.text.orEmpty(),
        )
    }

    override fun dispose() {
        _isDisposed = true
        pendingDocumentReloadJob?.cancel()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        cancelActiveGenerationRequest("Spec workflow panel disposed")
        scope.cancel()
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val WORKSPACE_SUMMARY_BG = JBColor(Color(245, 249, 255), Color(56, 62, 72))
        private val WORKSPACE_SUMMARY_BORDER = JBColor(Color(201, 214, 235), Color(86, 96, 110))
        private val WORKSPACE_SUMMARY_TITLE_FG = JBColor(Color(42, 59, 94), Color(214, 223, 236))
        private val WORKSPACE_SUMMARY_META_FG = JBColor(Color(94, 110, 139), Color(160, 171, 188))
        private val WORKSPACE_SUMMARY_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val WORKSPACE_EMPTY_TITLE_FG = JBColor(Color(57, 72, 104), Color(214, 223, 236))
        private val WORKSPACE_EMPTY_DESCRIPTION_FG = JBColor(Color(101, 117, 145), Color(166, 176, 193))
        private val WORKSPACE_INFO_CHIP_FG = JBColor(Color(48, 74, 112), Color(210, 220, 235))
        private val WORKSPACE_SUCCESS_CHIP_FG = JBColor(Color(42, 118, 71), Color(177, 225, 194))
        private val WORKSPACE_WARNING_CHIP_FG = JBColor(Color(140, 96, 28), Color(239, 210, 146))
        private val WORKSPACE_ERROR_CHIP_FG = JBColor(Color(152, 52, 52), Color(244, 182, 182))
        private val WORKSPACE_MUTED_CHIP_FG = JBColor(Color(98, 109, 126), Color(173, 181, 194))
        private val ICON_BUTTON_BG = JBColor(Color(246, 250, 255), Color(68, 75, 87))
        private val ICON_BUTTON_BORDER = JBColor(Color(178, 198, 226), Color(104, 116, 134))
        private val ICON_BUTTON_BG_HOVER = JBColor(Color(236, 246, 255), Color(76, 86, 100))
        private val ICON_BUTTON_BORDER_HOVER = JBColor(Color(124, 167, 229), Color(124, 158, 205))
        private val ICON_BUTTON_BG_ACTIVE = JBColor(Color(226, 239, 255), Color(84, 97, 116))
        private val ICON_BUTTON_BORDER_ACTIVE = JBColor(Color(89, 136, 208), Color(143, 182, 232))
        private val ICON_BUTTON_BG_DISABLED = JBColor(Color(247, 250, 254), Color(66, 72, 83))
        private val ICON_BUTTON_BORDER_DISABLED = JBColor(Color(198, 205, 216), Color(96, 106, 121))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val DETAIL_COLUMN_BG = JBColor(Color(244, 249, 255), Color(56, 62, 72))
        private val LIST_SECTION_BG = JBColor(Color(242, 248, 255), Color(59, 66, 77))
        private val LIST_SECTION_BORDER = JBColor(Color(198, 212, 234), Color(89, 100, 117))
        private val PHASE_SECTION_BG = JBColor(Color(240, 246, 255), Color(62, 69, 80))
        private val DETAIL_SECTION_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val DETAIL_SECTION_BORDER = JBColor(Color(204, 217, 236), Color(84, 94, 109))
        private const val WORKSPACE_SECTION_CARD_PADDING = 12
        private val WORKFLOW_ICON_CREATE_WORKTREE = IconLoader.getIcon("/icons/spec-workflow-create-worktree.svg", SpecWorkflowPanel::class.java)
        private val WORKFLOW_ICON_MERGE_WORKTREE = IconLoader.getIcon("/icons/spec-workflow-merge-worktree.svg", SpecWorkflowPanel::class.java)
        private val WORKFLOW_ICON_DELTA = IconLoader.getIcon("/icons/spec-workflow-diff.svg", SpecWorkflowPanel::class.java)
        private val WORKFLOW_ICON_GRAPH = IconLoader.getIcon("/icons/spec-workflow-graphql-tool-window.svg", SpecWorkflowPanel::class.java)
        private val WORKFLOW_ICON_ARCHIVE = IconLoader.getIcon("/icons/spec-workflow-archive.svg", SpecWorkflowPanel::class.java)
        private val PLACEHOLDER_ERROR_MESSAGES = setOf("-", "--", "—", "...", "…", "null", "none", "unknown")
        private val PLACEHOLDER_SYMBOLS_REGEX = Regex("""^[\p{Punct}\s]+$""")
        private val ERROR_TEXT_CONTENT_REGEX = Regex("""[A-Za-z0-9\p{IsHan}]""")
        private const val DOCUMENT_RELOAD_DEBOUNCE_MILLIS = 300L
        private const val WORKSPACE_CARD_EMPTY = "empty"
        private const val WORKSPACE_CARD_CONTENT = "content"
        private val SPEC_DOCUMENT_FILE_NAMES = (SpecPhase.entries
            .map { it.outputFileName } + listOfNotNull(StageId.VERIFY.artifactFileName))
            .toSet()
    }
}
