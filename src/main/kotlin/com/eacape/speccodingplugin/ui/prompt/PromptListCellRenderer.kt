package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Prompt 列表项渲染器
 */
class PromptListCellRenderer : ListCellRenderer<PromptTemplate> {

    override fun getListCellRendererComponent(
        list: JList<out PromptTemplate>,
        value: PromptTemplate?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = JBUI.Borders.empty(4, 8)

        if (value == null) return panel

        // 名称
        val nameLabel = JLabel(value.name)
        nameLabel.font = nameLabel.font.deriveFont(13f)

        // 作用域标签
        val scopeName = SpecCodingBundle.messageOrDefault("prompt.scope.${value.scope.name.lowercase()}", value.scope.name)
        val scopeLabel = JLabel(SpecCodingBundle.message("prompt.scope.label", scopeName))
        scopeLabel.font = scopeLabel.font.deriveFont(10f)
        scopeLabel.foreground = JBColor.GRAY

        // 标签
        val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        tagsPanel.isOpaque = false
        value.tags.take(3).forEach { tag ->
            val tagLabel = JLabel(tag)
            tagLabel.font = tagLabel.font.deriveFont(10f)
            tagLabel.foreground = JBColor(
                java.awt.Color(0, 120, 215),
                java.awt.Color(78, 154, 241)
            )
            tagsPanel.add(tagLabel)
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        left.isOpaque = false
        left.add(nameLabel)
        left.add(scopeLabel)

        panel.add(left, BorderLayout.WEST)
        panel.add(tagsPanel, BorderLayout.EAST)

        // 选中状态
        if (isSelected) {
            panel.background = list.selectionBackground
            nameLabel.foreground = list.selectionForeground
        } else {
            panel.background = list.background
            nameLabel.foreground = list.foreground
        }

        return panel
    }
}
