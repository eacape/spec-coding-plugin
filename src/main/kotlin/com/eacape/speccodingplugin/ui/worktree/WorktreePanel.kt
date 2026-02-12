package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeMergeResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane

class WorktreePanel(
    private val project: Project,
    private val listWorkflows: () -> List<String> = {
        SpecEngine.getInstance(project).listWorkflows()
    },
    private val loadWorkflow: (workflowId: String) -> Result<SpecWorkflow> = { workflowId ->
        SpecEngine.getInstance(project).loadWorkflow(workflowId)
    },
    private val getActiveWorktree: () -> WorktreeBinding? = {
        WorktreeManager.getInstance(project).getActiveWorktree()
    },
    private val listBindings: () -> List<WorktreeBinding> = {
        WorktreeManager.getInstance(project).listBindings()
    },
    private val switchWorktreeAction: (worktreeId: String) -> Result<WorktreeBinding> = { worktreeId ->
        WorktreeManager.getInstance(project).switchWorktree(worktreeId)
    },
    private val mergeWorktreeAction: (worktreeId: String, targetBranch: String) -> Result<WorktreeMergeResult> = { worktreeId, targetBranch ->
        WorktreeManager.getInstance(project).mergeWorktree(worktreeId, targetBranch)
    },
    private val cleanupWorktreeAction: (worktreeId: String, force: Boolean) -> Result<Unit> = { worktreeId, force ->
        WorktreeManager.getInstance(project).cleanupWorktree(worktreeId, force)
    },
    private val createAndOpenWorktreeAction: (
        specTaskId: String,
        shortName: String,
        baseBranch: String,
    ) -> Result<WorktreeBinding> = { specTaskId, shortName, baseBranch ->
        WorktreeManager.getInstance(project).createAndOpenWorktree(specTaskId, shortName, baseBranch)
    },
    private val newWorktreeDialogFactory: () -> NewWorktreeDialog = { NewWorktreeDialog() },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val listPanel = WorktreeListPanel(
        onWorktreeSelected = ::onWorktreeSelected,
        onCreateWorktree = ::onCreateWorktree,
        onSwitchWorktree = ::onSwitchWorktree,
        onMergeWorktree = ::onMergeWorktree,
        onCleanupWorktree = ::onCleanupWorktree,
    )
    private val detailPanel = WorktreeDetailPanel()

    private val refreshButton = JButton(SpecCodingBundle.message("worktree.action.refresh"))

    private var selectedWorktreeId: String? = null
    private var currentItems: List<WorktreeListItem> = emptyList()

    init {
        border = JBUI.Borders.empty(4)
        setupUI()
        subscribeToLocaleEvents()
        refreshWorktrees()
    }

    private fun setupUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
        }
        refreshButton.addActionListener { refreshWorktrees() }
        toolbar.add(refreshButton)
        add(toolbar, BorderLayout.NORTH)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detailPanel).apply {
            dividerLocation = 260
            dividerSize = JBUI.scale(4)
        }
        add(splitPane, BorderLayout.CENTER)
    }

    fun refreshWorktrees() {
        runBackground {
            val workflowsById = listWorkflows()
                .associateWith { id -> loadWorkflow(id).getOrNull() }

            val activeId = getActiveWorktree()?.id
            val items = listBindings()
                .map { binding ->
                    val workflow = workflowsById[binding.specTaskId]
                    WorktreeListItem(
                        id = binding.id,
                        specTaskId = binding.specTaskId,
                        specTitle = workflow?.title?.ifBlank { binding.specTaskId } ?: binding.specTaskId,
                        branchName = binding.branchName,
                        worktreePath = binding.worktreePath,
                        baseBranch = binding.baseBranch,
                        status = binding.status,
                        isActive = binding.id == activeId,
                        updatedAt = binding.updatedAt,
                        lastError = binding.lastError,
                    )
                }

            invokeLaterSafe {
                currentItems = items
                listPanel.updateWorktrees(items)

                val selected = selectedWorktreeId?.let { id -> items.firstOrNull { it.id == id } }
                    ?: items.firstOrNull()
                selectedWorktreeId = selected?.id
                listPanel.setSelectedWorktree(selected?.id)
                if (selected != null) {
                    detailPanel.updateWorktree(selected)
                } else {
                    detailPanel.showEmpty()
                }
            }
        }
    }

    private fun onWorktreeSelected(worktreeId: String) {
        selectedWorktreeId = worktreeId
        currentItems.firstOrNull { it.id == worktreeId }?.let(detailPanel::updateWorktree)
    }

    private fun onCreateWorktree() {
        val dialog = newWorktreeDialogFactory()
        if (!dialog.showAndGet()) {
            return
        }

        val specTaskId = dialog.resultSpecTaskId ?: return
        val shortName = dialog.resultShortName ?: return
        val baseBranch = dialog.resultBaseBranch ?: return

        runBackground {
            createAndOpenWorktreeAction(specTaskId, shortName, baseBranch)
                .onFailure { error -> logger.warn("Failed to create worktree", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onSwitchWorktree(worktreeId: String) {
        runBackground {
            switchWorktreeAction(worktreeId)
                .onFailure { error -> logger.warn("Failed to switch worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onMergeWorktree(worktreeId: String) {
        val targetBranch = currentItems.firstOrNull { it.id == worktreeId }?.baseBranch ?: NewWorktreeDialog.DEFAULT_BASE_BRANCH
        runBackground {
            mergeWorktreeAction(worktreeId, targetBranch)
                .onFailure { error -> logger.warn("Failed to merge worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onCleanupWorktree(worktreeId: String) {
        runBackground {
            cleanupWorktreeAction(worktreeId, true)
                .onFailure { error -> logger.warn("Failed to cleanup worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (runSynchronously) {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
            return
        }

        if (isDisposed) return
        invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    private fun runBackground(task: () -> Unit) {
        if (runSynchronously) {
            task()
            return
        }
        scope.launch(Dispatchers.IO) { task() }
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

    private fun refreshLocalizedTexts() {
        refreshButton.text = SpecCodingBundle.message("worktree.action.refresh")
        listPanel.refreshLocalizedTexts()
        detailPanel.refreshLocalizedTexts()
    }

    internal fun selectedWorktreeIdForTest(): String? = selectedWorktreeId

    internal fun itemsForTest(): List<WorktreeListItem> = currentItems

    internal fun listPanelButtonStatesForTest(): Map<String, Boolean> = listPanel.buttonStatesForTest()

    internal fun detailStatusTextForTest(): String = detailPanel.displayedStatusForTest()

    internal fun detailSpecTaskIdTextForTest(): String = detailPanel.displayedSpecTaskIdForTest()

    internal fun isDetailEmptyForTest(): Boolean = detailPanel.isShowingEmptyForTest()

    internal fun setSelectedWorktreeForTest(worktreeId: String?) {
        selectedWorktreeId = worktreeId
        listPanel.setSelectedWorktree(worktreeId)
        if (worktreeId == null) {
            detailPanel.showEmpty()
            return
        }
        currentItems.firstOrNull { it.id == worktreeId }
            ?.let(detailPanel::updateWorktree)
            ?: detailPanel.showEmpty()
    }

    internal fun clickSwitchForTest() {
        listPanel.clickSwitchForTest()
    }

    internal fun clickMergeForTest() {
        listPanel.clickMergeForTest()
    }

    internal fun clickCleanupForTest() {
        listPanel.clickCleanupForTest()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }
}
