package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal class SpecWorkflowTasksPanel(
    private val onTransitionStatus: (taskId: String, to: TaskStatus) -> Unit = { _, _ -> },
    private val onExecuteTask: (taskId: String, retry: Boolean) -> Unit = { _, _ -> },
    private val onUpdateDependsOn: (taskId: String, dependsOn: List<String>) -> Unit = { _, _ -> },
    private val onUpdateRelatedFiles: (taskId: String, files: List<String>) -> Unit = { _, _ -> },
    private val onCompleteWithRelatedFiles: (
        taskId: String,
        files: List<String>,
        verificationResult: TaskVerificationResult?,
    ) -> Unit = { _, _, _ -> },
    private val onUpdateVerificationResult: (taskId: String, verificationResult: TaskVerificationResult?) -> Unit = { _, _ -> },
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
        font = JBUI.Fonts.smallFont()
    }
    private val applyStatusButton = createIconActionButton {
        handleApplyStatus()
    }
    private val executeTaskButton = createTextActionButton {
        handleExecuteTask()
    }
    private val editDependsOnButton = createIconActionButton {
        handleEditDependsOn()
    }
    private val editRelatedFilesButton = createIconActionButton {
        handleEditRelatedFiles()
    }
    private val editVerificationResultButton = createTextActionButton {
        handleEditVerificationResult()
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
        applyActionButtonPresentation()
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
        updateHeader()
        updateControlsForSelection()
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
            "statusComboHeight" to statusComboBox.preferredSize.height.toString(),
            "executeText" to executeTaskButton.text.orEmpty(),
            "executeEnabled" to executeTaskButton.isEnabled.toString(),
            "executeTooltip" to executeTaskButton.toolTipText.orEmpty(),
            "applyText" to applyStatusButton.text.orEmpty(),
            "applyTooltip" to applyStatusButton.toolTipText.orEmpty(),
            "applyHasIcon" to (applyStatusButton.icon != null).toString(),
            "applyRolloverEnabled" to applyStatusButton.isRolloverEnabled.toString(),
            "dependsOnText" to editDependsOnButton.text.orEmpty(),
            "dependsOnTooltip" to editDependsOnButton.toolTipText.orEmpty(),
            "dependsOnHasIcon" to (editDependsOnButton.icon != null).toString(),
            "dependsOnRolloverEnabled" to editDependsOnButton.isRolloverEnabled.toString(),
            "relatedFilesText" to editRelatedFilesButton.text.orEmpty(),
            "relatedFilesTooltip" to editRelatedFilesButton.toolTipText.orEmpty(),
            "relatedFilesHasIcon" to (editRelatedFilesButton.icon != null).toString(),
            "relatedFilesRolloverEnabled" to editRelatedFilesButton.isRolloverEnabled.toString(),
            "verificationText" to editVerificationResultButton.text.orEmpty(),
            "verificationEnabled" to editVerificationResultButton.isEnabled.toString(),
            "verificationTooltip" to editVerificationResultButton.toolTipText.orEmpty(),
            "applyEnabled" to applyStatusButton.isEnabled.toString(),
            "dependsOnEnabled" to editDependsOnButton.isEnabled.toString(),
            "relatedFilesEnabled" to editRelatedFilesButton.isEnabled.toString(),
            "emptyText" to tasksList.emptyText.text.orEmpty(),
        )
    }

    internal fun selectTaskForTest(taskId: String) {
        selectTask(taskId)
    }

    internal fun selectTask(taskId: String): Boolean {
        for (index in 0 until listModel.size()) {
            if (listModel[index].id == taskId) {
                tasksList.selectedIndex = index
                return true
            }
        }
        return false
    }

    internal fun requestCompletionForTask(taskId: String): Boolean {
        if (!selectTask(taskId)) {
            return false
        }
        val selectedTask = tasksList.selectedValue ?: return false
        requestCompletionWithRelatedFiles(selectedTask)
        return true
    }

    internal fun requestExecutionForTask(taskId: String): Boolean {
        if (!selectTask(taskId)) {
            return false
        }
        return requestExecutionForSelection()
    }

    internal fun clickApplyStatusForTest() {
        applyStatusButton.doClick()
    }

    internal fun clickExecuteTaskForTest() {
        executeTaskButton.doClick()
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
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = statusComboBox,
            minWidth = JBUI.scale(88),
            maxWidth = JBUI.scale(220),
            height = JBUI.scale(28),
        )
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(executeTaskButton)
            add(editVerificationResultButton)
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

    private fun createIconActionButton(onClick: () -> Unit): JButton {
        return JButton().apply {
            isEnabled = false
            isFocusable = false
            addActionListener { onClick() }
            SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        }
    }

    private fun createTextActionButton(onClick: () -> Unit): JButton {
        return JButton().apply {
            isEnabled = false
            isFocusable = false
            addActionListener { onClick() }
            styleTextActionButton(this)
        }
    }

    private fun applyActionButtonPresentation() {
        SpecUiStyle.configureIconActionButton(
            button = applyStatusButton,
            icon = APPLY_STATUS_ICON,
            tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.status.apply"),
        )
        SpecUiStyle.configureIconActionButton(
            button = editDependsOnButton,
            icon = EDIT_DEPENDS_ON_ICON,
            tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"),
        )
        SpecUiStyle.configureIconActionButton(
            button = editRelatedFilesButton,
            icon = EDIT_RELATED_FILES_ICON,
            tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.edit"),
        )
        updateVerificationButtonPresentation(tasksList.selectedValue)
    }

    private fun styleTextActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.isOpaque = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.foreground = ACTION_BUTTON_FG
        button.background = ACTION_BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(ACTION_BUTTON_BORDER, JBUI.scale(12)),
            JBUI.Borders.empty(1, 8, 1, 8),
        )
        SpecUiStyle.applyRoundRect(button, arc = 12)
        updateTextButtonSize(button)
    }

    private fun updateTextButtonSize(button: JButton) {
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text.orEmpty())
        val insets = button.insets
        val lafWidth = button.preferredSize?.width ?: 0
        val width = maxOf(
            lafWidth,
            textWidth + insets.left + insets.right + JBUI.scale(16),
            JBUI.scale(72),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
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
            executeTaskButton.text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.none")
            executeTaskButton.toolTipText = SpecCodingBundle.message("spec.toolwindow.tasks.execute.unavailable")
            updateTextButtonSize(executeTaskButton)
            executeTaskButton.isEnabled = false
            applyStatusButton.isEnabled = false
            editVerificationResultButton.text = SpecCodingBundle.message("spec.toolwindow.tasks.verification.button")
            editVerificationResultButton.toolTipText = SpecCodingBundle.message("spec.toolwindow.tasks.verification.unavailable")
            updateTextButtonSize(editVerificationResultButton)
            editVerificationResultButton.isEnabled = false
            editDependsOnButton.isEnabled = false
            editRelatedFilesButton.isEnabled = false
            return
        }

        val options = buildStatusOptions(selected.status)
        statusComboBox.model = DefaultComboBoxModel(options.toTypedArray())
        statusComboBox.selectedItem = selected.status
        statusComboBox.isEnabled = options.size > 1
        updateExecuteButtonPresentation(selected)
        applyStatusButton.isEnabled = options.size > 1
        updateVerificationButtonPresentation(selected)
        editDependsOnButton.isEnabled = selected.status != TaskStatus.COMPLETED && selected.status != TaskStatus.CANCELLED
        editRelatedFilesButton.isEnabled = true
    }

    private fun updateExecuteButtonPresentation(selected: StructuredTask) {
        val presentation = when (selected.status) {
            TaskStatus.PENDING -> TaskExecutionPresentation(
                text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start"),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", selected.id),
                enabled = true,
            )

            TaskStatus.BLOCKED -> TaskExecutionPresentation(
                text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume"),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume.tooltip", selected.id),
                enabled = true,
            )

            TaskStatus.IN_PROGRESS -> TaskExecutionPresentation(
                text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete"),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete.tooltip", selected.id),
                enabled = true,
            )

            TaskStatus.COMPLETED -> TaskExecutionPresentation(
                text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done"),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done.tooltip", selected.id),
                enabled = false,
            )

            TaskStatus.CANCELLED -> TaskExecutionPresentation(
                text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled"),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled.tooltip", selected.id),
                enabled = false,
            )
        }
        executeTaskButton.text = presentation.text
        executeTaskButton.toolTipText = presentation.tooltip
        updateTextButtonSize(executeTaskButton)
        executeTaskButton.isEnabled = presentation.enabled
    }

    private fun updateVerificationButtonPresentation(selected: StructuredTask?) {
        val hasVerification = selected?.verificationResult != null
        editVerificationResultButton.text = if (hasVerification) {
            SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit")
        } else {
            SpecCodingBundle.message("spec.toolwindow.tasks.verification.button")
        }
        editVerificationResultButton.toolTipText = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.verification.unavailable")
            hasVerification -> SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit.tooltip", selected.id)
            else -> SpecCodingBundle.message("spec.toolwindow.tasks.verification.record.tooltip", selected.id)
        }
        updateTextButtonSize(editVerificationResultButton)
        editVerificationResultButton.isEnabled = selected != null && selected.status != TaskStatus.CANCELLED
    }

    private fun buildStatusOptions(current: TaskStatus): List<TaskStatus> {
        val transitions = TaskStatus.values().filter { to -> current.canTransitionTo(to) }
        return listOf(current) + transitions
    }

    private fun handleExecuteTask() {
        requestExecutionForSelection()
    }

    private fun requestExecutionForSelection(): Boolean {
        val selectedTask = tasksList.selectedValue ?: return false
        when (selectedTask.status) {
            TaskStatus.PENDING,
            -> onExecuteTask(selectedTask.id, false)

            TaskStatus.BLOCKED,
            -> onExecuteTask(selectedTask.id, true)

            TaskStatus.IN_PROGRESS -> requestCompletionWithRelatedFiles(selectedTask)
            else -> return false
        }
        return true
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
                val verificationDialog = TaskVerificationResultDialog(
                    taskId = taskId,
                    initialResult = selectedTask.verificationResult,
                    title = SpecCodingBundle.message("spec.toolwindow.tasks.verification.confirm.title", taskId),
                )
                if (!verificationDialog.showAndGet()) {
                    return@invokeLater
                }
                onCompleteWithRelatedFiles(taskId, dialog.resultLines, verificationDialog.result)
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

    private fun handleEditVerificationResult() {
        val selectedTask = tasksList.selectedValue ?: return
        val dialog = TaskVerificationResultDialog(
            taskId = selectedTask.id,
            initialResult = selectedTask.verificationResult,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.verification.dialog.title", selectedTask.id),
        )
        if (!dialog.showAndGet()) {
            return
        }
        onUpdateVerificationResult(selectedTask.id, dialog.result)
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
            metaLabel.text = "${value.priority.name} | dependsOn=${value.dependsOn.size}, relatedFiles=${value.relatedFiles.size}"
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

    private class TaskVerificationResultDialog(
        private val taskId: String,
        initialResult: TaskVerificationResult?,
        title: String,
    ) : DialogWrapper(true) {
        private val conclusionCombo = JComboBox(VerificationDialogOption.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create<VerificationDialogOption> { label, value, _ ->
                label.text = value?.displayName.orEmpty()
            }
        }
        private val runIdField = JBTextField(initialResult?.runId.orEmpty())
        private val atField = JBTextField(initialResult?.at.orEmpty())
        private val summaryArea = JBTextArea().apply {
            text = initialResult?.summary.orEmpty()
            lineWrap = true
            wrapStyleWord = true
            rows = 5
        }

        var result: TaskVerificationResult? = initialResult
            private set

        init {
            this.title = title
            conclusionCombo.selectedItem = VerificationDialogOption.from(initialResult?.conclusion)
            conclusionCombo.addActionListener { syncFieldsForSelection() }
            init()
            syncFieldsForSelection()
        }

        override fun createCenterPanel(): JComponent {
            val hintLabel = JBLabel(
                SpecCodingBundle.message("spec.toolwindow.tasks.verification.dialog.hint", taskId),
            ).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
                border = JBUI.Borders.emptyBottom(6)
            }
            val formPanel = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                isOpaque = false
                add(createFieldRow(SpecCodingBundle.message("spec.toolwindow.tasks.verification.conclusion"), conclusionCombo))
                add(createFieldRow(SpecCodingBundle.message("spec.toolwindow.tasks.verification.runId"), runIdField))
                add(createFieldRow(SpecCodingBundle.message("spec.toolwindow.tasks.verification.at"), atField))
                add(
                    createFieldRow(
                        SpecCodingBundle.message("spec.toolwindow.tasks.verification.summary"),
                        JBScrollPane(summaryArea).apply {
                            preferredSize = JBUI.size(0, 120)
                            minimumSize = JBUI.size(0, 120)
                        },
                    ),
                )
            }
            return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(10)
                add(hintLabel, BorderLayout.NORTH)
                add(formPanel, BorderLayout.CENTER)
            }
        }

        override fun doValidate(): ValidationInfo? {
            val option = conclusionCombo.selectedItem as? VerificationDialogOption ?: return null
            if (option.conclusion == null) {
                return null
            }
            if (runIdField.text.isNullOrBlank()) {
                return ValidationInfo(
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.validation.runId"),
                    runIdField,
                )
            }
            if (atField.text.isNullOrBlank()) {
                return ValidationInfo(
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.validation.at"),
                    atField,
                )
            }
            if (summaryArea.text.isNullOrBlank()) {
                return ValidationInfo(
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.validation.summary"),
                    summaryArea,
                )
            }
            return null
        }

        override fun doOKAction() {
            val option = conclusionCombo.selectedItem as? VerificationDialogOption ?: return
            result = option.conclusion?.let { conclusion ->
                TaskVerificationResult(
                    conclusion = conclusion,
                    runId = runIdField.text.orEmpty().trim(),
                    summary = summaryArea.text.orEmpty().trim(),
                    at = atField.text.orEmpty().trim(),
                )
            }
            super.doOKAction()
        }

        private fun syncFieldsForSelection() {
            val option = conclusionCombo.selectedItem as? VerificationDialogOption ?: VerificationDialogOption.NONE
            val editable = option.conclusion != null
            if (editable && runIdField.text.isNullOrBlank()) {
                runIdField.text = defaultRunId()
            }
            if (editable && atField.text.isNullOrBlank()) {
                atField.text = Instant.now().toString()
            }
            if (editable && summaryArea.text.isNullOrBlank()) {
                summaryArea.text = SpecCodingBundle.message("spec.toolwindow.tasks.verification.summary.default", taskId)
            }
            runIdField.isEnabled = editable
            atField.isEnabled = editable
            summaryArea.isEnabled = editable
        }

        private fun defaultRunId(): String {
            val slug = taskId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return "manual-$slug"
        }

        private fun createFieldRow(labelText: String, field: JComponent): JComponent {
            return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(JBLabel(labelText).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
                }, BorderLayout.NORTH)
                add(field, BorderLayout.CENTER)
            }
        }

        private enum class VerificationDialogOption(
            val displayName: String,
            val conclusion: VerificationConclusion?,
        ) {
            NONE(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.none"), null),
            PASS(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.pass"), VerificationConclusion.PASS),
            WARN(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.warn"), VerificationConclusion.WARN),
            FAIL(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.fail"), VerificationConclusion.FAIL),
            ;

            companion object {
                fun from(conclusion: VerificationConclusion?): VerificationDialogOption {
                    return entries.firstOrNull { option -> option.conclusion == conclusion } ?: NONE
                }
            }
        }
    }

    private data class TaskExecutionPresentation(
        val text: String,
        val tooltip: String,
        val enabled: Boolean,
    )

    companion object {
        private val HEADER_FG = JBColor(Color(35, 40, 47), Color(222, 226, 232))
        private val HEADER_SECONDARY_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val REFRESHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val ACTION_BUTTON_BG = JBColor(Color(236, 244, 255), Color(66, 72, 84))
        private val ACTION_BUTTON_BORDER = JBColor(Color(176, 194, 222), Color(104, 116, 134))
        private val ACTION_BUTTON_FG = JBColor(Color(42, 66, 104), Color(205, 217, 236))
        private val APPLY_STATUS_ICON: Icon =
            IconLoader.getIcon("/icons/spec-task-status-apply.svg", SpecWorkflowTasksPanel::class.java)
        private val EDIT_DEPENDS_ON_ICON: Icon =
            IconLoader.getIcon("/icons/spec-task-depends-edit.svg", SpecWorkflowTasksPanel::class.java)
        private val EDIT_RELATED_FILES_ICON: Icon =
            IconLoader.getIcon("/icons/spec-task-related-files-edit.svg", SpecWorkflowTasksPanel::class.java)
    }
}
