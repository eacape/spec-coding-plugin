package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphRenderer
import com.eacape.speccodingplugin.context.CodeGraphService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.spec.*
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.ui.worktree.NewWorktreeDialog
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants

class SpecWorkflowPanel(
    private val project: Project
) : JBPanel<SpecWorkflowPanel>(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val specEngine = SpecEngine.getInstance(project)
    private val specDeltaService = SpecDeltaService.getInstance(project)
    private val codeGraphService = CodeGraphService.getInstance(project)
    private val worktreeManager = WorktreeManager.getInstance(project)
    private val llmRouter = LlmRouter.getInstance()
    private val modelRegistry = ModelRegistry.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var _isDisposed = false

    private val phaseIndicator = SpecPhaseIndicatorPanel()
    private val listPanel: SpecWorkflowListPanel
    private val detailPanel: SpecDetailPanel
    private val statusLabel = JBLabel("")
    private val statusChipPanel = JPanel(BorderLayout())
    private val modelLabel = JBLabel(SpecCodingBundle.message("toolwindow.model.label"))
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private val createWorktreeButton = JButton(SpecCodingBundle.message("spec.workflow.createWorktree.short"))
    private val mergeWorktreeButton = JButton(SpecCodingBundle.message("spec.workflow.mergeWorktree.short"))
    private val deltaButton = JButton(SpecCodingBundle.message("spec.workflow.delta.short"))
    private val codeGraphButton = JButton(SpecCodingBundle.message("spec.workflow.codeGraph.short"))
    private val archiveButton = JButton(SpecCodingBundle.message("spec.workflow.archive.short"))
    private val refreshButton = JButton(SpecCodingBundle.message("spec.workflow.refresh.short"))

    private var selectedWorkflowId: String? = null
    private var currentWorkflow: SpecWorkflow? = null

    init {
        border = JBUI.Borders.empty(8)

        listPanel = SpecWorkflowListPanel(
            onWorkflowSelected = ::onWorkflowSelectedByUser,
            onCreateWorkflow = ::onCreateWorkflow,
            onEditWorkflow = ::onEditWorkflow,
            onDeleteWorkflow = ::onDeleteWorkflow
        )

        detailPanel = SpecDetailPanel(
            onGenerate = ::onGenerate,
            onNextPhase = ::onNextPhase,
            onGoBack = ::onGoBack,
            onComplete = ::onComplete,
            onPauseResume = ::onPauseResume,
            onOpenInEditor = ::onOpenInEditor,
            onShowHistoryDiff = ::onShowHistoryDiff,
            onSaveDocument = ::onSaveDocument,
        )

        setupUI()
        subscribeToLocaleEvents()
        subscribeToWorkflowEvents()
        refreshWorkflows()
    }

    private fun setupUI() {
        refreshButton.addActionListener { refreshWorkflows() }
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        deltaButton.isEnabled = false
        codeGraphButton.isEnabled = true
        archiveButton.isEnabled = false
        createWorktreeButton.addActionListener { onCreateWorktree() }
        mergeWorktreeButton.addActionListener { onMergeWorktree() }
        deltaButton.addActionListener { onShowDelta() }
        codeGraphButton.addActionListener { onShowCodeGraph() }
        archiveButton.addActionListener { onArchiveWorkflow() }

        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG
        setupGenerationControls()

        val modelRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(JBUI.scale(6))
            add(providerComboBox)
            add(modelLabel)
            add(modelComboBox)
        }
        val modelHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(
                JBScrollPane(modelRow).apply {
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
        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(JBUI.scale(6))
            add(refreshButton)
            add(createWorktreeButton)
            add(mergeWorktreeButton)
            add(deltaButton)
            add(codeGraphButton)
            add(archiveButton)
        }
        val actionsHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(
                JBScrollPane(actionsRow).apply {
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
            top = 4,
            left = 10,
            bottom = 4,
            right = 10,
        )
        statusChipPanel.removeAll()
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)
        val toolbarCard = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(14),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(modelHost, BorderLayout.NORTH)
            add(actionsHost, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(statusChipPanel, BorderLayout.WEST)
                },
                BorderLayout.SOUTH,
            )
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        val rightPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(createSectionContainer(phaseIndicator, padding = 4), BorderLayout.NORTH)
            add(createSectionContainer(detailPanel), BorderLayout.CENTER)
        }

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            createSectionContainer(listPanel),
            rightPanel,
        ).apply {
            dividerLocation = 210
            resizeWeight = 0.26
            dividerSize = JBUI.scale(6)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_SECTION_BG
        }
        split.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    clampDividerLocation(split)
                }
            },
        )
        add(split, BorderLayout.CENTER)
        clampDividerLocation(split)
        setStatusText(null)
    }

    private fun clampDividerLocation(split: JSplitPane) {
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
    }

    private fun styleToolbarButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(1, 5, 1, 5),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val lafWidth = button.preferredSize?.width ?: 0
        val width = maxOf(
            lafWidth,
            textWidth + insets.left + insets.right + JBUI.scale(10),
            JBUI.scale(40),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
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
        configureToolbarCombo(providerComboBox, preferredWidth = 114)
        configureToolbarCombo(modelComboBox, preferredWidth = 158)
        refreshProviderCombo(preserveSelection = false)
    }

    private fun configureToolbarCombo(comboBox: ComboBox<*>, preferredWidth: Int) {
        val width = JBUI.scale(preferredWidth)
        val height = JBUI.scale(28)
        comboBox.preferredSize = JBUI.size(width, height)
        comboBox.minimumSize = JBUI.size(JBUI.scale(92), height)
        comboBox.maximumSize = JBUI.size(JBUI.scale(260), height)
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

    private fun createSectionContainer(content: Component, padding: Int = 2): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_SECTION_BORDER,
                arc = JBUI.scale(14),
                top = padding,
                left = padding,
                bottom = padding,
                right = padding,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    fun refreshWorkflows(selectWorkflowId: String? = null) {
        scope.launch(Dispatchers.IO) {
            val ids = specEngine.listWorkflows()
            val items = ids.mapNotNull { id ->
                specEngine.loadWorkflow(id).getOrNull()?.let { wf ->
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = wf.id,
                        title = wf.title.ifBlank { wf.id },
                        description = wf.description,
                        currentPhase = wf.currentPhase,
                        status = wf.status,
                        updatedAt = wf.updatedAt
                    )
                }
            }
            invokeLaterSafe {
                listPanel.updateWorkflows(items)
                setStatusText(null)
                val targetSelection = selectWorkflowId
                    ?: selectedWorkflowId?.takeIf { target -> items.any { it.workflowId == target } }
                if (targetSelection != null) {
                    listPanel.setSelectedWorkflow(targetSelection)
                    selectWorkflow(targetSelection)
                } else if (items.isEmpty()) {
                    selectedWorkflowId = null
                    currentWorkflow = null
                    phaseIndicator.reset()
                    detailPanel.showEmpty()
                    createWorktreeButton.isEnabled = false
                    mergeWorktreeButton.isEnabled = false
                    deltaButton.isEnabled = false
                    archiveButton.isEnabled = false
                }
            }
        }
    }

    private fun onWorkflowSelectedByUser(workflowId: String) {
        selectWorkflow(workflowId)
        publishWorkflowSelection(workflowId)
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
        selectedWorkflowId = workflowId
        scope.launch(Dispatchers.IO) {
            val wf = specEngine.loadWorkflow(workflowId).getOrNull()
            currentWorkflow = wf
            invokeLaterSafe {
                if (wf != null) {
                    phaseIndicator.updatePhase(wf)
                    detailPanel.updateWorkflow(wf)
                    createWorktreeButton.isEnabled = true
                    mergeWorktreeButton.isEnabled = true
                    deltaButton.isEnabled = true
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                } else {
                    phaseIndicator.reset()
                    detailPanel.showEmpty()
                    createWorktreeButton.isEnabled = false
                    mergeWorktreeButton.isEnabled = false
                    deltaButton.isEnabled = false
                    archiveButton.isEnabled = false
                }
            }
        }
    }

    private fun onCreateWorkflow() {
        val dialog = NewSpecWorkflowDialog()
        if (dialog.showAndGet()) {
            val title = dialog.resultTitle ?: return
            val desc = dialog.resultDescription ?: ""
            scope.launch(Dispatchers.IO) {
                specEngine.createWorkflow(title, desc).onSuccess { wf ->
                    invokeLaterSafe {
                        refreshWorkflows()
                        selectWorkflow(wf.id)
                        listPanel.setSelectedWorkflow(wf.id)
                    }
                }.onFailure { e ->
                    logger.warn("Failed to create workflow", e)
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
                            if (selectedWorkflowId == workflowId) {
                                currentWorkflow = updated
                                phaseIndicator.updatePhase(updated)
                                detailPanel.updateWorkflow(updated)
                                archiveButton.isEnabled = updated.status == WorkflowStatus.COMPLETED
                            }
                            refreshWorkflows(selectWorkflowId = workflowId)
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
                    selectedWorkflowId = null
                    currentWorkflow = null
                    phaseIndicator.reset()
                    detailPanel.showEmpty()
                    createWorktreeButton.isEnabled = false
                    mergeWorktreeButton.isEnabled = false
                    deltaButton.isEnabled = false
                    archiveButton.isEnabled = false
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

    private fun onGenerate(input: String) {
        val wfId = selectedWorkflowId ?: return
        val providerId = providerComboBox.selectedItem as? String
        if (providerId.isNullOrBlank()) {
            setStatusText(SpecCodingBundle.message("spec.workflow.generation.providerRequired"))
            return
        }

        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        if (modelId.isBlank()) {
            setStatusText(SpecCodingBundle.message("spec.workflow.generation.modelRequired", providerDisplayName(providerId)))
            return
        }

        val options = GenerationOptions(
            providerId = providerId,
            model = modelId,
        )
        scope.launch(Dispatchers.IO) {
            specEngine.generateCurrentPhase(wfId, input, options).collect { progress ->
                invokeLaterSafe {
                    when (progress) {
                        is SpecGenerationProgress.Started ->
                            detailPanel.showGenerating(0.0)
                        is SpecGenerationProgress.Generating ->
                            detailPanel.showGenerating(progress.progress)
                        is SpecGenerationProgress.Completed -> {
                            reloadCurrentWorkflow()
                        }
                        is SpecGenerationProgress.ValidationFailed -> {
                            reloadCurrentWorkflow()
                        }
                        is SpecGenerationProgress.Failed -> {
                            detailPanel.showGenerationFailed()
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", progress.error))
                        }
                    }
                }
            }
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
                            ).show()
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

        scope.launch(Dispatchers.IO) {
            val result = specEngine.archiveWorkflow(workflow.id)
            invokeLaterSafe {
                result.onSuccess {
                    if (selectedWorkflowId == workflow.id) {
                        selectedWorkflowId = null
                        currentWorkflow = null
                        phaseIndicator.reset()
                        detailPanel.showEmpty()
                        createWorktreeButton.isEnabled = false
                        mergeWorktreeButton.isEnabled = false
                        deltaButton.isEnabled = false
                        archiveButton.isEnabled = false
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
                invokeLaterSafe { reloadCurrentWorkflow() }
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
                invokeLaterSafe { reloadCurrentWorkflow() }
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
        project.messageBus.connect(this).subscribe(
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

    private fun refreshLocalizedTexts() {
        listPanel.refreshLocalizedTexts()
        detailPanel.refreshLocalizedTexts()
        refreshButton.text = SpecCodingBundle.message("spec.workflow.refresh.short")
        createWorktreeButton.text = SpecCodingBundle.message("spec.workflow.createWorktree.short")
        mergeWorktreeButton.text = SpecCodingBundle.message("spec.workflow.mergeWorktree.short")
        deltaButton.text = SpecCodingBundle.message("spec.workflow.delta.short")
        codeGraphButton.text = SpecCodingBundle.message("spec.workflow.codeGraph.short")
        archiveButton.text = SpecCodingBundle.message("spec.workflow.archive.short")
        modelLabel.text = SpecCodingBundle.message("toolwindow.model.label")
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)
        refreshProviderCombo(preserveSelection = true)
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

    private fun reloadCurrentWorkflow() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            val wf = specEngine.loadWorkflow(wfId).getOrNull()
            currentWorkflow = wf
            invokeLaterSafe {
                if (wf != null) {
                    phaseIndicator.updatePhase(wf)
                    detailPanel.updateWorkflow(wf)
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                } else {
                    archiveButton.isEnabled = false
                }
            }
        }
    }

    private fun compactErrorMessage(error: Throwable?, fallback: String, maxLength: Int = 220): String {
        val compact = error?.message
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.ifBlank { null }
            ?: fallback
        if (compact.length <= maxLength) {
            return compact
        }
        return compact.take(maxLength - 1).trimEnd() + "…"
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!_isDisposed && !project.isDisposed) action()
        }
    }

    override fun dispose() {
        _isDisposed = true
        scope.cancel()
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
    }
}
