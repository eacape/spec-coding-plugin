package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphRenderer
import com.eacape.speccodingplugin.context.CodeGraphService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.spec.*
import com.eacape.speccodingplugin.ui.worktree.NewWorktreeDialog
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane

class SpecWorkflowPanel(
    private val project: Project
) : JBPanel<SpecWorkflowPanel>(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val specEngine = SpecEngine.getInstance(project)
    private val specDeltaService = SpecDeltaService.getInstance(project)
    private val codeGraphService = CodeGraphService.getInstance(project)
    private val worktreeManager = WorktreeManager.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var _isDisposed = false

    private val phaseIndicator = SpecPhaseIndicatorPanel()
    private val listPanel: SpecWorkflowListPanel
    private val detailPanel: SpecDetailPanel
    private val statusLabel = JBLabel("")
    private val createWorktreeButton = JButton(SpecCodingBundle.message("spec.workflow.createWorktree"))
    private val mergeWorktreeButton = JButton(SpecCodingBundle.message("spec.workflow.mergeWorktree"))
    private val deltaButton = JButton(SpecCodingBundle.message("spec.workflow.delta"))
    private val codeGraphButton = JButton(SpecCodingBundle.message("spec.workflow.codeGraph"))
    private val archiveButton = JButton(SpecCodingBundle.message("spec.workflow.archive"))
    private val refreshButton = JButton(SpecCodingBundle.message("spec.workflow.refresh"))

    private var selectedWorkflowId: String? = null
    private var currentWorkflow: SpecWorkflow? = null

    init {
        border = JBUI.Borders.empty(8)

        listPanel = SpecWorkflowListPanel(
            onWorkflowSelected = ::selectWorkflow,
            onCreateWorkflow = ::onCreateWorkflow,
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
        )

        setupUI()
        subscribeToLocaleEvents()
        subscribeToWorkflowEvents()
        refreshWorkflows()
    }

    private fun setupUI() {
        // 工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.emptyBottom(8)
        refreshButton.addActionListener { refreshWorkflows() }
        toolbar.add(refreshButton)
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
        toolbar.add(createWorktreeButton)
        toolbar.add(mergeWorktreeButton)
        toolbar.add(deltaButton)
        toolbar.add(codeGraphButton)
        toolbar.add(archiveButton)
        toolbar.add(statusLabel)
        add(toolbar, BorderLayout.NORTH)

        // 右侧面板（指示器 + 详情）
        val rightPanel = JPanel(BorderLayout())
        rightPanel.add(phaseIndicator, BorderLayout.NORTH)
        rightPanel.add(detailPanel, BorderLayout.CENTER)

        // 左右分割
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, rightPanel)
        split.dividerLocation = 200
        split.dividerSize = JBUI.scale(4)
        add(split, BorderLayout.CENTER)
    }

    fun refreshWorkflows(selectWorkflowId: String? = null) {
        scope.launch(Dispatchers.IO) {
            val ids = specEngine.listWorkflows()
            val items = ids.mapNotNull { id ->
                specEngine.loadWorkflow(id).getOrNull()?.let { wf ->
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = wf.id,
                        title = wf.title.ifBlank { wf.id },
                        currentPhase = wf.currentPhase,
                        status = wf.status,
                        updatedAt = wf.updatedAt
                    )
                }
            }
            invokeLaterSafe {
                listPanel.updateWorkflows(items)
                statusLabel.text = SpecCodingBundle.message("spec.workflow.status.count", items.size)
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
            statusLabel.text = SpecCodingBundle.message("spec.workflow.worktree.selectFirst")
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
                        statusLabel.text = SpecCodingBundle.message("spec.workflow.worktree.created", binding.branchName)
                    } else {
                        val message = switched.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown")
                        statusLabel.text = SpecCodingBundle.message("spec.workflow.worktree.switchFailed", message)
                    }
                }
            }.onFailure { error ->
                invokeLaterSafe {
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.workflow.worktree.createFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onMergeWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            statusLabel.text = SpecCodingBundle.message("spec.workflow.worktree.selectFirst")
            return
        }

        scope.launch(Dispatchers.IO) {
            val binding = worktreeManager.listBindings(includeInactive = true)
                .firstOrNull { it.specTaskId == workflow.id && it.status == WorktreeStatus.ACTIVE }
                ?: worktreeManager.listBindings(includeInactive = true)
                    .firstOrNull { it.specTaskId == workflow.id }

            if (binding == null) {
                invokeLaterSafe {
                    statusLabel.text = SpecCodingBundle.message("spec.workflow.worktree.noBinding")
                }
                return@launch
            }

            val mergeResult = worktreeManager.mergeWorktree(binding.id, binding.baseBranch)
            invokeLaterSafe {
                mergeResult.onSuccess {
                    statusLabel.text = SpecCodingBundle.message("spec.workflow.worktree.merged", it.targetBranch)
                }.onFailure { error ->
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.workflow.worktree.mergeFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onGenerate(input: String) {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.generateCurrentPhase(wfId, input).collect { progress ->
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
                        is SpecGenerationProgress.Failed ->
                            statusLabel.text = SpecCodingBundle.message("spec.workflow.error", progress.error)
                    }
                }
            }
        }
    }

    private fun onShowDelta() {
        val targetWorkflow = currentWorkflow
        if (targetWorkflow == null) {
            statusLabel.text = SpecCodingBundle.message("spec.delta.error.noCurrentWorkflow")
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
                    statusLabel.text = SpecCodingBundle.message("spec.delta.emptyCandidates")
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
                    statusLabel.text = SpecCodingBundle.message("spec.delta.selectBaseline.required")
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
                            statusLabel.text = SpecCodingBundle.message("spec.delta.generated")
                        }.onFailure { error ->
                            statusLabel.text = SpecCodingBundle.message(
                                "spec.workflow.error",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onArchiveWorkflow() {
        val workflow = currentWorkflow
        if (workflow == null) {
            statusLabel.text = SpecCodingBundle.message("spec.workflow.archive.selectFirst")
            return
        }
        if (workflow.status != WorkflowStatus.COMPLETED) {
            statusLabel.text = SpecCodingBundle.message("spec.workflow.archive.onlyCompleted")
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
                    statusLabel.text = SpecCodingBundle.message("spec.workflow.archive.done", workflow.id)
                }.onFailure { error ->
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.workflow.archive.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onShowCodeGraph() {
        statusLabel.text = SpecCodingBundle.message("code.graph.status.generating")
        scope.launch(Dispatchers.Default) {
            val result = codeGraphService.buildFromActiveEditor()
            invokeLaterSafe {
                result.onSuccess { snapshot ->
                    if (snapshot.edges.isEmpty()) {
                        statusLabel.text = SpecCodingBundle.message("code.graph.status.empty")
                        return@onSuccess
                    }

                    val summary = CodeGraphRenderer.renderSummary(snapshot)
                    val mermaid = CodeGraphRenderer.renderMermaid(snapshot)
                    CodeGraphDialog(summary = summary, mermaid = mermaid).show()
                    statusLabel.text = SpecCodingBundle.message(
                        "code.graph.status.generated",
                        snapshot.nodes.size,
                        snapshot.edges.size,
                    )
                }.onFailure { error ->
                    statusLabel.text = SpecCodingBundle.message(
                        "code.graph.status.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
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
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    )
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
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    )
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
                    refreshWorkflows(selectWorkflowId = event.workflowId)
                }
            },
        )
    }

    private fun refreshLocalizedTexts() {
        refreshButton.text = SpecCodingBundle.message("spec.workflow.refresh")
        createWorktreeButton.text = SpecCodingBundle.message("spec.workflow.createWorktree")
        mergeWorktreeButton.text = SpecCodingBundle.message("spec.workflow.mergeWorktree")
        deltaButton.text = SpecCodingBundle.message("spec.workflow.delta")
        codeGraphButton.text = SpecCodingBundle.message("spec.workflow.codeGraph")
        archiveButton.text = SpecCodingBundle.message("spec.workflow.archive")
        statusLabel.text = SpecCodingBundle.message("spec.workflow.status.ready")
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

    private fun onShowHistoryDiffForWorkflow(
        workflowId: String,
        phase: SpecPhase,
        currentDoc: SpecDocument?,
    ) {
        if (currentDoc == null) {
            statusLabel.text = SpecCodingBundle.message("spec.history.noCurrentDocument")
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
                    statusLabel.text = SpecCodingBundle.message("spec.history.noSnapshot")
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

                statusLabel.text = SpecCodingBundle.message(
                    "spec.history.diff.opened",
                    phase.displayName,
                    snapshots.size,
                )
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

    private fun invokeLaterSafe(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!_isDisposed && !project.isDisposed) action()
        }
    }

    override fun dispose() {
        _isDisposed = true
        scope.cancel()
    }
}
