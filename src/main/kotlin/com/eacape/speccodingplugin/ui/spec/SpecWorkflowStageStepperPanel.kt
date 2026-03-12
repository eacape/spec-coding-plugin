package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageProgress
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SpecWorkflowStageStepperPanel(
    private val onAdvanceRequested: () -> Unit = {},
    private val onJumpRequested: () -> Unit = {},
    private val onRollbackRequested: () -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val stagesPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
    }
    private val advanceButton = JButton().apply {
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { onAdvanceRequested() }
    }
    private val jumpButton = JButton().apply {
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { onJumpRequested() }
    }
    private val rollbackButton = JButton().apply {
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { onRollbackRequested() }
    }
    private val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(advanceButton)
        add(jumpButton)
        add(rollbackButton)
    }

    private var currentState: SpecWorkflowStageStepperState? = null

    init {
        isOpaque = false
        add(stagesPanel, BorderLayout.NORTH)
        add(controlsPanel, BorderLayout.SOUTH)
        refreshLocalizedTexts()
        clear()
    }

    fun updateState(state: SpecWorkflowStageStepperState) {
        currentState = state
        render()
    }

    fun clear() {
        currentState = null
        render()
    }

    fun refreshLocalizedTexts() {
        advanceButton.text = SpecCodingBundle.message("spec.action.advance.text")
        jumpButton.text = SpecCodingBundle.message("spec.action.jump.text")
        rollbackButton.text = SpecCodingBundle.message("spec.action.rollback.text")
        render()
    }

    internal fun snapshotForTest(): Map<String, String> {
        return mapOf(
            "stages" to currentState
                ?.stages
                ?.joinToString(" | ") { stage ->
                    "${SpecWorkflowOverviewPresenter.stageLabel(stage.stageId)}:${statusText(stage)}"
                }
                .orEmpty(),
            "advanceEnabled" to advanceButton.isEnabled.toString(),
            "jumpEnabled" to jumpButton.isEnabled.toString(),
            "rollbackEnabled" to rollbackButton.isEnabled.toString(),
        )
    }

    internal fun clickAdvanceForTest() {
        advanceButton.doClick()
    }

    internal fun clickJumpForTest() {
        jumpButton.doClick()
    }

    internal fun clickRollbackForTest() {
        rollbackButton.doClick()
    }

    private fun render() {
        stagesPanel.removeAll()
        val state = currentState
        if (state == null) {
            advanceButton.isEnabled = false
            jumpButton.isEnabled = false
            rollbackButton.isEnabled = false
            revalidate()
            repaint()
            return
        }

        state.stages.forEachIndexed { index, stage ->
            stagesPanel.add(createStageChip(stage))
            if (index < state.stages.lastIndex) {
                stagesPanel.add(
                    JBLabel("→").apply {
                        foreground = CONNECTOR_FG
                        border = JBUI.Borders.empty(0, 0, 0, 0)
                    },
                )
            }
        }
        advanceButton.isEnabled = state.canAdvance
        jumpButton.isEnabled = state.jumpTargets.isNotEmpty()
        rollbackButton.isEnabled = state.rollbackTargets.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun createStageChip(stage: SpecWorkflowStageStepState): JPanel {
        val (titleForeground, statusForeground) = textPalette(stage)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 2, 0)
            add(
                JBLabel(SpecWorkflowOverviewPresenter.stageLabel(stage.stageId)).apply {
                    foreground = titleForeground
                    horizontalAlignment = SwingConstants.LEFT
                    alignmentX = LEFT_ALIGNMENT
                    font = JBUI.Fonts.smallFont().deriveFont(if (stage.current) java.awt.Font.BOLD else java.awt.Font.PLAIN)
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(
                JBLabel(statusText(stage)).apply {
                    foreground = statusForeground
                    font = JBUI.Fonts.smallFont().deriveFont(10.0f)
                    horizontalAlignment = SwingConstants.LEFT
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }
    }

    private fun statusText(stage: SpecWorkflowStageStepState): String {
        return when {
            !stage.active -> SpecCodingBundle.message("spec.toolwindow.stage.state.inactive")
            stage.progress == StageProgress.DONE -> SpecCodingBundle.message("spec.action.stage.state.done")
            stage.current || stage.progress == StageProgress.IN_PROGRESS ->
                SpecCodingBundle.message("spec.action.stage.state.inProgress")
            else -> SpecCodingBundle.message("spec.action.stage.state.pending")
        }
    }

    private fun textPalette(stage: SpecWorkflowStageStepState): TextPalette {
        return when {
            stage.current -> TextPalette(
                titleForeground = CURRENT_FG,
                statusForeground = CURRENT_FG,
            )

            !stage.active -> TextPalette(
                titleForeground = INACTIVE_FG,
                statusForeground = INACTIVE_FG,
            )

            stage.progress == StageProgress.DONE -> TextPalette(
                titleForeground = DONE_FG,
                statusForeground = DONE_FG,
            )

            else -> TextPalette(
                titleForeground = ACTIVE_FG,
                statusForeground = ACTIVE_FG,
            )
        }
    }

    private data class TextPalette(
        val titleForeground: Color,
        val statusForeground: Color,
    )

    companion object {
        private val CONNECTOR_FG = JBColor(Color(132, 141, 153), Color(122, 130, 142))

        private val CURRENT_FG = JBColor(Color(33, 72, 153), Color(212, 224, 255))

        private val DONE_FG = JBColor(Color(39, 94, 57), Color(200, 234, 208))

        private val ACTIVE_FG = JBColor(Color(70, 79, 91), Color(218, 224, 231))

        private val INACTIVE_FG = JBColor(Color(131, 139, 149), Color(162, 170, 180))
    }
}
