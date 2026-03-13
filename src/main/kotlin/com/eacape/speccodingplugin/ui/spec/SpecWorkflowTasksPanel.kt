package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal class SpecWorkflowTasksPanel(
    private val onTransitionStatus: (taskId: String, to: TaskStatus) -> Unit = { _, _ -> },
    private val onExecuteTask: (taskId: String, retry: Boolean) -> Unit = { _, _ -> },
    private val onOpenWorkflowChat: (workflowId: String, taskId: String) -> Unit = { _, _ -> },
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

    private val executeTaskButton = createIconActionButton {
        handleExecuteTask()
    }
    private val openWorkflowChatButton = createIconActionButton {
        handleOpenWorkflowChat()
    }
    private val secondaryActionsButton = createIconActionButton {
        showSecondaryActionsMenu()
    }
    private val editDependsOnButton = createIconActionButton {
        handleEditDependsOn()
    }
    private val editRelatedFilesButton = createIconActionButton {
        handleEditRelatedFiles()
    }
    private val editVerificationResultButton = createIconActionButton {
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
            "${task.id}:${task.displayStatus.name}:${task.priority.name}"
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
            "executeText" to executeTaskButton.text.orEmpty(),
            "executeIconId" to SpecWorkflowIcons.debugId(executeTaskButton.icon),
            "executeHasIcon" to (executeTaskButton.icon != null).toString(),
            "executeRolloverEnabled" to executeTaskButton.isRolloverEnabled.toString(),
            "executeFocusable" to executeTaskButton.isFocusable.toString(),
            "executeEnabled" to executeTaskButton.isEnabled.toString(),
            "executeTooltip" to executeTaskButton.toolTipText.orEmpty(),
            "executeAccessibleName" to executeTaskButton.accessibleContext.accessibleName.orEmpty(),
            "executeAccessibleDescription" to executeTaskButton.accessibleContext.accessibleDescription.orEmpty(),
            "chatText" to openWorkflowChatButton.text.orEmpty(),
            "chatIconId" to SpecWorkflowIcons.debugId(openWorkflowChatButton.icon),
            "chatHasIcon" to (openWorkflowChatButton.icon != null).toString(),
            "chatRolloverEnabled" to openWorkflowChatButton.isRolloverEnabled.toString(),
            "chatFocusable" to openWorkflowChatButton.isFocusable.toString(),
            "chatEnabled" to openWorkflowChatButton.isEnabled.toString(),
            "chatTooltip" to openWorkflowChatButton.toolTipText.orEmpty(),
            "chatAccessibleName" to openWorkflowChatButton.accessibleContext.accessibleName.orEmpty(),
            "chatAccessibleDescription" to openWorkflowChatButton.accessibleContext.accessibleDescription.orEmpty(),
            "secondaryText" to secondaryActionsButton.text.orEmpty(),
            "secondaryIconId" to SpecWorkflowIcons.debugId(secondaryActionsButton.icon),
            "secondaryTooltip" to secondaryActionsButton.toolTipText.orEmpty(),
            "secondaryHasIcon" to (secondaryActionsButton.icon != null).toString(),
            "secondaryRolloverEnabled" to secondaryActionsButton.isRolloverEnabled.toString(),
            "secondaryFocusable" to secondaryActionsButton.isFocusable.toString(),
            "secondaryEnabled" to secondaryActionsButton.isEnabled.toString(),
            "secondaryAccessibleName" to secondaryActionsButton.accessibleContext.accessibleName.orEmpty(),
            "secondaryAccessibleDescription" to secondaryActionsButton.accessibleContext.accessibleDescription.orEmpty(),
            "dependsOnText" to editDependsOnButton.text.orEmpty(),
            "dependsOnIconId" to SpecWorkflowIcons.debugId(editDependsOnButton.icon),
            "dependsOnTooltip" to editDependsOnButton.toolTipText.orEmpty(),
            "dependsOnHasIcon" to (editDependsOnButton.icon != null).toString(),
            "dependsOnRolloverEnabled" to editDependsOnButton.isRolloverEnabled.toString(),
            "dependsOnFocusable" to editDependsOnButton.isFocusable.toString(),
            "dependsOnAccessibleName" to editDependsOnButton.accessibleContext.accessibleName.orEmpty(),
            "dependsOnAccessibleDescription" to editDependsOnButton.accessibleContext.accessibleDescription.orEmpty(),
            "relatedFilesText" to editRelatedFilesButton.text.orEmpty(),
            "relatedFilesIconId" to SpecWorkflowIcons.debugId(editRelatedFilesButton.icon),
            "relatedFilesTooltip" to editRelatedFilesButton.toolTipText.orEmpty(),
            "relatedFilesHasIcon" to (editRelatedFilesButton.icon != null).toString(),
            "relatedFilesRolloverEnabled" to editRelatedFilesButton.isRolloverEnabled.toString(),
            "relatedFilesFocusable" to editRelatedFilesButton.isFocusable.toString(),
            "relatedFilesAccessibleName" to editRelatedFilesButton.accessibleContext.accessibleName.orEmpty(),
            "relatedFilesAccessibleDescription" to editRelatedFilesButton.accessibleContext.accessibleDescription.orEmpty(),
            "verificationText" to editVerificationResultButton.text.orEmpty(),
            "verificationIconId" to SpecWorkflowIcons.debugId(editVerificationResultButton.icon),
            "verificationHasIcon" to (editVerificationResultButton.icon != null).toString(),
            "verificationRolloverEnabled" to editVerificationResultButton.isRolloverEnabled.toString(),
            "verificationFocusable" to editVerificationResultButton.isFocusable.toString(),
            "verificationEnabled" to editVerificationResultButton.isEnabled.toString(),
            "verificationTooltip" to editVerificationResultButton.toolTipText.orEmpty(),
            "verificationAccessibleName" to editVerificationResultButton.accessibleContext.accessibleName.orEmpty(),
            "verificationAccessibleDescription" to editVerificationResultButton.accessibleContext.accessibleDescription.orEmpty(),
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

    internal fun clickExecuteTaskForTest() {
        executeTaskButton.doClick()
    }

    internal fun clickOpenWorkflowChatForTest() {
        openWorkflowChatButton.doClick()
    }

    internal fun triggerSecondaryActionForTest(targetStatus: TaskStatus): Boolean {
        val selectedTask = tasksList.selectedValue ?: return false
        val action = buildSecondaryActions(selectedTask).firstOrNull { it.targetStatus == targetStatus } ?: return false
        onTransitionStatus(selectedTask.id, action.targetStatus)
        return true
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
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(executeTaskButton)
            add(openWorkflowChatButton)
            add(secondaryActionsButton)
            add(editVerificationResultButton)
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

    private fun applyActionButtonPresentation() {
        updateExecuteButtonPresentation(tasksList.selectedValue)
        updateOpenWorkflowChatButtonPresentation(tasksList.selectedValue)
        updateSecondaryActionsButtonPresentation(tasksList.selectedValue)
        updateVerificationButtonPresentation(tasksList.selectedValue)
        updateDependsOnButtonPresentation(tasksList.selectedValue)
        updateRelatedFilesButtonPresentation(tasksList.selectedValue)
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
            updateExecuteButtonPresentation(null)
            updateOpenWorkflowChatButtonPresentation(null)
            updateSecondaryActionsButtonPresentation(null)
            updateVerificationButtonPresentation(null)
            updateDependsOnButtonPresentation(null)
            updateRelatedFilesButtonPresentation(null)
            return
        }

        updateExecuteButtonPresentation(selected)
        updateOpenWorkflowChatButtonPresentation(selected)
        updateSecondaryActionsButtonPresentation(selected)
        updateVerificationButtonPresentation(selected)
        updateDependsOnButtonPresentation(selected)
        updateRelatedFilesButtonPresentation(selected)
    }

    private fun updateExecuteButtonPresentation(selected: StructuredTask?) {
        val taskId = selected?.id.orEmpty()
        val presentation = when (selected?.displayStatus) {
            null -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.PENDING),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.unavailable"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.none"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.unavailable"),
            )

            TaskStatus.PENDING -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.PENDING),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start"),
                enabled = true,
            )

            TaskStatus.BLOCKED -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.BLOCKED),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume"),
                enabled = true,
            )

            TaskStatus.IN_PROGRESS -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.IN_PROGRESS),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete"),
                enabled = true,
            )

            TaskStatus.COMPLETED -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.COMPLETED),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done.tooltip", taskId),
            )

            TaskStatus.CANCELLED -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.CANCELLED),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled.tooltip", taskId),
            )
        }
        SpecUiStyle.applyIconActionPresentation(executeTaskButton, presentation)
    }

    private fun updateOpenWorkflowChatButtonPresentation(selected: StructuredTask?) {
        val workflowId = currentWorkflowId?.trim().orEmpty()
        val taskId = selected?.id.orEmpty()
        val disabledReason = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.disabled")
            workflowId.isBlank() -> SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.disabled.noWorkflow")
            else -> null
        }
        SpecUiStyle.applyIconActionPresentation(
            button = openWorkflowChatButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.OpenToolWindow,
                tooltip = disabledReason ?: SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.chat.open"),
                enabled = disabledReason == null,
                disabledReason = disabledReason,
            ),
        )
    }

    private fun updateVerificationButtonPresentation(selected: StructuredTask?) {
        val hasVerification = selected?.verificationResult != null
        val taskId = selected?.id.orEmpty()
        val presentation = when {
            selected == null -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Add,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.unavailable"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.verification.button"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.verification.unavailable"),
            )

            selected.status == TaskStatus.CANCELLED -> SpecIconActionPresentation(
                icon = if (hasVerification) SpecWorkflowIcons.Edit else SpecWorkflowIcons.Add,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit.tooltip", taskId),
                accessibleName = if (hasVerification) {
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit")
                } else {
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.button")
                },
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.verification.cancelled", taskId),
            )

            hasVerification -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Edit,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit"),
                enabled = true,
            )

            else -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Add,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.record.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.verification.button"),
                enabled = true,
            )
        }
        SpecUiStyle.applyIconActionPresentation(editVerificationResultButton, presentation)
    }

    private fun updateSecondaryActionsButtonPresentation(selected: StructuredTask?) {
        val taskId = selected?.id.orEmpty()
        val actions = selected?.let(::buildSecondaryActions).orEmpty()
        val disabledReason = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.secondary.unavailable")
            actions.isEmpty() && selected.displayStatus == TaskStatus.IN_PROGRESS -> {
                SpecCodingBundle.message("spec.toolwindow.tasks.secondary.inProgress", taskId)
            }
            actions.isEmpty() -> SpecCodingBundle.message("spec.toolwindow.tasks.secondary.none", taskId)
            else -> null
        }
        val tooltip = if (disabledReason != null) {
            disabledReason
        } else {
            SpecCodingBundle.message("spec.toolwindow.tasks.secondary.tooltip", taskId)
        }
        SpecUiStyle.applyIconActionPresentation(
            button = secondaryActionsButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Overflow,
                tooltip = tooltip,
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.button"),
                enabled = actions.isNotEmpty(),
                disabledReason = disabledReason,
            ),
        )
    }

    private fun updateDependsOnButtonPresentation(selected: StructuredTask?) {
        val taskId = selected?.id.orEmpty()
        val disabledReason = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.unavailable")
            selected.status == TaskStatus.COMPLETED || selected.status == TaskStatus.CANCELLED -> {
                SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.locked", taskId)
            }
            else -> null
        }
        SpecUiStyle.applyIconActionPresentation(
            button = editDependsOnButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Edit,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"),
                enabled = disabledReason == null,
                disabledReason = disabledReason,
            ),
        )
    }

    private fun updateRelatedFilesButtonPresentation(selected: StructuredTask?) {
        val disabledReason = if (selected == null) {
            SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.unavailable")
        } else {
            null
        }
        SpecUiStyle.applyIconActionPresentation(
            button = editRelatedFilesButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Edit,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.edit"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.edit"),
                enabled = disabledReason == null,
                disabledReason = disabledReason,
            ),
        )
    }

    private fun handleExecuteTask() {
        requestExecutionForSelection()
    }

    private fun handleOpenWorkflowChat() {
        val selectedTask = tasksList.selectedValue ?: return
        val workflowId = currentWorkflowId?.trim()?.ifBlank { null } ?: return
        onOpenWorkflowChat(workflowId, selectedTask.id)
    }

    private fun requestExecutionForSelection(): Boolean {
        val selectedTask = tasksList.selectedValue ?: return false
        when (selectedTask.displayStatus) {
            TaskStatus.PENDING,
            -> onExecuteTask(selectedTask.id, false)

            TaskStatus.BLOCKED,
            -> onExecuteTask(selectedTask.id, true)

            TaskStatus.IN_PROGRESS -> requestCompletionWithRelatedFiles(selectedTask)
            else -> return false
        }
        return true
    }

    private fun showSecondaryActionsMenu() {
        val selectedTask = tasksList.selectedValue ?: return
        val actions = buildSecondaryActions(selectedTask)
        if (actions.isEmpty()) {
            return
        }
        val menu = JPopupMenu()
        actions.forEach { action ->
            menu.add(
                JMenuItem(action.label).apply {
                    icon = action.icon
                    accessibleContext.accessibleName = action.label
                    accessibleContext.accessibleDescription = action.label
                    addActionListener { onTransitionStatus(selectedTask.id, action.targetStatus) }
                },
            )
        }
        menu.show(secondaryActionsButton, 0, secondaryActionsButton.height)
    }

    private fun requestCompletionWithRelatedFiles(selectedTask: StructuredTask) {
        val taskId = selectedTask.id
        val existing = selectedTask.relatedFiles
        val previousCursor = cursor
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        SpecUiStyle.setIconActionEnabled(
            executeTaskButton,
            enabled = false,
            disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
        )
        SpecUiStyle.setIconActionEnabled(
            secondaryActionsButton,
            enabled = false,
            disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
        )

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

    private fun buildSecondaryActions(task: StructuredTask): List<TaskSecondaryAction> {
        if (task.displayStatus == TaskStatus.IN_PROGRESS) {
            return emptyList()
        }
        return when (task.status) {
            TaskStatus.PENDING -> listOf(
                TaskSecondaryAction(
                    targetStatus = TaskStatus.BLOCKED,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.block", task.id),
                    icon = SpecWorkflowIcons.Pause,
                ),
                TaskSecondaryAction(
                    targetStatus = TaskStatus.CANCELLED,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.cancel", task.id),
                    icon = SpecWorkflowIcons.Close,
                ),
            )

            TaskStatus.BLOCKED -> listOf(
                TaskSecondaryAction(
                    targetStatus = TaskStatus.PENDING,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.reopen", task.id),
                    icon = SpecWorkflowIcons.Back,
                ),
                TaskSecondaryAction(
                    targetStatus = TaskStatus.CANCELLED,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.cancel", task.id),
                    icon = SpecWorkflowIcons.Close,
                ),
            )

            TaskStatus.IN_PROGRESS,
            TaskStatus.COMPLETED,
            TaskStatus.CANCELLED,
            -> emptyList()
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
            statusChipLabel.text = value.displayStatus.name
            applyChipStyle(statusChipLabel, value.displayStatus, isSelected)
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

    private data class TaskSecondaryAction(
        val targetStatus: TaskStatus,
        val label: String,
        val icon: javax.swing.Icon,
    )

    companion object {
        private val HEADER_FG = JBColor(Color(35, 40, 47), Color(222, 226, 232))
        private val HEADER_SECONDARY_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val REFRESHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
