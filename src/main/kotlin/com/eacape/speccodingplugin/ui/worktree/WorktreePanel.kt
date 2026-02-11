package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.worktree.WorktreeManager
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
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val worktreeManager = WorktreeManager.getInstance(project)
    private val specEngine = SpecEngine.getInstance(project)

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
        scope.launch(Dispatchers.IO) {
            val workflowsById = specEngine.listWorkflows()
                .associateWith { id -> specEngine.loadWorkflow(id).getOrNull() }

            val activeId = worktreeManager.getActiveWorktree()?.id
            val items = worktreeManager.listBindings()
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
        val dialog = NewWorktreeDialog()
        if (!dialog.showAndGet()) {
            return
        }

        val specTaskId = dialog.resultSpecTaskId ?: return
        val shortName = dialog.resultShortName ?: return
        val baseBranch = dialog.resultBaseBranch ?: return

        scope.launch(Dispatchers.IO) {
            worktreeManager.createAndOpenWorktree(specTaskId, shortName, baseBranch)
                .onFailure { error -> logger.warn("Failed to create worktree", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onSwitchWorktree(worktreeId: String) {
        scope.launch(Dispatchers.IO) {
            worktreeManager.switchWorktree(worktreeId)
                .onFailure { error -> logger.warn("Failed to switch worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onMergeWorktree(worktreeId: String) {
        val targetBranch = currentItems.firstOrNull { it.id == worktreeId }?.baseBranch ?: "main"
        scope.launch(Dispatchers.IO) {
            worktreeManager.mergeWorktree(worktreeId, targetBranch)
                .onFailure { error -> logger.warn("Failed to merge worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onCleanupWorktree(worktreeId: String) {
        scope.launch(Dispatchers.IO) {
            worktreeManager.cleanupWorktree(worktreeId, force = true)
                .onFailure { error -> logger.warn("Failed to cleanup worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (isDisposed) return
        invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }
}
