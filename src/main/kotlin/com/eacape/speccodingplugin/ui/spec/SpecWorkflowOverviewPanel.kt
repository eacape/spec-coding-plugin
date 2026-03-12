package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SpecWorkflowOverviewPanel(
    onAdvanceRequested: () -> Unit = {},
    onJumpRequested: () -> Unit = {},
    onRollbackRequested: () -> Unit = {},
    onTemplateSwitchRequested: (WorkflowTemplate) -> Unit = {},
    onTemplateRollbackRequested: (TemplateSwitchHistoryEntry) -> Unit = {},
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val emptyLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.TOP
        foreground = EMPTY_TEXT_FG
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(4)
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 2, 2)
    }

    private val focusTitleLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13.5f)
        foreground = VALUE_TEXT_FG
    }
    private val focusSummaryLabel = createBodyLabel(FOCUS_SUMMARY_FG)
    private val checklistTitleLabel = createSectionTitleLabel()
    private val checklistLabels = List(4) { createChecklistLabel() }
    private val stageFlowTitleLabel = createSectionTitleLabel()
    private val detailsTitleLabel = createSectionTitleLabel()

    private val workflowValueLabel = createValueLabel()
    private val statusValueLabel = createValueLabel()
    private val templateValueLabel = createValueLabel()
    private val templateHistoryValueLabel = createBodyLabel(VALUE_SECONDARY_FG)
    private val currentStageValueLabel = createValueLabel()
    private val activeStagesValueLabel = createValueLabel()
    private val nextStageValueLabel = createValueLabel()
    private val refreshedValueLabel = createValueLabel()
    private val gateSummaryValueLabel = createBodyLabel(VALUE_SECONDARY_FG)
    private val gateChipLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.empty()
        isOpaque = false
        alignmentY = Component.TOP_ALIGNMENT
        font = JBUI.Fonts.smallFont()
    }
    private val gateStatusSummaryRow = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(gateChipLabel)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(gateSummaryValueLabel.apply {
            alignmentY = Component.TOP_ALIGNMENT
        })
        add(Box.createHorizontalGlue())
    }
    private val gateStatusSummaryContainer = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(gateStatusSummaryRow, BorderLayout.CENTER)
    }

    private val templateTargetComboBox = ComboBox<WorkflowTemplate>().apply {
        isFocusable = false
        renderer = SimpleListCellRenderer.create<WorkflowTemplate> { label, value, _ ->
            label.text = value?.let(SpecWorkflowOverviewPresenter::templateLabel)
                ?: SpecCodingBundle.message("spec.toolwindow.overview.template.target.none")
        }
    }
    private val templateSwitchButton = createActionButton {
        val targetTemplate = templateTargetComboBox.selectedItem as? WorkflowTemplate ?: return@createActionButton
        onTemplateSwitchRequested(targetTemplate)
    }
    private val templateRollbackButton = createActionButton {
        val historyEntry = currentState?.latestTemplateSwitch ?: return@createActionButton
        onTemplateRollbackRequested(historyEntry)
    }
    private val templateValuePanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        isOpaque = false
        add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(templateValueLabel)
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(templateHistoryValueLabel)
            },
            BorderLayout.NORTH,
        )
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(templateTargetComboBox)
                add(templateSwitchButton)
                add(templateRollbackButton)
            },
            BorderLayout.CENTER,
        )
    }
    private val stageStepperPanel = SpecWorkflowStageStepperPanel(
        onAdvanceRequested = onAdvanceRequested,
        onJumpRequested = onJumpRequested,
        onRollbackRequested = onRollbackRequested,
    )

    private val workflowKeyLabel = createKeyLabel()
    private val statusKeyLabel = createKeyLabel()
    private val templateKeyLabel = createKeyLabel()
    private val currentStageKeyLabel = createKeyLabel()
    private val activeStagesKeyLabel = createKeyLabel()
    private val nextStageKeyLabel = createKeyLabel()
    private val advanceGateKeyLabel = createKeyLabel()
    private val refreshedKeyLabel = createKeyLabel()

    private var currentState: SpecWorkflowOverviewState? = null
    private var emptyMessageKey: String = "spec.toolwindow.overview.empty"

    init {
        isOpaque = false
        buildContent()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = templateTargetComboBox,
            minWidth = JBUI.scale(96),
            maxWidth = JBUI.scale(240),
            height = templateTargetComboBox.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(28),
        )
        add(emptyLabel, BorderLayout.CENTER)
        add(contentPanel, BorderLayout.NORTH)
        refreshLocalizedTexts()
        showEmpty()
    }

    fun updateOverview(state: SpecWorkflowOverviewState) {
        currentState = state
        emptyMessageKey = "spec.toolwindow.overview.empty"
        renderCurrentState()
    }

    fun showEmpty() {
        currentState = null
        emptyMessageKey = "spec.toolwindow.overview.empty"
        renderEmptyState()
    }

    fun showLoading() {
        currentState = null
        emptyMessageKey = "spec.toolwindow.overview.loading"
        renderEmptyState()
    }

    fun refreshLocalizedTexts() {
        workflowKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.workflow")
        statusKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.status")
        templateKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.template")
        currentStageKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.currentStage")
        activeStagesKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.activeStages")
        nextStageKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.next")
        advanceGateKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.advanceGate")
        refreshedKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.refreshed")
        checklistTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.checklist.title")
        stageFlowTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.stageFlow.title")
        detailsTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.title")
        applyTemplateButtonPresentation()
        stageStepperPanel.refreshLocalizedTexts()
        if (currentState != null) {
            renderCurrentState()
        } else {
            renderEmptyState()
        }
    }

    internal fun snapshotForTest(): Map<String, String> {
        val stageStepperSnapshot = stageStepperPanel.snapshotForTest()
        val checklist = checklistLabels
            .mapNotNull { label -> label.text?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" | ")
        return mapOf(
            "workflow" to workflowValueLabel.text.orEmpty(),
            "status" to statusValueLabel.text.orEmpty(),
            "template" to templateValueLabel.text.orEmpty(),
            "templateHistory" to templateHistoryValueLabel.text.orEmpty(),
            "templateSwitchEnabled" to templateSwitchButton.isEnabled.toString(),
            "templateSwitchText" to templateSwitchButton.text.orEmpty(),
            "templateSwitchTooltip" to templateSwitchButton.toolTipText.orEmpty(),
            "templateSwitchHasIcon" to (templateSwitchButton.icon != null).toString(),
            "templateSwitchRolloverEnabled" to templateSwitchButton.isRolloverEnabled.toString(),
            "templateRollbackEnabled" to templateRollbackButton.isEnabled.toString(),
            "templateRollbackText" to templateRollbackButton.text.orEmpty(),
            "templateRollbackTooltip" to templateRollbackButton.toolTipText.orEmpty(),
            "templateRollbackHasIcon" to (templateRollbackButton.icon != null).toString(),
            "templateRollbackRolloverEnabled" to templateRollbackButton.isRolloverEnabled.toString(),
            "currentStage" to currentStageValueLabel.text.orEmpty(),
            "activeStages" to activeStagesValueLabel.text.orEmpty(),
            "nextStage" to nextStageValueLabel.text.orEmpty(),
            "gateStatus" to gateChipLabel.text.orEmpty(),
            "gateStatusOpaque" to gateChipLabel.isOpaque.toString(),
            "gateStatusInsets" to gateChipLabel.border.getBorderInsets(gateChipLabel).let { insets ->
                "${insets.top},${insets.left},${insets.bottom},${insets.right}"
            },
            "gateContainerInsets" to gateStatusSummaryContainer.border.getBorderInsets(gateStatusSummaryContainer).let { insets ->
                "${insets.top},${insets.left},${insets.bottom},${insets.right}"
            },
            "gateStatusFont" to gateChipLabel.font.let { font -> "${font.size2D}:${font.style}" },
            "gateSummaryFont" to gateSummaryValueLabel.font.let { font -> "${font.size2D}:${font.style}" },
            "gateStatusBeforeSummary" to (
                gateStatusSummaryRow.components.indexOf(gateChipLabel) <
                    gateStatusSummaryRow.components.indexOf(gateSummaryValueLabel)
                ).toString(),
            "gateSummary" to gateSummaryValueLabel.text.orEmpty(),
            "stageFlow" to stageStepperSnapshot.getValue("stages"),
            "advanceEnabled" to stageStepperSnapshot.getValue("advanceEnabled"),
            "jumpEnabled" to stageStepperSnapshot.getValue("jumpEnabled"),
            "rollbackEnabled" to stageStepperSnapshot.getValue("rollbackEnabled"),
            "refreshed" to refreshedValueLabel.text.orEmpty(),
            "focusTitle" to focusTitleLabel.text.orEmpty(),
            "focusSummary" to focusSummaryLabel.text.orEmpty(),
            "checklist" to checklist,
            "empty" to emptyLabel.text.orEmpty(),
        )
    }

    internal fun clickAdvanceForTest() {
        stageStepperPanel.clickAdvanceForTest()
    }

    private fun buildContent() {
        contentPanel.add(createCard(buildFocusCard()))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(createCard(buildChecklistCard()))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(createCard(buildStageFlowCard()))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(createCard(buildDetailsCard()))
    }

    private fun buildFocusCard(): JPanel {
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(focusTitleLabel, BorderLayout.NORTH)
            add(focusSummaryLabel, BorderLayout.CENTER)
        }
    }

    private fun buildChecklistCard(): JPanel {
        val checklistBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            checklistLabels.forEachIndexed { index, label ->
                if (index > 0) {
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                }
                add(label)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(checklistTitleLabel, BorderLayout.NORTH)
            add(checklistBody, BorderLayout.CENTER)
        }
    }

    private fun buildStageFlowCard(): JPanel {
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(stageFlowTitleLabel, BorderLayout.NORTH)
            add(stageStepperPanel, BorderLayout.CENTER)
        }
    }

    private fun buildDetailsCard(): JPanel {
        val grid = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        var row = 0
        row = addDetailRow(grid, row, workflowKeyLabel, workflowValueLabel)
        row = addDetailRow(grid, row, statusKeyLabel, statusValueLabel)
        row = addDetailRow(grid, row, currentStageKeyLabel, currentStageValueLabel)
        row = addDetailRow(grid, row, templateKeyLabel, templateValuePanel)
        row = addDetailRow(grid, row, activeStagesKeyLabel, activeStagesValueLabel)
        row = addDetailRow(grid, row, nextStageKeyLabel, nextStageValueLabel)
        row = addDetailRow(grid, row, advanceGateKeyLabel, gateStatusSummaryContainer)
        addDetailRow(grid, row, refreshedKeyLabel, refreshedValueLabel, fillVertical = true)
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(detailsTitleLabel, BorderLayout.NORTH)
            add(grid, BorderLayout.CENTER)
        }
    }

    private fun addDetailRow(
        panel: JPanel,
        row: Int,
        keyLabel: JLabel,
        valueComponent: Component,
        fillVertical: Boolean = false,
    ): Int {
        panel.add(
            keyLabel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(8), JBUI.scale(12))
            },
        )
        panel.add(
            valueComponent,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                weighty = if (fillVertical) 1.0 else 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(8), 0)
            },
        )
        return row + 1
    }

    private fun renderCurrentState() {
        val state = currentState ?: return
        emptyLabel.isVisible = false
        contentPanel.isVisible = true

        val guidance = SpecWorkflowStageGuidanceBuilder.build(state)
        focusTitleLabel.text = guidance.headline
        focusSummaryLabel.text = guidance.summary
        updateChecklist(guidance.checklist)

        workflowValueLabel.text = state.title.takeIf { it.isNotBlank() }?.let { title ->
            if (title == state.workflowId) {
                state.workflowId
            } else {
                "$title | ${state.workflowId}"
            }
        } ?: state.workflowId
        statusValueLabel.text = workflowStatusText(state.status)
        templateValueLabel.text = SpecCodingBundle.message(
            "spec.toolwindow.overview.template.current",
            SpecWorkflowOverviewPresenter.templateLabel(state.template),
        )
        templateHistoryValueLabel.text = state.latestTemplateSwitch?.let { latestSwitch ->
            SpecCodingBundle.message(
                "spec.toolwindow.overview.template.history.last",
                SpecWorkflowOverviewPresenter.templateLabel(latestSwitch.fromTemplate),
                SpecWorkflowOverviewPresenter.templateLabel(latestSwitch.toTemplate),
            )
        } ?: SpecCodingBundle.message("spec.toolwindow.overview.template.history.none")
        updateTemplateTargets(state.switchableTemplates)
        templateSwitchButton.isEnabled = state.switchableTemplates.isNotEmpty()
        templateRollbackButton.isEnabled = state.latestTemplateSwitch != null
        currentStageValueLabel.text = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage)
        activeStagesValueLabel.text = state.activeStages.joinToString(" -> ") { stage ->
            SpecWorkflowOverviewPresenter.stageLabel(stage)
        }
        nextStageValueLabel.text = state.nextStage?.let(SpecWorkflowOverviewPresenter::stageLabel)
            ?: SpecCodingBundle.message("spec.toolwindow.overview.nextStage.none")
        gateChipLabel.text = gateStatusText(state.gateStatus)
        applyGateChipStyle(state.gateStatus)
        gateSummaryValueLabel.text = state.gateSummary
            ?: SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.unavailable")
        stageStepperPanel.updateState(state.stageStepper)
        refreshedValueLabel.text = REFRESHED_AT_FORMATTER.format(
            Instant.ofEpochMilli(state.refreshedAtMillis).atZone(ZoneId.systemDefault()),
        )
        revalidate()
        repaint()
    }

    private fun renderEmptyState() {
        contentPanel.isVisible = false
        emptyLabel.isVisible = true
        emptyLabel.text = SpecCodingBundle.message(emptyMessageKey)
        focusTitleLabel.text = ""
        focusSummaryLabel.text = ""
        updateChecklist(emptyList())
        workflowValueLabel.text = ""
        statusValueLabel.text = ""
        templateValueLabel.text = ""
        templateHistoryValueLabel.text = ""
        currentStageValueLabel.text = ""
        activeStagesValueLabel.text = ""
        nextStageValueLabel.text = ""
        gateChipLabel.text = ""
        applyGateChipStyle(null)
        gateSummaryValueLabel.text = ""
        refreshedValueLabel.text = ""
        updateTemplateTargets(emptyList())
        templateSwitchButton.isEnabled = false
        templateRollbackButton.isEnabled = false
        stageStepperPanel.clear()
        revalidate()
        repaint()
    }

    private fun updateChecklist(lines: List<String>) {
        checklistLabels.forEachIndexed { index, label ->
            val value = lines.getOrNull(index).orEmpty()
            label.text = value
            label.isVisible = value.isNotBlank()
        }
    }

    private fun updateTemplateTargets(templates: List<WorkflowTemplate>) {
        val previousSelection = templateTargetComboBox.selectedItem as? WorkflowTemplate
        templateTargetComboBox.removeAllItems()
        templates.forEach(templateTargetComboBox::addItem)
        if (templates.isEmpty()) {
            templateTargetComboBox.isEnabled = false
            return
        }
        templateTargetComboBox.selectedItem = previousSelection?.takeIf { templates.contains(it) } ?: templates.first()
        templateTargetComboBox.isEnabled = true
    }

    private fun createCard(content: Component): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CARD_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(CARD_BORDER, JBUI.scale(16)),
                JBUI.Borders.empty(10, 12, 10, 12),
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createSectionTitleLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 11.5f)
            foreground = SECTION_TITLE_FG
        }
    }

    private fun createKeyLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = KEY_TEXT_FG
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createValueLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = VALUE_TEXT_FG
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createBodyLabel(foreground: Color): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            this.foreground = foreground
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createChecklistLabel(): JBLabel {
        return createBodyLabel(CHECKLIST_FG).apply {
            border = JBUI.Borders.emptyLeft(2)
        }
    }

    private fun createActionButton(onClick: () -> Unit): JButton {
        return JButton().apply {
            isFocusable = false
            addActionListener { onClick() }
            SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        }
    }

    private fun applyTemplateButtonPresentation() {
        SpecUiStyle.configureIconActionButton(
            button = templateSwitchButton,
            icon = TEMPLATE_SWITCH_ICON,
            tooltip = SpecCodingBundle.message("spec.toolwindow.overview.template.switch"),
        )
        SpecUiStyle.configureIconActionButton(
            button = templateRollbackButton,
            icon = TEMPLATE_ROLLBACK_ICON,
            tooltip = SpecCodingBundle.message("spec.toolwindow.overview.template.rollback"),
        )
    }

    private fun workflowStatusText(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun gateStatusText(status: GateStatus?): String {
        return when (status) {
            GateStatus.PASS -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.pass")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.warning")
            GateStatus.ERROR -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.error")
            null -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.unavailable.short")
        }
    }

    private fun applyGateChipStyle(status: GateStatus?) {
        val foreground = when (status) {
            GateStatus.PASS -> PASS_FG
            GateStatus.WARNING -> WARN_FG
            GateStatus.ERROR -> ERROR_FG
            null -> NEUTRAL_FG
        }
        gateChipLabel.isOpaque = false
        gateChipLabel.foreground = foreground
        gateChipLabel.border = JBUI.Borders.empty()
    }

    companion object {
        private val REFRESHED_AT_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val KEY_TEXT_FG = JBColor(Color(110, 118, 131), Color(140, 149, 163))
        private val VALUE_TEXT_FG = JBColor(Color(35, 39, 47), Color(216, 222, 233))
        private val VALUE_SECONDARY_FG = JBColor(Color(102, 112, 127), Color(165, 175, 190))
        private val EMPTY_TEXT_FG = JBColor(Color(120, 127, 138), Color(132, 141, 153))
        private val SECTION_TITLE_FG = JBColor(Color(62, 80, 118), Color(204, 215, 231))
        private val FOCUS_SUMMARY_FG = JBColor(Color(84, 96, 116), Color(177, 186, 199))
        private val CHECKLIST_FG = JBColor(Color(74, 86, 105), Color(189, 199, 214))
        private val CARD_BG = JBColor(Color(250, 252, 255), Color(55, 61, 71))
        private val CARD_BORDER = JBColor(Color(209, 220, 237), Color(85, 96, 111))
        private val TEMPLATE_SWITCH_ICON: Icon =
            IconLoader.getIcon("/icons/spec-workflow-template-switch.svg", SpecWorkflowOverviewPanel::class.java)
        private val TEMPLATE_ROLLBACK_ICON: Icon =
            IconLoader.getIcon("/icons/spec-workflow-template-rollback.svg", SpecWorkflowOverviewPanel::class.java)

        private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))

        private val WARN_FG = JBColor(Color(150, 96, 0), Color(245, 212, 152))

        private val ERROR_FG = JBColor(Color(166, 53, 60), Color(250, 196, 200))

        private val NEUTRAL_FG = JBColor(Color(85, 94, 106), Color(192, 199, 208))
    }
}
