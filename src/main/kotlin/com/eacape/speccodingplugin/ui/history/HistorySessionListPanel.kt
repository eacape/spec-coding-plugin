package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.SessionExportFormat
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

class HistorySessionListPanel(
    private val onSessionSelected: (String) -> Unit,
    private val onOpenSession: (String) -> Unit,
    private val onExportSession: (String, SessionExportFormat) -> Unit,
    private val onDeleteSession: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SessionSummary>()
    private val sessionList = JBList(listModel)
    private val openButton = JButton(SpecCodingBundle.message("history.action.open"))
    private val exportFormatCombo = JComboBox(SessionExportFormat.entries.toTypedArray())
    private val exportButton = JButton(SpecCodingBundle.message("history.action.export"))
    private val deleteButton = JButton(SpecCodingBundle.message("history.action.delete"))

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
    }

    private fun setupUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
        }

        openButton.addActionListener { selectedSessionId()?.let(onOpenSession) }
        exportButton.addActionListener {
            val sessionId = selectedSessionId() ?: return@addActionListener
            val format = exportFormatCombo.selectedItem as? SessionExportFormat ?: SessionExportFormat.MARKDOWN
            onExportSession(sessionId, format)
        }
        deleteButton.addActionListener { selectedSessionId()?.let(onDeleteSession) }

        toolbar.add(openButton)
        toolbar.add(exportFormatCombo)
        toolbar.add(exportButton)
        toolbar.add(deleteButton)
        add(toolbar, BorderLayout.NORTH)

        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.cellRenderer = SessionCellRenderer()
        sessionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateButtonStates()
                selectedSessionId()?.let(onSessionSelected)
            }
        }
        updateButtonStates()

        add(JBScrollPane(sessionList), BorderLayout.CENTER)
    }

    fun updateSessions(items: List<SessionSummary>) {
        val selectedId = selectedSessionId()
        listModel.clear()
        items.forEach(listModel::addElement)

        if (selectedId != null) {
            setSelectedSession(selectedId)
        }

        if (sessionList.selectedValue == null) {
            updateButtonStates()
        }
    }

    fun setSelectedSession(sessionId: String?) {
        if (sessionId == null) {
            sessionList.clearSelection()
            updateButtonStates()
            return
        }

        for (i in 0 until listModel.size()) {
            if (listModel[i].id == sessionId) {
                sessionList.selectedIndex = i
                updateButtonStates()
                return
            }
        }
    }

    internal fun selectedSessionIdForTest(): String? = selectedSessionId()

    internal fun sessionsForTest(): List<SessionSummary> = (0 until listModel.size()).map { listModel[it] }

    internal fun buttonStatesForTest(): Map<String, Boolean> {
        return mapOf(
            "openEnabled" to openButton.isEnabled,
            "exportEnabled" to exportButton.isEnabled,
            "deleteEnabled" to deleteButton.isEnabled,
        )
    }

    internal fun clickOpenForTest() {
        openButton.doClick()
    }

    internal fun clickDeleteForTest() {
        deleteButton.doClick()
    }

    internal fun clickExportForTest() {
        exportButton.doClick()
    }

    private fun selectedSessionId(): String? = sessionList.selectedValue?.id

    private fun updateButtonStates() {
        val hasSelection = selectedSessionId() != null
        openButton.isEnabled = hasSelection
        exportButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
    }

    private class SessionCellRenderer : ListCellRenderer<SessionSummary> {
        private val panel = JPanel(BorderLayout())
        private val titleLabel = JLabel()
        private val detailLabel = JLabel()

        init {
            panel.border = JBUI.Borders.empty(6, 8)
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, 11f)
            detailLabel.foreground = JBColor.GRAY
            panel.add(titleLabel, BorderLayout.NORTH)
            panel.add(detailLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out SessionSummary>,
            value: SessionSummary?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value != null) {
                titleLabel.text = value.title
                val bindingText = when {
                    !value.worktreeId.isNullOrBlank() -> SpecCodingBundle.message(
                        "history.binding.worktree",
                        value.worktreeId,
                    )

                    !value.specTaskId.isNullOrBlank() -> SpecCodingBundle.message(
                        "history.binding.spec",
                        value.specTaskId,
                    )

                    else -> SpecCodingBundle.message("history.binding.general")
                }
                detailLabel.text = SpecCodingBundle.message(
                    "history.binding.detail",
                    bindingText,
                    value.messageCount,
                )
            }

            panel.background = if (isSelected) list.selectionBackground else list.background
            titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            return panel
        }
    }
}
