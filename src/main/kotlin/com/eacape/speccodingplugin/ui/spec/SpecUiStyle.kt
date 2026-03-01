package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.border.AbstractBorder
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.plaf.basic.BasicSplitPaneUI

internal object SpecUiStyle {
    fun roundedLineBorder(
        lineColor: Color,
        arc: Int,
        thickness: Int = 1,
    ): Border = RoundedLineBorder(
        lineColor = lineColor,
        arc = arc,
        thickness = thickness,
    )

    fun roundedCardBorder(
        lineColor: Color,
        arc: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    ): Border = CompoundBorder(
        roundedLineBorder(lineColor = lineColor, arc = arc),
        JBUI.Borders.empty(top, left, bottom, right),
    )

    fun applyRoundRect(button: AbstractButton, arc: Int) {
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(arc))
    }

    fun applySlimHorizontalScrollBar(scrollPane: JScrollPane, height: Int = 7) {
        val barHeight = JBUI.scale(height)
        val trackColor = JBColor(Color(224, 233, 247), Color(72, 80, 92))
        val thumbColor = JBColor(Color(143, 171, 214), Color(122, 143, 171))
        val topLineColor = JBColor(Color(191, 206, 229), Color(87, 97, 112))

        scrollPane.viewportBorder = JBUI.Borders.emptyBottom(barHeight + JBUI.scale(3))
        scrollPane.horizontalScrollBar.apply {
            preferredSize = JBUI.size(0, barHeight)
            minimumSize = JBUI.size(0, barHeight)
            maximumSize = JBUI.size(Int.MAX_VALUE, barHeight)
            unitIncrement = JBUI.scale(24)
            blockIncrement = JBUI.scale(96)
            isOpaque = true
            background = trackColor
            foreground = thumbColor
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, topLineColor)
            putClientProperty("JScrollBar.showButtons", false)
            putClientProperty("JComponent.sizeVariant", "regular")
            putClientProperty("JScrollBar.trackColor", trackColor)
            putClientProperty("JScrollBar.thumbColor", thumbColor)
        }
        scrollPane.putClientProperty("JScrollPane.smoothScrolling", true)
    }

    fun applySplitPaneDivider(
        splitPane: JSplitPane,
        dividerSize: Int = JBUI.scale(8),
        dividerBackground: Color = JBColor(Color(236, 240, 246), Color(66, 72, 82)),
        dividerBorderColor: Color = JBColor(Color(217, 223, 232), Color(78, 86, 98, 110)),
    ) {
        val horizontal = splitPane.orientation == JSplitPane.HORIZONTAL_SPLIT
        val cursorType = if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR
        val cursor = Cursor.getPredefinedCursor(cursorType)
        splitPane.dividerSize = dividerSize
        splitPane.isOneTouchExpandable = false
        (splitPane.ui as? BasicSplitPaneUI)?.divider?.let { divider ->
            divider.cursor = cursor
            divider.background = dividerBackground
            divider.border = if (StartupUiUtil.isUnderDarcula) {
                JBUI.Borders.empty()
            } else if (horizontal) {
                JBUI.Borders.customLine(dividerBorderColor, 0, 1, 0, 1)
            } else {
                JBUI.Borders.customLine(dividerBorderColor, 1, 0, 1, 0)
            }
        }
    }

    private class RoundedLineBorder(
        private val lineColor: Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: java.awt.Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = lineColor
                val safeThickness = thickness.coerceAtLeast(1)
                repeat(safeThickness) { index ->
                    val offset = index + 0.5f
                    val drawWidth = width - index * 2 - 1f
                    val drawHeight = height - index * 2 - 1f
                    if (drawWidth <= 0f || drawHeight <= 0f) {
                        return@repeat
                    }
                    val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                    g2.draw(
                        RoundRectangle2D.Float(
                            x + offset,
                            y + offset,
                            drawWidth,
                            drawHeight,
                            arcSize,
                            arcSize,
                        ),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: java.awt.Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: java.awt.Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }
}
