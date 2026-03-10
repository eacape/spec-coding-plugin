package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SpecWorkflowOverviewPanel : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val emptyLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.TOP
        foreground = EMPTY_TEXT_FG
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(4)
    }

    private val contentPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 2)
    }

    private val workflowValueLabel = createValueLabel()
    private val statusValueLabel = createValueLabel()
    private val currentStageValueLabel = createValueLabel()
    private val activeStagesValueLabel = createValueLabel()
    private val nextStageValueLabel = createValueLabel()
    private val refreshedValueLabel = createValueLabel()
    private val gateSummaryValueLabel = createValueLabel()
    private val gateChipLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder()
        isOpaque = true
        font = JBUI.Fonts.smallFont().deriveFont(10.5f)
    }

    private val workflowKeyLabel = createKeyLabel()
    private val statusKeyLabel = createKeyLabel()
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
        workflowKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.workflow")
        statusKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.status")
        currentStageKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.currentStage")
        activeStagesKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.activeStages")
        nextStageKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.nextStage")
        advanceGateKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.advanceGate")
        refreshedKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.refreshed")
        if (currentState != null) {
            renderCurrentState()
        } else {
            renderEmptyState()
        }
    }

    internal fun snapshotForTest(): Map<String, String> {
        return mapOf(
            "workflow" to workflowValueLabel.text.orEmpty(),
            "status" to statusValueLabel.text.orEmpty(),
            "currentStage" to currentStageValueLabel.text.orEmpty(),
            "activeStages" to activeStagesValueLabel.text.orEmpty(),
            "nextStage" to nextStageValueLabel.text.orEmpty(),
            "gateStatus" to gateChipLabel.text.orEmpty(),
            "gateSummary" to gateSummaryValueLabel.text.orEmpty(),
            "refreshed" to refreshedValueLabel.text.orEmpty(),
            "empty" to emptyLabel.text.orEmpty(),
        )
    }

    private fun buildContent() {
        var row = 0
        row = addRow(row, workflowKeyLabel, workflowValueLabel)
        row = addRow(row, statusKeyLabel, statusValueLabel)
        row = addRow(row, currentStageKeyLabel, currentStageValueLabel)
        row = addRow(row, activeStagesKeyLabel, activeStagesValueLabel)
        row = addRow(row, nextStageKeyLabel, nextStageValueLabel)
        row = addRow(
            row,
            advanceGateKeyLabel,
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(gateChipLabel)
            },
        )
        row = addRow(row, createSpacerLabel(), gateSummaryValueLabel)
        addRow(row, refreshedKeyLabel, refreshedValueLabel, fillVertical = true)
    }

    private fun addRow(
        row: Int,
        keyLabel: JLabel,
        valueComponent: java.awt.Component,
        fillVertical: Boolean = false,
    ): Int {
        contentPanel.add(
            keyLabel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(8), JBUI.scale(12))
            },
        )
        contentPanel.add(
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

        workflowValueLabel.text = state.title.takeIf { it.isNotBlank() }?.let { title ->
            if (title == state.workflowId) state.workflowId else "$title · ${state.workflowId}"
        } ?: state.workflowId
        statusValueLabel.text = workflowStatusText(state.status)
        currentStageValueLabel.text = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage)
        activeStagesValueLabel.text = state.activeStages.joinToString(" → ") { stage ->
            SpecWorkflowOverviewPresenter.stageLabel(stage)
        }
        nextStageValueLabel.text = state.nextStage?.let(SpecWorkflowOverviewPresenter::stageLabel)
            ?: SpecCodingBundle.message("spec.toolwindow.overview.nextStage.none")
        gateChipLabel.text = gateStatusText(state.gateStatus)
        applyGateChipStyle(state.gateStatus)
        gateSummaryValueLabel.text = state.gateSummary
            ?: SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.unavailable")
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
        revalidate()
        repaint()
    }

    private fun createKeyLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = KEY_TEXT_FG
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createSpacerLabel(): JBLabel {
        return createKeyLabel().apply { text = "" }
    }

    private fun createValueLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = VALUE_TEXT_FG
            verticalAlignment = SwingConstants.TOP
        }
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
        val (background, foreground, border) = when (status) {
            GateStatus.PASS -> Triple(PASS_BG, PASS_FG, PASS_BORDER)
            GateStatus.WARNING -> Triple(WARN_BG, WARN_FG, WARN_BORDER)
            GateStatus.ERROR -> Triple(ERROR_BG, ERROR_FG, ERROR_BORDER)
            null -> Triple(NEUTRAL_BG, NEUTRAL_FG, NEUTRAL_BORDER)
        }
        gateChipLabel.background = background
        gateChipLabel.foreground = foreground
        gateChipLabel.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
            JBUI.Borders.empty(2, 8, 2, 8),
        )
    }

    companion object {
        private val REFRESHED_AT_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val KEY_TEXT_FG = JBColor(Color(110, 118, 131), Color(140, 149, 163))
        private val VALUE_TEXT_FG = JBColor(Color(35, 39, 47), Color(216, 222, 233))
        private val EMPTY_TEXT_FG = JBColor(Color(120, 127, 138), Color(132, 141, 153))

        private val PASS_BG = JBColor(Color(233, 246, 237), Color(54, 85, 63))
        private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))
        private val PASS_BORDER = JBColor(Color(154, 204, 170), Color(87, 134, 102))

        private val WARN_BG = JBColor(Color(255, 244, 229), Color(97, 77, 42))
        private val WARN_FG = JBColor(Color(150, 96, 0), Color(245, 212, 152))
        private val WARN_BORDER = JBColor(Color(232, 191, 111), Color(138, 112, 63))

        private val ERROR_BG = JBColor(Color(252, 235, 236), Color(97, 54, 58))
        private val ERROR_FG = JBColor(Color(166, 53, 60), Color(250, 196, 200))
        private val ERROR_BORDER = JBColor(Color(226, 140, 147), Color(145, 92, 98))

        private val NEUTRAL_BG = JBColor(Color(241, 244, 248), Color(72, 77, 85))
        private val NEUTRAL_FG = JBColor(Color(85, 94, 106), Color(192, 199, 208))
        private val NEUTRAL_BORDER = JBColor(Color(201, 208, 217), Color(98, 108, 121))
    }
}
