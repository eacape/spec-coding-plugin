package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class SpecWorkflowListPanel(
    private val onWorkflowSelected: (String) -> Unit,
    private val onCreateWorkflow: () -> Unit,
    private val onDeleteWorkflow: (String) -> Unit
) : JPanel(BorderLayout()) {

    data class WorkflowListItem(
        val workflowId: String,
        val title: String,
        val currentPhase: SpecPhase,
        val status: WorkflowStatus,
        val updatedAt: Long
    )

    private val listModel = DefaultListModel<WorkflowListItem>()
    private val workflowList = JBList(listModel)
    private val newButton = JButton(SpecCodingBundle.message("spec.workflow.new"))
    private val deleteButton = JButton(SpecCodingBundle.message("spec.workflow.delete"))

    init {
        setupUI()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4)

        // 工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.isOpaque = false
        newButton.addActionListener { onCreateWorkflow() }
        toolbar.add(newButton)

        deleteButton.addActionListener {
            workflowList.selectedValue?.let { onDeleteWorkflow(it.workflowId) }
        }
        toolbar.add(deleteButton)
        add(toolbar, BorderLayout.NORTH)

        // 列表
        workflowList.cellRenderer = WorkflowCellRenderer()
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                workflowList.selectedValue?.let { item ->
                    onWorkflowSelected(item.workflowId)
                }
            }
        }
        add(JBScrollPane(workflowList), BorderLayout.CENTER)
    }

    fun updateWorkflows(items: List<WorkflowListItem>) {
        listModel.clear()
        items.forEach { listModel.addElement(it) }
    }

    fun setSelectedWorkflow(workflowId: String?) {
        if (workflowId == null) {
            workflowList.clearSelection()
            return
        }
        for (i in 0 until listModel.size()) {
            if (listModel[i].workflowId == workflowId) {
                workflowList.selectedIndex = i
                return
            }
        }
    }

    internal fun itemsForTest(): List<WorkflowListItem> {
        return (0 until listModel.size()).map { listModel[it] }
    }

    internal fun selectedWorkflowIdForTest(): String? {
        return workflowList.selectedValue?.workflowId
    }

    internal fun clickNewForTest() {
        newButton.doClick()
    }

    internal fun clickDeleteForTest() {
        deleteButton.doClick()
    }

    private class WorkflowCellRenderer : ListCellRenderer<WorkflowListItem> {
        override fun getListCellRendererComponent(
            list: JList<out WorkflowListItem>,
            value: WorkflowListItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(6, 8)

            if (value == null) return panel

            val bg = if (isSelected) list.selectionBackground else list.background
            val fg = if (isSelected) list.selectionForeground else list.foreground
            panel.background = bg

            // 标题
            val titleLabel = JLabel(value.title)
            titleLabel.foreground = fg
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
            panel.add(titleLabel, BorderLayout.NORTH)

            // 状态行
            val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            statusPanel.isOpaque = false

            val phaseLabel = JLabel(value.currentPhase.displayName)
            phaseLabel.foreground = JBColor.GRAY
            phaseLabel.font = phaseLabel.font.deriveFont(Font.PLAIN, 11f)
            statusPanel.add(phaseLabel)

            val dot = JLabel(" \u2022 ")
            dot.foreground = JBColor.GRAY
            statusPanel.add(dot)

            val statusLabel = JLabel(localizeStatus(value.status))
            statusLabel.foreground = getStatusColor(value.status)
            statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 11f)
            statusPanel.add(statusLabel)

            panel.add(statusPanel, BorderLayout.SOUTH)
            return panel
        }

        private fun getStatusColor(status: WorkflowStatus): Color = when (status) {
            WorkflowStatus.IN_PROGRESS -> JBColor(Color(33, 150, 243), Color(78, 154, 241))
            WorkflowStatus.PAUSED -> JBColor(Color(255, 152, 0), Color(255, 167, 38))
            WorkflowStatus.COMPLETED -> JBColor(Color(76, 175, 80), Color(76, 175, 80))
            WorkflowStatus.FAILED -> JBColor(Color(244, 67, 54), Color(239, 83, 80))
        }

        private fun localizeStatus(status: WorkflowStatus): String = when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }
}
