package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal class SpecWorkflowTasksPanel(
    private val onTransitionStatus: (taskId: String, to: TaskStatus) -> Unit = { _, _ -> },
    private val onUpdateDependsOn: (taskId: String, dependsOn: List<String>) -> Unit = { _, _ -> },
    private val onUpdateRelatedFiles: (taskId: String, files: List<String>) -> Unit = { _, _ -> },
    private val onCompleteWithRelatedFiles: (taskId: String, files: List<String>) -> Unit = { _, _ -> },
    private val suggestRelatedFiles: (taskId: String, existingRelatedFiles: List<String>) -> List<String> = { _, existing -> existing },
    private val showHeader: Boolean = true,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private val headerTitleLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(12.5f)
        foreground = HEADER_FG
    }
    private val headerSummaryLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = HEADER_SECONDARY_FG
    }
    private val headerRefreshedLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.RIGHT
        font = JBUI.Fonts.smallFont().deriveFont(10.5f)
        foreground = HEADER_SECONDARY_FG
    }

    private val listModel = DefaultListModel<StructuredTask>()
    private val tasksList = JBList(listModel).apply {
        cellRenderer = TaskCellRenderer()
        visibleRowCount = -1
        fixedCellHeight = -1
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
    }

    private val statusComboBox = JComboBox<TaskStatus>().apply {
        renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value?.name.orEmpty()
        }
        isEnabled = false
    }
    private val applyStatusButton = JButton().apply {
        isEnabled = false
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { handleApplyStatus() }
    }
    private val editDependsOnButton = JButton().apply {
        isEnabled = false
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { handleEditDependsOn() }
    }
    private val editRelatedFilesButton = JButton().apply {
        isEnabled = false
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { handleEditRelatedFiles() }
    }

    private var currentWorkflowId: String? = null
    private var currentTasksById: Map<String, StructuredTask> = emptyMap()
    private var lastRefreshedAtMillis: Long? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 4, 6, 4)
        if (showHeader) {
            add(buildHeader(), BorderLayout.NORTH)
        }
        add(
            JBScrollPane(tasksList).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
        add(buildControls(), BorderLayout.SOUTH)

        tasksList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateControlsForSelection()
            }
        }
        refreshLocalizedTexts()
        showEmpty()
    }

    fun refreshLocalizedTexts() {
        headerTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.tasks.title")
        applyStatusButton.text = SpecCodingBundle.message("spec.toolwindow.tasks.status.apply")
        editDependsOnButton.text = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit")
        editRelatedFilesButton.text = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.edit")
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
        updateHeader()
    }

    fun showEmpty() {
        currentWorkflowId = null
        currentTasksById = emptyMap()
        lastRefreshedAtMillis = null
        listModel.clear()
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
        updateHeader()
        updateControlsForSelection()
    }

    fun showLoading() {
        currentWorkflowId = null
        currentTasksById = emptyMap()
        lastRefreshedAtMillis = null
        listModel.clear()
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.loading")
        updateHeader()
        updateControlsForSelection()
    }

    fun updateTasks(
        workflowId: String,
        tasks: List<StructuredTask>,
        refreshedAtMillis: Long,
    ) {
        val previousSelection = tasksList.selectedValue?.id
        currentWorkflowId = workflowId
        currentTasksById = tasks.associateBy { it.id }
        lastRefreshedAtMillis = refreshedAtMillis

        listModel.clear()
        tasks.forEach { listModel.addElement(it) }
        if (previousSelection != null) {
            val index = tasks.indexOfFirst { it.id == previousSelection }
            if (index >= 0) {
                tasksList.selectedIndex = index
            }
        }
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow")
        updateHeader()
        updateControlsForSelection()
    }

    internal fun snapshotForTest(): Map<String, String> {
        val tasks = (0 until listModel.size()).joinToString(" | ") { index ->
            val task = listModel[index]
            "${task.id}:${task.status.name}:${task.priority.name}"
        }
        val selectedId = tasksList.selectedValue?.id.orEmpty()
        return mapOf(
            "workflowId" to currentWorkflowId.orEmpty(),
            "headerVisible" to showHeader.toString(),
            "headerTitle" to headerTitleLabel.text.orEmpty(),
            "headerSummary" to headerSummaryLabel.text.orEmpty(),
            "headerRefreshed" to headerRefreshedLabel.text.orEmpty(),
            "tasks" to tasks,
            "selectedTaskId" to selectedId,
            "applyEnabled" to applyStatusButton.isEnabled.toString(),
            "dependsOnEnabled" to editDependsOnButton.isEnabled.toString(),
            "relatedFilesEnabled" to editRelatedFilesButton.isEnabled.toString(),
            "emptyText" to tasksList.emptyText.text.orEmpty(),
        )
    }

    internal fun selectTaskForTest(taskId: String) {
        for (index in 0 until listModel.size()) {
            if (listModel[index].id == taskId) {
                tasksList.selectedIndex = index
                return
            }
        }
    }

    internal fun clickApplyStatusForTest() {
        applyStatusButton.doClick()
    }

    private fun buildHeader(): JPanel {
        val left = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(headerTitleLabel, BorderLayout.NORTH)
            add(headerSummaryLabel, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(left, BorderLayout.CENTER)
            add(headerRefreshedLabel, BorderLayout.EAST)
        }
    }

    private fun buildControls(): JPanel {
        statusComboBox.preferredSize = JBUI.size(JBUI.scale(120), JBUI.scale(24))
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(JBLabel(SpecCodingBundle.message("spec.toolwindow.tasks.status.label")).apply {
                font = JBUI.Fonts.smallFont()
                foreground = HEADER_SECONDARY_FG
            })
            add(statusComboBox)
            add(applyStatusButton)
            add(editDependsOnButton)
            add(editRelatedFilesButton)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(row, BorderLayout.CENTER)
        }
    }

    private fun updateHeader() {
        val tasks = currentTasksById.values.toList()
        if (tasks.isEmpty()) {
            headerSummaryLabel.text = SpecCodingBundle.message("spec.toolwindow.tasks.summary.none")
        } else {
            val total = tasks.size
            val completed = tasks.count { it.status == TaskStatus.COMPLETED }
            val blocked = tasks.count { it.status == TaskStatus.BLOCKED }
            headerSummaryLabel.text = SpecCodingBundle.message(
                "spec.toolwindow.tasks.summary",
                total,
                completed,
                blocked,
            )
        }

        val refreshed = lastRefreshedAtMillis
        headerRefreshedLabel.text = refreshed?.let { millis ->
            REFRESHED_AT_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }.orEmpty()
    }

    private fun updateControlsForSelection() {
        val selected = tasksList.selectedValue
        if (selected == null) {
            statusComboBox.model = DefaultComboBoxModel()
            statusComboBox.isEnabled = false
            applyStatusButton.isEnabled = false
            editDependsOnButton.isEnabled = false
            editRelatedFilesButton.isEnabled = false
            return
        }

        val options = buildStatusOptions(selected.status)
        statusComboBox.model = DefaultComboBoxModel(options.toTypedArray())
        statusComboBox.selectedItem = selected.status
        statusComboBox.isEnabled = options.size > 1
        applyStatusButton.isEnabled = options.size > 1
        editDependsOnButton.isEnabled = selected.status != TaskStatus.COMPLETED && selected.status != TaskStatus.CANCELLED
        editRelatedFilesButton.isEnabled = true
    }

    private fun buildStatusOptions(current: TaskStatus): List<TaskStatus> {
        val transitions = TaskStatus.values().filter { to -> current.canTransitionTo(to) }
        return listOf(current) + transitions
    }

    private fun handleApplyStatus() {
        val selectedTask = tasksList.selectedValue ?: return
        val targetStatus = statusComboBox.selectedItem as? TaskStatus ?: return
        if (targetStatus == selectedTask.status) {
            return
        }
        if (targetStatus == TaskStatus.COMPLETED) {
            requestCompletionWithRelatedFiles(selectedTask)
        } else {
            onTransitionStatus(selectedTask.id, targetStatus)
        }
    }

    private fun requestCompletionWithRelatedFiles(selectedTask: StructuredTask) {
        val taskId = selectedTask.id
        val existing = selectedTask.relatedFiles
        val previousCursor = cursor
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        applyStatusButton.isEnabled = false
        statusComboBox.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val suggestedRelatedFiles = runCatching {
                suggestRelatedFiles(taskId, existing)
            }.getOrDefault(existing)

            ApplicationManager.getApplication().invokeLater {
                cursor = previousCursor
                updateControlsForSelection()

                val dialog = ListEditDialog(
                    title = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.confirm.title", taskId),
                    hintText = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.confirm.hint"),
                    initialLines = suggestedRelatedFiles,
                    okText = SpecCodingBundle.message("spec.toolwindow.tasks.complete.ok"),
                )
                if (!dialog.showAndGet()) {
                    return@invokeLater
                }
                onCompleteWithRelatedFiles(taskId, dialog.resultLines)
            }
        }
    }

    private fun handleEditDependsOn() {
        val selectedTask = tasksList.selectedValue ?: return
        val dialog = ListEditDialog(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.dialog.title", selectedTask.id),
            hintText = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.dialog.hint"),
            initialLines = selectedTask.dependsOn,
        )
        if (!dialog.showAndGet()) {
            return
        }
        onUpdateDependsOn(selectedTask.id, dialog.resultLines)
    }

    private fun handleEditRelatedFiles() {
        val selectedTask = tasksList.selectedValue ?: return
        val dialog = ListEditDialog(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.dialog.title", selectedTask.id),
            hintText = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.dialog.hint"),
            initialLines = selectedTask.relatedFiles,
        )
        if (!dialog.showAndGet()) {
            return
        }
        onUpdateRelatedFiles(selectedTask.id, dialog.resultLines)
    }

    private class TaskCellRenderer : javax.swing.ListCellRenderer<StructuredTask> {
        private val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        private val titleLabel = JBLabel()
        private val metaLabel = JBLabel()
        private val statusChipLabel = JBLabel()
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(6, 8)

            titleLabel.font = JBUI.Fonts.label().deriveFont(12f)
            titleLabel.foreground = TITLE_FG
            metaLabel.font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            metaLabel.foreground = META_FG

            statusChipLabel.isOpaque = true
            statusChipLabel.font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            statusChipLabel.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)

            rightPanel.isOpaque = false
            rightPanel.add(statusChipLabel)

            panel.add(
                JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(titleLabel, BorderLayout.NORTH)
                    add(metaLabel, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            panel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out StructuredTask>?,
            value: StructuredTask?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value == null) {
                titleLabel.text = ""
                metaLabel.text = ""
                statusChipLabel.text = ""
                return panel
            }
            val background = if (isSelected) SELECTED_BG else ROW_BG
            panel.background = background
            titleLabel.text = "${value.id}: ${value.title}"
            metaLabel.text = "${value.priority.name} 路 dependsOn=${value.dependsOn.size}, relatedFiles=${value.relatedFiles.size}"
            statusChipLabel.text = value.status.name
            applyChipStyle(statusChipLabel, value.status, isSelected)
            return panel
        }

        private fun applyChipStyle(label: JBLabel, status: TaskStatus, selected: Boolean) {
            val palette = when (status) {
                TaskStatus.PENDING -> ChipPalette(
                    bg = JBColor(Color(230, 239, 252), Color(72, 86, 104)),
                    fg = JBColor(Color(32, 89, 163), Color(195, 220, 255)),
                    border = JBColor(Color(185, 207, 238), Color(92, 106, 128)),
                )

                TaskStatus.IN_PROGRESS -> ChipPalette(
                    bg = JBColor(Color(229, 247, 238), Color(62, 92, 82)),
                    fg = JBColor(Color(22, 109, 78), Color(193, 239, 216)),
                    border = JBColor(Color(182, 225, 205), Color(80, 122, 108)),
                )

                TaskStatus.BLOCKED -> ChipPalette(
                    bg = JBColor(Color(255, 243, 224), Color(92, 74, 62)),
                    fg = JBColor(Color(166, 92, 0), Color(255, 222, 181)),
                    border = JBColor(Color(237, 203, 153), Color(120, 94, 78)),
                )

                TaskStatus.COMPLETED -> ChipPalette(
                    bg = JBColor(Color(232, 245, 233), Color(54, 82, 62)),
                    fg = JBColor(Color(24, 111, 41), Color(186, 232, 205)),
                    border = JBColor(Color(176, 219, 188), Color(74, 108, 84)),
                )

                TaskStatus.CANCELLED -> ChipPalette(
                    bg = JBColor(Color(245, 245, 245), Color(74, 79, 86)),
                    fg = JBColor(Color(102, 102, 102), Color(208, 214, 222)),
                    border = JBColor(Color(214, 214, 214), Color(92, 98, 108)),
                )
            }
            label.background = if (selected) palette.bg.darker() else palette.bg
            label.foreground = palette.fg
            label.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(palette.border, JBUI.scale(10)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6),
            )
        }

        private data class ChipPalette(
            val bg: Color,
            val fg: Color,
            val border: Color,
        )

        companion object {
            private val TITLE_FG = JBColor(Color(30, 36, 44), Color(225, 229, 235))
            private val META_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            private val ROW_BG = JBColor(Color(255, 255, 255), Color(47, 51, 56))
            private val SELECTED_BG = JBColor(Color(231, 241, 255), Color(59, 77, 92))
        }
    }

    private class ListEditDialog(
        title: String,
        private val hintText: String,
        initialLines: List<String>,
        okText: String? = null,
    ) : DialogWrapper(true) {

        private val textArea = JBTextArea().apply {
            text = initialLines.joinToString("\n")
            lineWrap = false
            wrapStyleWord = false
        }

        var resultLines: List<String> = emptyList()
            private set

        init {
            this.title = title
            okText?.let { text -> setOKButtonText(text) }
            init()
        }

        override fun createCenterPanel(): JComponent {
            val hintLabel = JBLabel(hintText).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
                border = JBUI.Borders.emptyBottom(6)
            }
            val areaScroll = JBScrollPane(textArea).apply {
                preferredSize = JBUI.size(520, 260)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }
            return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(10)
                add(hintLabel, BorderLayout.NORTH)
                add(areaScroll, BorderLayout.CENTER)
            }
        }

        override fun doOKAction() {
            resultLines = parseListLines(textArea.text.orEmpty())
            super.doOKAction()
        }

        private fun parseListLines(raw: String): List<String> {
            return raw
                .replace('\r', '\n')
                .split('\n', ',', ';')
                .map { token -> token.trim() }
                .filter { token -> token.isNotEmpty() }
        }
    }

    companion object {
        private val HEADER_FG = JBColor(Color(35, 40, 47), Color(222, 226, 232))
        private val HEADER_SECONDARY_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val REFRESHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
