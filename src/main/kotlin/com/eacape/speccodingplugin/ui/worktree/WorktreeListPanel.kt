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
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
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
        border = JBUI.Borders.empty(8)
        minimumSize = JBUI.size(JBUI.scale(220), 0)
        setupUI()
    }

    private fun setupUI() {
        val toolbar = JPanel(GridLayout(1, 0, 6, 0)).apply {
            isOpaque = false
        }

        createButton.addActionListener { onCreateWorktree() }
        switchButton.addActionListener { selectedId()?.let(onSwitchWorktree) }
        mergeButton.addActionListener { selectedId()?.let(onMergeWorktree) }
        cleanupButton.addActionListener { selectedId()?.let(onCleanupWorktree) }
        styleToolbarButton(createButton)
        styleToolbarButton(switchButton)
        styleToolbarButton(mergeButton)
        styleToolbarButton(cleanupButton)

        toolbar.add(createButton)
        toolbar.add(switchButton)
        toolbar.add(mergeButton)
        toolbar.add(cleanupButton)

        val toolbarCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TOOLBAR_BORDER, 1),
                JBUI.Borders.empty(8),
            )
            add(toolbar, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        worktreeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        worktreeList.cellRenderer = WorktreeCellRenderer()
        worktreeList.fixedCellHeight = -1
        worktreeList.visibleRowCount = -1
        worktreeList.border = JBUI.Borders.empty()
        worktreeList.emptyText.text = SpecCodingBundle.message("worktree.list.empty")
        worktreeList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateButtonStates(worktreeList.selectedValue)
                worktreeList.selectedValue?.let { item -> onWorktreeSelected(item.id) }
            }
        }

        updateButtonStates(null)
        add(
            JBScrollPane(worktreeList).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(LIST_BORDER, 1),
                    JBUI.Borders.empty(2),
                )
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
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
        worktreeList.emptyText.text = SpecCodingBundle.message("worktree.list.empty")
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

    private fun styleToolbarButton(button: JButton) {
        button.isFocusable = false
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(10))
        button.margin = JBUI.insets(4, 8, 4, 8)
    }

    private fun updateButtonStates(selected: WorktreeListItem?) {
        val hasSelection = selected != null
        val isActive = selected?.status == WorktreeStatus.ACTIVE
        switchButton.isEnabled = hasSelection && isActive
        mergeButton.isEnabled = hasSelection && isActive
        cleanupButton.isEnabled = hasSelection && selected.status != WorktreeStatus.REMOVED
    }

    private class WorktreeCellRenderer : ListCellRenderer<WorktreeListItem> {
        private val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        private val titleLabel = JBLabel()
        private val subtitleLabel = JBLabel()
        private val detailLabel = JBLabel()
        private val textPanel = JPanel()

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(8, 10)

            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12.5f)

            subtitleLabel.font = subtitleLabel.font.deriveFont(Font.PLAIN, 11f)
            subtitleLabel.foreground = SUBTITLE_FG

            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, 10.5f)

            textPanel.isOpaque = false
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.add(titleLabel)
            textPanel.add(subtitleLabel)
            textPanel.add(detailLabel)

            panel.add(textPanel, BorderLayout.CENTER)
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
                subtitleLabel.text = value.specTitle
                detailLabel.text = SpecCodingBundle.message("worktree.list.detail", statusText, value.branchName)
            } else {
                titleLabel.text = ""
                subtitleLabel.text = ""
                detailLabel.text = ""
            }

            panel.background = if (isSelected) list.selectionBackground else list.background
            titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            subtitleLabel.foreground = if (isSelected) list.selectionForeground else SUBTITLE_FG
            detailLabel.foreground = if (isSelected) list.selectionForeground else statusColor(value?.status ?: WorktreeStatus.ACTIVE)
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

        companion object {
            private val SUBTITLE_FG = JBColor(Color(88, 98, 113), Color(168, 176, 189))
        }
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(248, 250, 253), Color(58, 63, 71))
        private val TOOLBAR_BORDER = JBColor(Color(214, 222, 236), Color(82, 90, 102))
        private val LIST_BORDER = JBColor(Color(211, 218, 232), Color(79, 85, 96))
    }
}
