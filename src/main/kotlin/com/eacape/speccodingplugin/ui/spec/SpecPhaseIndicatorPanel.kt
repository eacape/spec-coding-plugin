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

    private val completedColor = JBColor(Color(76, 175, 80), Color(76, 175, 80))
    private val activeColor = JBColor(Color(33, 150, 243), Color(78, 154, 241))
    private val pendingColor = JBColor(Color(189, 189, 189), Color(100, 100, 100))
    private val textColor = JBColor.foreground()

    init {
        preferredSize = Dimension(0, JBUI.scale(60))
        minimumSize = Dimension(0, JBUI.scale(60))
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
        val circleRadius = JBUI.scale(14)
        val totalWidth = width - JBUI.scale(40)
        val startX = JBUI.scale(20)
        val centerY = height / 2 - JBUI.scale(5)
        val spacing = totalWidth / (phases.size - 1).coerceAtLeast(1)

        // 画连接线
        for (i in 0 until phases.size - 1) {
            val x1 = startX + i * spacing + circleRadius
            val x2 = startX + (i + 1) * spacing - circleRadius
            val lineColor = if (phases[i] in completedPhases) completedColor else pendingColor
            g2.color = lineColor
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            g2.drawLine(x1, centerY, x2, centerY)
        }

        // 画圆圈和标签
        for ((i, phase) in phases.withIndex()) {
            val cx = startX + i * spacing
            val color = when {
                phase in completedPhases -> completedColor
                phase == currentPhase -> activeColor
                else -> pendingColor
            }

            // 圆圈
            g2.color = color
            g2.fillOval(cx - circleRadius, centerY - circleRadius, circleRadius * 2, circleRadius * 2)

            // 圆圈内文字
            g2.color = Color.WHITE
            g2.font = g2.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            val numStr = if (phase in completedPhases) "\u2713" else "${i + 1}"
            val fm = g2.fontMetrics
            g2.drawString(numStr, cx - fm.stringWidth(numStr) / 2, centerY + fm.ascent / 2 - 1)

            // 标签
            g2.color = if (phase == currentPhase) textColor else pendingColor
            g2.font = g2.font.deriveFont(
                if (phase == currentPhase) Font.BOLD else Font.PLAIN,
                JBUI.scale(11).toFloat()
            )
            val label = phase.displayName
            val lm = g2.fontMetrics
            g2.drawString(label, cx - lm.stringWidth(label) / 2, centerY + circleRadius + lm.height)
        }
    }
}
