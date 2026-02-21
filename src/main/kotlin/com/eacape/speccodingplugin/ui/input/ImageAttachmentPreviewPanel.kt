package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 图片附件预览面板，使用 chips 展示已选择的图片文件。
 */
class ImageAttachmentPreviewPanel(
    private val onRemove: (String) -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {

    private val imagePaths = mutableListOf<String>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
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
        if (imagePaths.isEmpty()) return
        imagePaths.clear()
        rebuildUi()
    }

    private fun removeImagePath(path: String) {
        val removed = imagePaths.removeIf { it.equals(path, ignoreCase = true) }
        if (!removed) return
        rebuildUi()
        onRemove(path)
    }

    private fun rebuildUi() {
        removeAll()
        if (imagePaths.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }

        isVisible = true
        imagePaths.forEach { path ->
            add(createChip(path))
        }
        revalidate()
        repaint()
    }

    private fun createChip(path: String): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        chip.isOpaque = true
        chip.background = JBColor(0xEAF2FF, 0x3A4350)
        chip.border = JBUI.Borders.empty(2, 6)

        val label = JBLabel(File(path).name.ifBlank { path }, AllIcons.FileTypes.Image, JBLabel.LEADING)
        label.font = JBUI.Fonts.smallFont()
        label.toolTipText = path
        chip.add(label)

        val removeButton = JButton("x")
        removeButton.font = JBUI.Fonts.smallFont()
        removeButton.isBorderPainted = false
        removeButton.isContentAreaFilled = false
        removeButton.margin = JBUI.emptyInsets()
        removeButton.toolTipText = SpecCodingBundle.message("toolwindow.image.attach.remove.tooltip")
        removeButton.addActionListener { removeImagePath(path) }
        chip.add(removeButton)

        return chip
    }
}
