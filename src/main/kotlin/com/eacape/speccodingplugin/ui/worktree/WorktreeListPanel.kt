package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

class WorktreeListPanel(
    private val onWorktreeSelected: (String) -> Unit,
    private val onCreateWorktree: () -> Unit,
    private val onSwitchWorktree: (String) -> Unit,
    private val onMergeWorktree: (String) -> Unit,
    private val onCleanupWorktree: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<WorktreeListItem>()
    private val worktreeList = JBList(listModel)
    private val createButton = JButton(SpecCodingBundle.message("worktree.action.create"))
    private val switchButton = JButton(SpecCodingBundle.message("worktree.action.switch"))
    private val mergeButton = JButton(SpecCodingBundle.message("worktree.action.merge"))
    private val cleanupButton = JButton(SpecCodingBundle.message("worktree.action.cleanup"))

    init {
        border = JBUI.Borders.empty(4)
        setupUI()
    }

    private fun setupUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
        }

        createButton.addActionListener { onCreateWorktree() }
        switchButton.addActionListener { selectedId()?.let(onSwitchWorktree) }
        mergeButton.addActionListener { selectedId()?.let(onMergeWorktree) }
        cleanupButton.addActionListener { selectedId()?.let(onCleanupWorktree) }

        toolbar.add(createButton)
        toolbar.add(switchButton)
        toolbar.add(mergeButton)
        toolbar.add(cleanupButton)
        add(toolbar, BorderLayout.NORTH)

        worktreeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        worktreeList.cellRenderer = WorktreeCellRenderer()
        worktreeList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateButtonStates(worktreeList.selectedValue)
                worktreeList.selectedValue?.let { item -> onWorktreeSelected(item.id) }
            }
        }

        updateButtonStates(null)
        add(JBScrollPane(worktreeList), BorderLayout.CENTER)
    }

    fun updateWorktrees(items: List<WorktreeListItem>) {
        val selectedId = selectedId()
        listModel.clear()
        items.forEach(listModel::addElement)

        if (selectedId != null) {
            setSelectedWorktree(selectedId)
        }
        if (worktreeList.selectedValue == null) {
            updateButtonStates(null)
        }
    }

    fun refreshLocalizedTexts() {
        createButton.text = SpecCodingBundle.message("worktree.action.create")
        switchButton.text = SpecCodingBundle.message("worktree.action.switch")
        mergeButton.text = SpecCodingBundle.message("worktree.action.merge")
        cleanupButton.text = SpecCodingBundle.message("worktree.action.cleanup")
        worktreeList.repaint()
    }

    fun setSelectedWorktree(worktreeId: String?) {
        if (worktreeId == null) {
            worktreeList.clearSelection()
            updateButtonStates(null)
            return
        }

        for (i in 0 until listModel.size()) {
            if (listModel[i].id == worktreeId) {
                worktreeList.selectedIndex = i
                updateButtonStates(listModel[i])
                return
            }
        }
    }

    internal fun itemsForTest(): List<WorktreeListItem> = (0 until listModel.size()).map { listModel[it] }

    internal fun selectedWorktreeIdForTest(): String? = selectedId()

    internal fun buttonStatesForTest(): Map<String, Boolean> {
        return mapOf(
            "createEnabled" to createButton.isEnabled,
            "switchEnabled" to switchButton.isEnabled,
            "mergeEnabled" to mergeButton.isEnabled,
            "cleanupEnabled" to cleanupButton.isEnabled,
        )
    }

    internal fun clickCreateForTest() {
        createButton.doClick()
    }

    internal fun clickSwitchForTest() {
        switchButton.doClick()
    }

    internal fun clickMergeForTest() {
        mergeButton.doClick()
    }

    internal fun clickCleanupForTest() {
        cleanupButton.doClick()
    }

    private fun selectedId(): String? = worktreeList.selectedValue?.id

    private fun updateButtonStates(selected: WorktreeListItem?) {
        val hasSelection = selected != null
        val isActive = selected?.status == WorktreeStatus.ACTIVE
        switchButton.isEnabled = hasSelection && isActive
        mergeButton.isEnabled = hasSelection && isActive
        cleanupButton.isEnabled = hasSelection && selected.status != WorktreeStatus.REMOVED
    }

    private class WorktreeCellRenderer : ListCellRenderer<WorktreeListItem> {
        private val panel = JPanel(BorderLayout())
        private val titleLabel = JBLabel()
        private val detailLabel = JBLabel()

        init {
            panel.border = JBUI.Borders.empty(6, 8)
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, 11f)
            detailLabel.foreground = JBColor.GRAY
            panel.add(titleLabel, BorderLayout.NORTH)
            panel.add(detailLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out WorktreeListItem>,
            value: WorktreeListItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value != null) {
                val activeMarker = if (value.isActive) {
                    SpecCodingBundle.message("worktree.list.activeMarker")
                } else {
                    ""
                }
                val statusText = when (value.status) {
                    WorktreeStatus.ACTIVE -> SpecCodingBundle.message("worktree.status.active")
                    WorktreeStatus.MERGED -> SpecCodingBundle.message("worktree.status.merged")
                    WorktreeStatus.REMOVED -> SpecCodingBundle.message("worktree.status.removed")
                    WorktreeStatus.ERROR -> SpecCodingBundle.message("worktree.status.error")
                }
                titleLabel.text = "${value.specTaskId}$activeMarker"
                detailLabel.text = SpecCodingBundle.message("worktree.list.detail", statusText, value.branchName)
                detailLabel.foreground = statusColor(value.status)
            }

            panel.background = if (isSelected) list.selectionBackground else list.background
            titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            return panel
        }

        private fun statusColor(status: WorktreeStatus): Color {
            return when (status) {
                WorktreeStatus.ACTIVE -> JBColor(Color(76, 175, 80), Color(76, 175, 80))
                WorktreeStatus.MERGED -> JBColor(Color(33, 150, 243), Color(78, 154, 241))
                WorktreeStatus.REMOVED -> JBColor.GRAY
                WorktreeStatus.ERROR -> JBColor(Color(244, 67, 54), Color(239, 83, 80))
            }
        }
    }
}
