package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageProgress
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
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
        val (background, titleForeground, statusForeground, indicatorColor, borderColor) = chipPalette(stage)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            this.background = background
            this.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(borderColor, JBUI.scale(14)),
                JBUI.Borders.empty(8, 10, 8, 10),
            )
            add(
                JBLabel(SpecWorkflowOverviewPresenter.stageLabel(stage.stageId)).apply {
                    foreground = titleForeground
                    horizontalAlignment = SwingConstants.LEFT
                    alignmentX = LEFT_ALIGNMENT
                    font = JBUI.Fonts.smallFont().deriveFont(if (stage.current) java.awt.Font.BOLD else java.awt.Font.PLAIN)
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    alignmentX = LEFT_ALIGNMENT
                    add(createStatusIndicator(indicatorColor))
                    add(
                        JBLabel(statusText(stage)).apply {
                            foreground = statusForeground
                            font = JBUI.Fonts.smallFont().deriveFont(10.0f)
                        },
                    )
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

    private fun createStatusIndicator(color: Color): JComponent {
        return object : JComponent() {
            init {
                isOpaque = false
                preferredSize = JBUI.size(8, 8)
                minimumSize = preferredSize
                maximumSize = preferredSize
            }

            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics.create() as? Graphics2D ?: return
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = color
                    g2.fillOval(0, 0, width.coerceAtLeast(1) - 1, height.coerceAtLeast(1) - 1)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    private fun chipPalette(stage: SpecWorkflowStageStepState): ChipPalette {
        return when {
            stage.current -> ChipPalette(
                background = CURRENT_BG,
                titleForeground = CURRENT_FG,
                statusForeground = CURRENT_FG,
                indicator = CURRENT_INDICATOR,
                border = CURRENT_BORDER,
            )

            !stage.active -> ChipPalette(
                background = INACTIVE_BG,
                titleForeground = INACTIVE_FG,
                statusForeground = INACTIVE_FG,
                indicator = INACTIVE_INDICATOR,
                border = INACTIVE_BORDER,
            )

            stage.progress == StageProgress.DONE -> ChipPalette(
                background = DONE_BG,
                titleForeground = DONE_FG,
                statusForeground = DONE_FG,
                indicator = DONE_INDICATOR,
                border = DONE_BORDER,
            )

            else -> ChipPalette(
                background = ACTIVE_BG,
                titleForeground = ACTIVE_FG,
                statusForeground = ACTIVE_FG,
                indicator = ACTIVE_INDICATOR,
                border = ACTIVE_BORDER,
            )
        }
    }

    private data class ChipPalette(
        val background: Color,
        val titleForeground: Color,
        val statusForeground: Color,
        val indicator: Color,
        val border: Color,
    )

    companion object {
        private val CONNECTOR_FG = JBColor(Color(132, 141, 153), Color(122, 130, 142))

        private val CURRENT_BG = JBColor(Color(233, 240, 255), Color(53, 71, 109))
        private val CURRENT_FG = JBColor(Color(33, 72, 153), Color(212, 224, 255))
        private val CURRENT_INDICATOR = JBColor(Color(76, 117, 204), Color(182, 206, 255))
        private val CURRENT_BORDER = JBColor(Color(138, 168, 232), Color(109, 130, 176))

        private val DONE_BG = JBColor(Color(233, 246, 237), Color(49, 74, 57))
        private val DONE_FG = JBColor(Color(39, 94, 57), Color(200, 234, 208))
        private val DONE_INDICATOR = JBColor(Color(66, 134, 88), Color(172, 225, 186))
        private val DONE_BORDER = JBColor(Color(154, 204, 170), Color(87, 134, 102))

        private val ACTIVE_BG = JBColor(Color(244, 247, 250), Color(64, 70, 79))
        private val ACTIVE_FG = JBColor(Color(70, 79, 91), Color(218, 224, 231))
        private val ACTIVE_INDICATOR = JBColor(Color(103, 118, 137), Color(189, 198, 211))
        private val ACTIVE_BORDER = JBColor(Color(205, 214, 225), Color(98, 108, 121))

        private val INACTIVE_BG = JBColor(Color(247, 248, 250), Color(62, 65, 71))
        private val INACTIVE_FG = JBColor(Color(131, 139, 149), Color(162, 170, 180))
        private val INACTIVE_INDICATOR = JBColor(Color(171, 179, 189), Color(122, 129, 139))
        private val INACTIVE_BORDER = JBColor(Color(219, 224, 230), Color(96, 102, 112))
    }
}
