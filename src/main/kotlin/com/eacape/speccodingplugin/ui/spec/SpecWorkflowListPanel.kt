package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

class SpecWorkflowListPanel(
    private val onWorkflowSelected: (String) -> Unit,
    private val onCreateWorkflow: () -> Unit,
    private val onEditWorkflow: (String) -> Unit,
    private val onDeleteWorkflow: (String) -> Unit,
) : JPanel(BorderLayout()) {

    data class WorkflowListItem(
        val workflowId: String,
        val title: String,
        val description: String,
        val currentPhase: SpecPhase,
        val status: WorkflowStatus,
        val updatedAt: Long
    )

    private val listModel = DefaultListModel<WorkflowListItem>()
    private val workflowList = JBList(listModel)
    private val newButton = JButton()
    private var suppressSelectionEvents = false

    init {
        minimumSize = JBUI.size(JBUI.scale(132), 0)
        preferredSize = JBUI.size(JBUI.scale(188), 0)
        setupUI()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4, 4, 2, 4)

        newButton.text = newButtonText()
        styleActionButton(newButton)
        newButton.horizontalAlignment = SwingConstants.CENTER

        newButton.addActionListener { onCreateWorkflow() }

        workflowList.cellRenderer = WorkflowCellRenderer()
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.fixedCellHeight = -1
        workflowList.visibleRowCount = -1
        workflowList.isOpaque = false
        workflowList.setExpandableItemsEnabled(false)
        workflowList.border = JBUI.Borders.empty()
        workflowList.emptyText.text = SpecCodingBundle.message("spec.workflow.empty")
        workflowList.addListSelectionListener {
            if (suppressSelectionEvents) return@addListSelectionListener
            if (!it.valueIsAdjusting) {
                workflowList.selectedValue?.let { item ->
                    onWorkflowSelected(item.workflowId)
                }
            }
        }
        workflowList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    handleListClick(e)
                }

                override fun mouseExited(e: MouseEvent) {
                    workflowList.cursor = Cursor.getDefaultCursor()
                }
            },
        )
        workflowList.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    updateListCursor(e.point)
                }
            },
        )
        add(
            JBScrollPane(workflowList).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(newButton, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
    }

    fun updateWorkflows(items: List<WorkflowListItem>) {
        listModel.clear()
        items.forEach { listModel.addElement(it) }
    }

    fun setSelectedWorkflow(workflowId: String?) {
        suppressSelectionEvents = true
        try {
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
        } finally {
            suppressSelectionEvents = false
        }
    }

    fun refreshLocalizedTexts() {
        newButton.text = newButtonText()
        styleActionButton(newButton)
        workflowList.emptyText.text = SpecCodingBundle.message("spec.workflow.empty")
        workflowList.repaint()
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
        workflowList.selectedValue?.let { onDeleteWorkflow(it.workflowId) }
    }

    internal fun clickEditForTest() {
        workflowList.selectedValue?.let { onEditWorkflow(it.workflowId) }
    }

    private fun updateListCursor(point: Point) {
        val index = workflowList.locationToIndex(point)
        if (index < 0) {
            workflowList.cursor = Cursor.getDefaultCursor()
            return
        }
        val cellBounds = workflowList.getCellBounds(index, index) ?: run {
            workflowList.cursor = Cursor.getDefaultCursor()
            return
        }
        if (!cellBounds.contains(point)) {
            workflowList.cursor = Cursor.getDefaultCursor()
            return
        }
        workflowList.cursor = if (WorkflowCellRenderer.resolveRowAction(cellBounds, point) != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun handleListClick(event: MouseEvent) {
        val index = workflowList.locationToIndex(event.point)
        if (index < 0) return
        val cellBounds = workflowList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(event.point)) return
        val selectedId = listModel.getElementAt(index).workflowId
        workflowList.selectedIndex = index
        when (WorkflowCellRenderer.resolveRowAction(cellBounds, event.point)) {
            WorkflowCellRenderer.RowAction.DELETE -> onDeleteWorkflow(selectedId)
            WorkflowCellRenderer.RowAction.EDIT -> onEditWorkflow(selectedId)
            null -> {
                if (event.clickCount >= 2 && event.button == MouseEvent.BUTTON1) {
                    onEditWorkflow(selectedId)
                }
            }
        }
    }

    private fun newButtonText(): String = "+ ${SpecCodingBundle.message("spec.workflow.new")}"

    private class WorkflowCellRenderer : ListCellRenderer<WorkflowListItem> {
        enum class RowAction {
            EDIT,
            DELETE,
        }

        private val rowPanel = JPanel(BorderLayout())
        private val cardPanel = RoundedCardPanel(JBUI.scale(12))
        private val contentPanel = JPanel(BorderLayout(0, JBUI.scale(4)))
        private val textPanel = JPanel()
        private val titleLabel = JLabel()
        private val descriptionLabel = JLabel()
        private val phaseLabel = JLabel()
        private val statusLabel = JLabel()
        private val metaRow = JPanel(BorderLayout())
        private val actionPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, ACTION_ICON_GAP, 0))
        private val editActionLabel = JLabel(AllIcons.Actions.Edit)
        private val deleteActionLabel = JLabel(AllIcons.Actions.GC)

        init {
            rowPanel.isOpaque = false
            rowPanel.border = JBUI.Borders.empty(0, 0, ROW_BOTTOM_GAP, 0)

            cardPanel.layout = BorderLayout()
            cardPanel.border = JBUI.Borders.empty(CARD_VERTICAL_PAD, CARD_LEFT_PAD, CARD_VERTICAL_PAD, CARD_RIGHT_PAD)

            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
            descriptionLabel.font = descriptionLabel.font.deriveFont(Font.PLAIN, 10.5f)

            phaseLabel.font = phaseLabel.font.deriveFont(Font.PLAIN, 10.5f)
            statusLabel.font = statusLabel.font.deriveFont(Font.BOLD, 10.5f)

            textPanel.isOpaque = false
            textPanel.layout = javax.swing.BoxLayout(textPanel, javax.swing.BoxLayout.Y_AXIS)
            textPanel.add(titleLabel)
            textPanel.add(descriptionLabel)

            metaRow.isOpaque = false
            metaRow.add(phaseLabel, BorderLayout.WEST)
            metaRow.add(statusLabel, BorderLayout.EAST)

            actionPanel.isOpaque = false
            actionPanel.border = JBUI.Borders.empty(0, ACTION_PANEL_LEFT_PAD, 0, ACTION_PANEL_RIGHT_PAD)
            editActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
            editActionLabel.minimumSize = editActionLabel.preferredSize
            deleteActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
            deleteActionLabel.minimumSize = deleteActionLabel.preferredSize
            actionPanel.add(editActionLabel)
            actionPanel.add(deleteActionLabel)

            contentPanel.isOpaque = false
            contentPanel.add(textPanel, BorderLayout.CENTER)
            contentPanel.add(metaRow, BorderLayout.SOUTH)

            cardPanel.add(contentPanel, BorderLayout.CENTER)
            cardPanel.add(actionPanel, BorderLayout.EAST)
            rowPanel.add(cardPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out WorkflowListItem>,
            value: WorkflowListItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return rowPanel

            val titleColor = if (isSelected) {
                JBColor(Color(28, 45, 70), Color(230, 236, 244))
            } else {
                JBColor(Color(31, 36, 43), Color(220, 224, 232))
            }
            val phaseColor = if (isSelected) {
                JBColor(Color(73, 89, 113), Color(170, 182, 196))
            } else {
                JBColor(Color(96, 103, 114), Color(157, 164, 174))
            }
            val cardBg = if (isSelected) {
                JBColor(Color(225, 238, 255), Color(66, 84, 110))
            } else {
                JBColor(Color(247, 249, 252), Color(55, 59, 67))
            }
            val cardBorder = if (isSelected) {
                JBColor(Color(129, 167, 225), Color(100, 130, 167))
            } else {
                JBColor(Color(219, 226, 238), Color(74, 80, 90))
            }

            titleLabel.text = value.title.lowercase()
            titleLabel.foreground = titleColor
            val description = value.description.trim()
            descriptionLabel.isVisible = description.isNotBlank()
            if (descriptionLabel.isVisible) {
                descriptionLabel.text = truncateSingleLine(description, maxChars = 60)
                descriptionLabel.foreground = phaseColor
                descriptionLabel.toolTipText = description
            } else {
                descriptionLabel.text = ""
                descriptionLabel.toolTipText = null
            }
            phaseLabel.text = value.currentPhase.displayName.lowercase()
            phaseLabel.foreground = phaseColor
            statusLabel.text = localizeStatus(value.status).lowercase()
            editActionLabel.toolTipText = SpecCodingBundle.message("spec.workflow.edit")
            deleteActionLabel.toolTipText = SpecCodingBundle.message("spec.workflow.delete")
            statusLabel.foreground = if (isSelected) {
                JBColor(Color(55, 84, 128), Color(191, 214, 244))
            } else {
                getStatusColor(value.status)
            }

            cardPanel.updateColors(cardBg, cardBorder)
            editActionLabel.icon = if (isSelected) AllIcons.Actions.EditSource else AllIcons.Actions.Edit
            return rowPanel
        }

        private fun truncateSingleLine(value: String, maxChars: Int): String {
            val normalized = value.replace(Regex("\\s+"), " ").trim()
            if (normalized.length <= maxChars) return normalized
            return normalized.take(maxChars - 1).trimEnd() + "…"
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

        private class RoundedCardPanel(private val arc: Int) : JPanel() {
            private var fillColor: Color = JBColor(Color(247, 249, 252), Color(55, 59, 67))
            private var strokeColor: Color = JBColor(Color(219, 226, 238), Color(74, 80, 90))

            init {
                isOpaque = false
            }

            fun updateColors(fill: Color, stroke: Color) {
                fillColor = fill
                strokeColor = stroke
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = fillColor
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    g2.color = strokeColor
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
        }

        companion object {
            private val ROW_BOTTOM_GAP = JBUI.scale(8)
            private val CARD_VERTICAL_PAD = JBUI.scale(8)
            private val CARD_LEFT_PAD = JBUI.scale(10)
            private val CARD_RIGHT_PAD = JBUI.scale(12)
            private val ACTION_PANEL_LEFT_PAD = JBUI.scale(8)
            private val ACTION_PANEL_RIGHT_PAD = JBUI.scale(4)
            private val ACTION_ICON_SIZE = JBUI.scale(16)
            private val ACTION_ICON_GAP = JBUI.scale(6)
            private val ACTION_RIGHT_PAD = CARD_RIGHT_PAD + ACTION_PANEL_RIGHT_PAD
            private val ACTION_HIT_SLOP = JBUI.scale(3)

            fun resolveRowAction(cellBounds: Rectangle, point: Point): RowAction? {
                if (!cellBounds.contains(point)) {
                    return null
                }
                val iconZoneHeight = (cellBounds.height - ROW_BOTTOM_GAP - CARD_VERTICAL_PAD * 2)
                    .coerceAtLeast(ACTION_ICON_SIZE)
                val centerY = cellBounds.y + CARD_VERTICAL_PAD + iconZoneHeight / 2
                val topY = centerY - ACTION_ICON_SIZE / 2
                val deleteX = cellBounds.x + cellBounds.width - ACTION_RIGHT_PAD - ACTION_ICON_SIZE
                val editX = deleteX - ACTION_ICON_GAP - ACTION_ICON_SIZE
                val editRect = Rectangle(
                    editX - ACTION_HIT_SLOP,
                    topY - ACTION_HIT_SLOP,
                    ACTION_ICON_SIZE + ACTION_HIT_SLOP * 2,
                    ACTION_ICON_SIZE + ACTION_HIT_SLOP * 2,
                )
                val deleteRect = Rectangle(
                    deleteX - ACTION_HIT_SLOP,
                    topY - ACTION_HIT_SLOP,
                    ACTION_ICON_SIZE + ACTION_HIT_SLOP * 2,
                    ACTION_ICON_SIZE + ACTION_HIT_SLOP * 2,
                )
                return when {
                    deleteRect.contains(point) -> RowAction.DELETE
                    editRect.contains(point) -> RowAction.EDIT
                    else -> null
                }
            }
        }
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(12)),
            JBUI.Borders.empty(1, 8, 1, 8),
        )
        SpecUiStyle.applyRoundRect(button, arc = 12)
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

    companion object {
        private val BUTTON_BG = JBColor(Color(236, 244, 255), Color(66, 72, 84))
        private val BUTTON_BORDER = JBColor(Color(176, 194, 222), Color(104, 116, 134))
        private val BUTTON_FG = JBColor(Color(42, 66, 104), Color(205, 217, 236))
    }
}
