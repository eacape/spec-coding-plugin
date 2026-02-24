package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.JPanel

/**
 * 阶段指示器面板
 * 显示 Specify -> Design -> Implement 三个阶段的进度
 */
class SpecPhaseIndicatorPanel : JPanel() {

    private var currentPhase: SpecPhase = SpecPhase.SPECIFY
    private var completedPhases: Set<SpecPhase> = emptySet()

    private val completedColor = JBColor(Color(99, 153, 230), Color(119, 160, 215))
    private val activeColor = JBColor(Color(46, 123, 231), Color(135, 182, 243))
    private val activeHaloColor = JBColor(Color(215, 231, 252), Color(80, 104, 136))
    private val pendingColor = JBColor(Color(191, 201, 214), Color(105, 113, 126))
    private val textColor = JBColor.foreground()

    init {
        preferredSize = Dimension(0, JBUI.scale(56))
        minimumSize = Dimension(0, JBUI.scale(56))
        isOpaque = false
    }

    fun updatePhase(workflow: SpecWorkflow) {
        currentPhase = workflow.currentPhase
        completedPhases = SpecPhase.entries
            .filter { phase -> workflow.documents.containsKey(phase) && phase != currentPhase }
            .toSet()
        repaint()
    }

    fun reset() {
        currentPhase = SpecPhase.SPECIFY
        completedPhases = emptySet()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val phases = SpecPhase.entries
        val phaseLabels = phases.map { it.displayName.lowercase() }
        val circleRadius = JBUI.scale(12)
        val labelFont = g2.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        g2.font = labelFont
        val labelWidths = phaseLabels.map { g2.fontMetrics.stringWidth(it) }
        val leftPadding = maxOf(JBUI.scale(18), (labelWidths.firstOrNull() ?: 0) / 2 + JBUI.scale(8))
        val rightPadding = maxOf(JBUI.scale(18), (labelWidths.lastOrNull() ?: 0) / 2 + JBUI.scale(8))
        val totalWidth = (width - leftPadding - rightPadding).coerceAtLeast(JBUI.scale(140))
        val startX = leftPadding
        val centerY = height / 2 - JBUI.scale(4)
        val spacing = totalWidth / (phases.size - 1).coerceAtLeast(1)

        for (i in 0 until phases.size - 1) {
            val x1 = startX + i * spacing + circleRadius
            val x2 = startX + (i + 1) * spacing - circleRadius
            val lineColor = if (phases[i] in completedPhases) completedColor else pendingColor
            g2.color = lineColor
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            g2.drawLine(x1, centerY, x2, centerY)
        }

        for ((i, phase) in phases.withIndex()) {
            val cx = startX + i * spacing
            val color = when {
                phase in completedPhases -> completedColor
                phase == currentPhase -> activeColor
                else -> pendingColor
            }

            if (phase == currentPhase) {
                g2.color = activeHaloColor
                val haloRadius = circleRadius + JBUI.scale(4)
                g2.fillOval(cx - haloRadius, centerY - haloRadius, haloRadius * 2, haloRadius * 2)
            }
            g2.color = color
            g2.fillOval(cx - circleRadius, centerY - circleRadius, circleRadius * 2, circleRadius * 2)

            g2.color = Color.WHITE
            g2.font = g2.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            val numStr = if (phase in completedPhases) "\u2713" else "${i + 1}"
            val fm = g2.fontMetrics
            g2.drawString(numStr, cx - fm.stringWidth(numStr) / 2, centerY + fm.ascent / 2 - 1)

            g2.color = if (phase == currentPhase) textColor else pendingColor
            g2.font = g2.font.deriveFont(
                if (phase == currentPhase) Font.BOLD else Font.PLAIN,
                JBUI.scale(11).toFloat()
            )
            val label = phaseLabels[i]
            val lm = g2.fontMetrics
            g2.drawString(label, cx - lm.stringWidth(label) / 2, centerY + circleRadius + lm.height)
        }
    }
}
