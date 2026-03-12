package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

internal class SpecCollapsibleWorkspaceSection(
    private val titleProvider: () -> String,
    content: Component,
    expandedInitially: Boolean = true,
    private val onExpandedChanged: (Boolean) -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private val titleLabel = JBLabel()
    private val summaryLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = SECTION_SUMMARY_FG
        isVisible = false
    }
    private val toggleButton = JButton().apply {
        isFocusable = false
        isFocusPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        foreground = SECTION_TOGGLE_FG
        margin = JBUI.insets(0, 4, 0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener {
            setExpanded(!isExpanded())
        }
    }
    private val bodyContainer = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(content, BorderLayout.CENTER)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 0, 2)
        add(
            JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply {
                isOpaque = false
                add(
                    JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                        isOpaque = false
                        add(titleLabel, BorderLayout.NORTH)
                        add(summaryLabel, BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
                add(toggleButton, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(bodyContainer, BorderLayout.CENTER)
        refreshLocalizedTexts()
        setExpanded(expandedInitially, notify = false)
    }

    fun refreshLocalizedTexts() {
        titleLabel.text = titleProvider()
        titleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12.5f)
        titleLabel.foreground = SECTION_TITLE_FG
        updateTogglePresentation()
    }

    fun setSummary(summary: String?) {
        val value = summary?.trim().orEmpty()
        summaryLabel.text = value
        summaryLabel.isVisible = value.isNotEmpty()
    }

    fun setExpanded(expanded: Boolean, notify: Boolean = true) {
        if (bodyContainer.isVisible == expanded) {
            updateTogglePresentation()
            return
        }
        bodyContainer.isVisible = expanded
        updateTogglePresentation()
        revalidate()
        repaint()
        if (notify) {
            onExpandedChanged(expanded)
        }
    }

    fun isExpanded(): Boolean = bodyContainer.isVisible

    private fun updateTogglePresentation() {
        val expanded = isExpanded()
        val key = if (expanded) {
            "spec.detail.toggle.collapse"
        } else {
            "spec.detail.toggle.expand"
        }
        toggleButton.text = SpecCodingBundle.message(key)
        toggleButton.toolTipText = toggleButton.text
        val targetWidth = maxOf(
            JBUI.scale(52),
            toggleButton.getFontMetrics(toggleButton.font).stringWidth(toggleButton.text) + JBUI.scale(10),
        )
        toggleButton.preferredSize = JBUI.size(targetWidth, JBUI.scale(20))
        toggleButton.minimumSize = toggleButton.preferredSize
    }

    companion object {
        private val SECTION_TITLE_FG = JBColor(Color(53, 70, 108), Color(211, 220, 235))
        private val SECTION_SUMMARY_FG = JBColor(Color(102, 116, 144), Color(154, 166, 184))
        private val SECTION_TOGGLE_FG = JBColor(Color(86, 108, 148), Color(170, 184, 208))
    }
}
