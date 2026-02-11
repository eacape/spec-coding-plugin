package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.context.ContextItem
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 上下文预览面板
 * 水平流式布局的 "chips"，每个 chip 代表一个 ContextItem
 */
class ContextPreviewPanel(
    private val onRemove: (ContextItem) -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {

    private val items = mutableListOf<ContextItem>()
    private val tokenLabel = JLabel()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        isVisible = false
        tokenLabel.foreground = JBColor.GRAY
        tokenLabel.font = JBUI.Fonts.smallFont()
    }

    fun addItem(item: ContextItem) {
        if (items.any { it.type == item.type && it.filePath == item.filePath && it.label == item.label }) {
            return
        }
        items.add(item)
        rebuildUI()
    }

    fun removeItem(item: ContextItem) {
        items.remove(item)
        rebuildUI()
    }

    fun getItems(): List<ContextItem> = items.toList()

    fun clear() {
        items.clear()
        rebuildUI()
    }

    private fun rebuildUI() {
        removeAll()

        if (items.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }

        isVisible = true

        for (item in items) {
            add(createChip(item))
        }

        // Token estimate summary
        val totalTokens = items.sumOf { it.tokenEstimate }
        tokenLabel.text = "~${totalTokens} tokens"
        add(tokenLabel)

        revalidate()
        repaint()
    }

    private fun createChip(item: ContextItem): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        chip.isOpaque = true
        chip.background = JBColor(0xE8EDF2, 0x3C3F41)
        chip.border = JBUI.Borders.empty(2, 6)

        val label = JLabel(item.label)
        label.font = JBUI.Fonts.smallFont()
        chip.add(label)

        val closeBtn = JButton("x")
        closeBtn.font = JBUI.Fonts.smallFont()
        closeBtn.isBorderPainted = false
        closeBtn.isContentAreaFilled = false
        closeBtn.margin = JBUI.emptyInsets()
        closeBtn.addActionListener {
            removeItem(item)
            onRemove(item)
        }
        chip.add(closeBtn)

        return chip
    }
}
