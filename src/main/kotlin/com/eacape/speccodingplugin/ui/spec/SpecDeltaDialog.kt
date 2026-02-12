package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaStatus
import com.eacape.speccodingplugin.spec.SpecPhaseDelta
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

class SpecDeltaDialog(
    delta: SpecWorkflowDelta,
) : DialogWrapper(true) {

    private val tableModel = DeltaTableModel(delta.phaseDeltas)
    private val deltaTable = JTable(tableModel)
    private val summaryLabel = JBLabel(buildSummaryText(delta))
    private val detailArea = JBTextArea()

    init {
        title = SpecCodingBundle.message("spec.delta.dialog.title")
        setupTable()
        setupDetailArea()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(460))
        panel.border = JBUI.Borders.empty(8)

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        header.isOpaque = false
        header.add(summaryLabel)
        panel.add(header, BorderLayout.NORTH)

        val centerSplit = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT)
        centerSplit.resizeWeight = 0.56
        centerSplit.topComponent = JBScrollPane(deltaTable)
        centerSplit.bottomComponent = JBScrollPane(detailArea)
        panel.add(centerSplit, BorderLayout.CENTER)

        if (tableModel.rowCount > 0) {
            deltaTable.setRowSelectionInterval(0, 0)
            updateDetail(0)
        }

        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun setupTable() {
        deltaTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = deltaTable.selectedRow
                if (row >= 0) {
                    updateDetail(row)
                }
            }
        }
    }

    private fun setupDetailArea() {
        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.wrapStyleWord = true
    }

    private fun updateDetail(row: Int) {
        val delta = tableModel.deltaAt(row)
        detailArea.text = buildDetailText(delta)
        detailArea.caretPosition = 0
    }

    private fun buildSummaryText(delta: SpecWorkflowDelta): String {
        val added = delta.count(SpecDeltaStatus.ADDED)
        val modified = delta.count(SpecDeltaStatus.MODIFIED)
        val removed = delta.count(SpecDeltaStatus.REMOVED)
        val unchanged = delta.count(SpecDeltaStatus.UNCHANGED)
        return SpecCodingBundle.message(
            "spec.delta.summary",
            delta.baselineWorkflowId,
            delta.targetWorkflowId,
            added,
            modified,
            removed,
            unchanged,
        )
    }

    private fun buildDetailText(delta: SpecPhaseDelta): String {
        return buildString {
            appendLine(SpecCodingBundle.message("spec.delta.detail.phase", delta.phase.displayName))
            appendLine(SpecCodingBundle.message("spec.delta.detail.status", localizeStatus(delta.status)))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.delta.detail.baseline"))
            appendLine(snippet(delta.baselineDocument?.content))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.delta.detail.target"))
            appendLine(snippet(delta.targetDocument?.content))
        }
    }

    private fun snippet(content: String?): String {
        if (content.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.delta.detail.empty")
        }
        val normalized = content.replace("\r\n", "\n").trim()
        return if (normalized.length <= 300) normalized else normalized.take(300) + "..."
    }

    private fun localizeStatus(status: SpecDeltaStatus): String {
        return when (status) {
            SpecDeltaStatus.ADDED -> SpecCodingBundle.message("spec.delta.status.added")
            SpecDeltaStatus.MODIFIED -> SpecCodingBundle.message("spec.delta.status.modified")
            SpecDeltaStatus.REMOVED -> SpecCodingBundle.message("spec.delta.status.removed")
            SpecDeltaStatus.UNCHANGED -> SpecCodingBundle.message("spec.delta.status.unchanged")
        }
    }

    private class DeltaTableModel(
        private val items: List<SpecPhaseDelta>,
    ) : AbstractTableModel() {
        override fun getRowCount(): Int = items.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String {
            return when (column) {
                0 -> SpecCodingBundle.message("spec.delta.column.phase")
                1 -> SpecCodingBundle.message("spec.delta.column.status")
                2 -> SpecCodingBundle.message("spec.delta.column.baseline")
                else -> SpecCodingBundle.message("spec.delta.column.target")
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = items[rowIndex]
            return when (columnIndex) {
                0 -> item.phase.displayName
                1 -> when (item.status) {
                    SpecDeltaStatus.ADDED -> SpecCodingBundle.message("spec.delta.status.added")
                    SpecDeltaStatus.MODIFIED -> SpecCodingBundle.message("spec.delta.status.modified")
                    SpecDeltaStatus.REMOVED -> SpecCodingBundle.message("spec.delta.status.removed")
                    SpecDeltaStatus.UNCHANGED -> SpecCodingBundle.message("spec.delta.status.unchanged")
                }

                2 -> item.baselineDocument?.metadata?.title ?: "-"
                else -> item.targetDocument?.metadata?.title ?: "-"
            }
        }

        fun deltaAt(row: Int): SpecPhaseDelta = items[row]
    }

}
