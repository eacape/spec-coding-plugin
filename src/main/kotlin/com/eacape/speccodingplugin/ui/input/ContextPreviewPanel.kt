package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class ContextPreviewPanel(
    project: Project,
    private val onRemove: (ContextItem) -> Unit = {},
    private val onStateChanged: () -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)), Disposable {

    private val items = mutableListOf<ContextItem>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        isVisible = false

        ApplicationManager.getApplication()?.messageBus?.connect(this)?.subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    rebuildUi()
                }
            },
        )
    }

    fun addItem(item: ContextItem) {
        if (items.any { it.type == item.type && it.filePath == item.filePath && it.label == item.label }) {
            return
        }
        items += item
        rebuildUi()
    }

    fun removeItem(item: ContextItem) {
        if (items.remove(item)) {
            rebuildUi()
        }
    }

    fun getItems(): List<ContextItem> = items.toList()

    fun clear() {
        if (items.isEmpty()) {
            return
        }
        items.clear()
        rebuildUi()
    }

    private fun rebuildUi() {
        removeAll()
        if (items.isEmpty()) {
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
        val totalTokens = items.sumOf { it.tokenEstimate }
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        chip.isOpaque = true
        chip.background = JBColor(Color(0xEDF3F8), Color(0x39424F))
        chip.border = JBUI.Borders.empty(3, 8, 3, 6)

        val tooltip = buildSummaryTooltip(totalTokens)
        val summaryLabel = JBLabel(SpecCodingBundle.message("toolwindow.composer.context.summary", items.size))
        summaryLabel.font = JBUI.Fonts.smallFont()
        summaryLabel.foreground = JBColor(Color(0x37506B), Color(0xD4E2F5))
        summaryLabel.toolTipText = tooltip
        chip.toolTipText = tooltip
        chip.add(summaryLabel)

        val tokenLabel = JBLabel(SpecCodingBundle.message("context.tokens", totalTokens))
        tokenLabel.font = JBUI.Fonts.smallFont()
        tokenLabel.foreground = JBColor(Color(0x6B7685), Color(0xAEB8C7))
        tokenLabel.toolTipText = tooltip
        chip.add(tokenLabel)

        val clearButton = JButton(SpecCodingBundle.message("context.chip.remove"))
        clearButton.font = JBUI.Fonts.smallFont()
        clearButton.isBorderPainted = false
        clearButton.isContentAreaFilled = false
        clearButton.isFocusPainted = false
        clearButton.margin = JBUI.emptyInsets()
        clearButton.toolTipText = SpecCodingBundle.message("toolwindow.composer.context.clear.tooltip")
        clearButton.addActionListener {
            val removedItems = items.toList()
            clear()
            removedItems.forEach(onRemove)
        }
        chip.add(clearButton)

        return chip
    }

    private fun buildSummaryTooltip(totalTokens: Int): String {
        val preview = items
            .take(MAX_TOOLTIP_ITEMS)
            .joinToString("<br/>") { item -> escapeHtml(item.label) }
        val overflowCount = (items.size - MAX_TOOLTIP_ITEMS).coerceAtLeast(0)
        val overflowLine = if (overflowCount == 0) "" else "<br/>+${overflowCount}"
        return buildString {
            append("<html>")
            append(escapeHtml(SpecCodingBundle.message("toolwindow.composer.context.summary", items.size)))
            append(" / ")
            append(escapeHtml(SpecCodingBundle.message("context.tokens", totalTokens)))
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

    override fun dispose() = Unit

    companion object {
        private const val MAX_TOOLTIP_ITEMS = 5
    }
}
