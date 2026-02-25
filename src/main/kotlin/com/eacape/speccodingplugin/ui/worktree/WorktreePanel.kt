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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
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

    private val titleLabel = JBLabel(SpecCodingBundle.message("worktree.panel.title"))
    private val statusLabel = JBLabel(SpecCodingBundle.message("worktree.status.count", 0))

    private var selectedWorktreeId: String? = null
    private var currentItems: List<WorktreeListItem> = emptyList()

    init {
        border = JBUI.Borders.empty(8)
        setupUI()
        subscribeToLocaleEvents()
        refreshWorktrees()
    }

    private fun setupUI() {
        add(buildHeader(), BorderLayout.NORTH)

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            createSectionContainer(listPanel),
            createSectionContainer(detailPanel),
        ).apply {
            dividerLocation = 320
            resizeWeight = 0.38
            dividerSize = JBUI.scale(6)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_SECTION_BG
        }
        add(splitPane, BorderLayout.CENTER)
    }

    private fun buildHeader(): JPanel {
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG

        val statusChip = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = STATUS_CHIP_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(STATUS_CHIP_BORDER, 1),
                JBUI.Borders.empty(3, 8),
            )
            add(statusLabel, BorderLayout.CENTER)
        }

        val headerCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = HEADER_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(HEADER_BORDER, 1),
                JBUI.Borders.empty(8, 10),
            )
            add(titleLabel, BorderLayout.WEST)
            add(statusChip, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            add(headerCard, BorderLayout.CENTER)
        }
    }

    private fun createSectionContainer(content: Component): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PANEL_SECTION_BORDER, 1),
                JBUI.Borders.empty(2),
            )
            add(content, BorderLayout.CENTER)
        }
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
                statusLabel.text = SpecCodingBundle.message("worktree.status.count", items.size)

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
        titleLabel.text = SpecCodingBundle.message("worktree.panel.title")
        statusLabel.text = SpecCodingBundle.message("worktree.status.count", currentItems.size)
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

    companion object {
        private val HEADER_BG = JBColor(Color(248, 250, 253), Color(58, 63, 71))
        private val HEADER_BORDER = JBColor(Color(214, 222, 236), Color(82, 90, 102))
        private val STATUS_CHIP_BG = JBColor(Color(239, 245, 253), Color(64, 74, 88))
        private val STATUS_CHIP_BORDER = JBColor(Color(186, 201, 224), Color(96, 111, 131))
        private val STATUS_TEXT_FG = JBColor(Color(60, 76, 100), Color(194, 207, 225))
        private val PANEL_SECTION_BG = JBColor(Color(251, 252, 254), Color(50, 54, 61))
        private val PANEL_SECTION_BORDER = JBColor(Color(211, 218, 232), Color(79, 85, 96))
    }
}
