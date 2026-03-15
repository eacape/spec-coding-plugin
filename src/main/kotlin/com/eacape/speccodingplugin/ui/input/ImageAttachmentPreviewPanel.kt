package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class ImageAttachmentPreviewPanel(
    private val onRemove: (String) -> Unit = {},
    private val onStateChanged: () -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {

    private val imagePaths = mutableListOf<String>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        isVisible = false
    }

    fun addImagePaths(paths: List<String>) {
        var changed = false
        paths.forEach { path ->
            val normalized = path.trim()
            if (normalized.isNotBlank() && imagePaths.none { it.equals(normalized, ignoreCase = true) }) {
                imagePaths += normalized
                changed = true
            }
        }
        if (changed) {
            rebuildUi()
        }
    }

    fun getImagePaths(): List<String> = imagePaths.toList()

    fun clear() {
        if (imagePaths.isEmpty()) {
            return
        }
        imagePaths.clear()
        rebuildUi()
    }

    private fun removeAllImagePaths() {
        if (imagePaths.isEmpty()) {
            return
        }
        val removedPaths = imagePaths.toList()
        clear()
        removedPaths.forEach(onRemove)
    }

    private fun rebuildUi() {
        removeAll()
        if (imagePaths.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            onStateChanged()
            return
        }

        isVisible = true
        add(createSummaryChip())
        revalidate()
        repaint()
        onStateChanged()
    }

    private fun createSummaryChip(): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        chip.isOpaque = true
        chip.background = JBColor(Color(0xEEF5FF), Color(0x344152))
        chip.border = JBUI.Borders.empty(3, 8, 3, 6)

        val tooltip = buildSummaryTooltip()
        val summaryLabel = JBLabel(SpecCodingBundle.message("toolwindow.composer.images.summary", imagePaths.size))
        summaryLabel.font = JBUI.Fonts.smallFont()
        summaryLabel.foreground = JBColor(Color(0x2D507B), Color(0xCFE0FF))
        summaryLabel.toolTipText = tooltip
        chip.toolTipText = tooltip
        chip.add(summaryLabel)

        val clearButton = JButton(SpecCodingBundle.message("context.chip.remove"))
        clearButton.font = JBUI.Fonts.smallFont()
        clearButton.isBorderPainted = false
        clearButton.isContentAreaFilled = false
        clearButton.isFocusPainted = false
        clearButton.margin = JBUI.emptyInsets()
        clearButton.toolTipText = SpecCodingBundle.message("toolwindow.composer.images.clear.tooltip")
        clearButton.addActionListener { removeAllImagePaths() }
        chip.add(clearButton)

        return chip
    }

    private fun buildSummaryTooltip(): String {
        val preview = imagePaths
            .take(MAX_TOOLTIP_ITEMS)
            .mapIndexed { index, path -> "image#${index + 1}: ${escapeHtml(path)}" }
            .joinToString("<br/>")
        val overflowCount = (imagePaths.size - MAX_TOOLTIP_ITEMS).coerceAtLeast(0)
        val overflowLine = if (overflowCount == 0) "" else "<br/>+${overflowCount}"
        return buildString {
            append("<html>")
            append(escapeHtml(SpecCodingBundle.message("toolwindow.composer.images.summary", imagePaths.size)))
            if (preview.isNotBlank()) {
                append("<br/>")
                append(preview)
            }
            append(overflowLine)
            append("</html>")
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    companion object {
        private const val MAX_TOOLTIP_ITEMS = 5
    }
}
