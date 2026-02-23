package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.JTextArea
import javax.swing.border.AbstractBorder
import javax.swing.border.CompoundBorder

class HistorySessionListPanel(
    private val onSessionSelected: (String) -> Unit,
    private val onOpenSession: (String) -> Unit,
    private val onContinueSession: (String) -> Unit,
    private val onDeleteSession: (String) -> Unit,
    private val onBranchSession: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SessionSummary>()
    private val sessionList = HoverInfoSessionList(listModel)
    private val openButton = JButton(SpecCodingBundle.message("history.action.open"))
    private val continueButton = JButton(SpecCodingBundle.message("history.action.continue"))
    private val branchButton = JButton(SpecCodingBundle.message("history.action.branch"))
    private val deleteButton = JButton(SpecCodingBundle.message("history.action.delete"))
    private val selectionInfoCard = RoundedCardPanel(JBUI.scale(12))
    private val selectionInfoTitle = JLabel(SpecCodingBundle.message("history.info.title"))
    private val selectionInfoArea = JTextArea()

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
    }

    private fun setupUi() {
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
        }
        val actionsRow = JPanel(GridLayout(1, 4, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }

        styleActionButton(openButton)
        styleActionButton(continueButton)
        styleActionButton(branchButton)
        styleActionButton(deleteButton)

        openButton.addActionListener { selectedSessionId()?.let(onOpenSession) }
        continueButton.addActionListener { selectedSessionId()?.let(onContinueSession) }
        branchButton.addActionListener { selectedSessionId()?.let(onBranchSession) }
        deleteButton.addActionListener { selectedSessionId()?.let(onDeleteSession) }

        actionsRow.add(openButton)
        actionsRow.add(continueButton)
        actionsRow.add(branchButton)
        actionsRow.add(deleteButton)
        toolbar.add(actionsRow, BorderLayout.CENTER)
        add(toolbar, BorderLayout.NORTH)

        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.cellRenderer = SessionCellRenderer()
        sessionList.fixedCellHeight = -1
        sessionList.visibleRowCount = -1
        sessionList.isOpaque = false
        sessionList.setExpandableItemsEnabled(false)
        sessionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateButtonStates()
                val selected = sessionList.selectedValue
                updateSelectionInfo(selected)
                selected?.id?.let(onSessionSelected)
            }
        }
        updateButtonStates()

        setupSelectionInfoCard()
        val listScrollPane = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(
            JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                border = JBUI.Borders.empty()
                add(listScrollPane, BorderLayout.CENTER)
                add(selectionInfoCard, BorderLayout.SOUTH)
            },
            BorderLayout.CENTER,
        )
    }

    fun updateSessions(items: List<SessionSummary>) {
        listModel.clear()
        items.forEach(listModel::addElement)
        sessionList.revalidate()
        sessionList.repaint()

        if (sessionList.selectedValue == null) {
            updateButtonStates()
        }
        updateSelectionInfo(sessionList.selectedValue)
    }

    fun setSelectedSession(sessionId: String?) {
        if (sessionId == null) {
            sessionList.clearSelection()
            updateButtonStates()
            updateSelectionInfo(null)
            return
        }

        for (i in 0 until listModel.size()) {
            if (listModel[i].id == sessionId) {
                sessionList.selectedIndex = i
                updateButtonStates()
                updateSelectionInfo(listModel[i])
                return
            }
        }

        sessionList.clearSelection()
        updateButtonStates()
        updateSelectionInfo(null)
    }

    internal fun selectedSessionIdForTest(): String? = selectedSessionId()

    internal fun sessionsForTest(): List<SessionSummary> = (0 until listModel.size()).map { listModel[it] }

    internal fun buttonStatesForTest(): Map<String, Boolean> {
        return mapOf(
            "openEnabled" to openButton.isEnabled,
            "continueEnabled" to continueButton.isEnabled,
            "branchEnabled" to branchButton.isEnabled,
            "deleteEnabled" to deleteButton.isEnabled,
        )
    }

    internal fun clickOpenForTest() {
        openButton.doClick()
    }

    internal fun clickDeleteForTest() {
        deleteButton.doClick()
    }

    internal fun clickContinueForTest() {
        continueButton.doClick()
    }

    internal fun clickBranchForTest() {
        branchButton.doClick()
    }

    internal fun selectedInfoTextForTest(): String = selectionInfoArea.text

    private fun selectedSessionId(): String? = sessionList.selectedValue?.id

    private fun updateButtonStates() {
        val hasSelection = selectedSessionId() != null
        openButton.isEnabled = hasSelection
        continueButton.isEnabled = hasSelection
        branchButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.font = button.font.deriveFont(Font.BOLD, 11f)
        button.margin = JBUI.emptyInsets()
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.background = ACTION_BUTTON_BG
        button.foreground = ACTION_BUTTON_FG
        button.border = CompoundBorder(
            RoundedLineBorder(
                lineColor = ACTION_BUTTON_BORDER,
                arc = JBUI.scale(12),
            ),
            JBUI.Borders.empty(3, 10, 3, 10),
        )
        button.preferredSize = JBUI.size(0, 30)
        button.minimumSize = JBUI.size(0, 30)
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(12))
    }

    private fun isSpecSession(summary: SessionSummary): Boolean {
        if (!summary.specTaskId.isNullOrBlank()) return true
        val normalizedTitle = summary.title.trim().lowercase()
        return normalizedTitle.startsWith("/spec") || normalizedTitle.startsWith("[spec]")
    }

    private fun formatTimestamp(timestamp: Long): String {
        return timestampFormatter.format(Instant.ofEpochMilli(timestamp))
    }

    private fun setupSelectionInfoCard() {
        selectionInfoCard.layout = BorderLayout()
        selectionInfoCard.border = JBUI.Borders.empty(8, 10)
        selectionInfoCard.updateColors(INFO_CARD_BG, INFO_CARD_BORDER)
        selectionInfoCard.preferredSize = JBUI.size(0, 134)

        selectionInfoTitle.font = selectionInfoTitle.font.deriveFont(Font.BOLD, 11.5f)
        selectionInfoTitle.foreground = INFO_TITLE_FG

        selectionInfoArea.isEditable = false
        selectionInfoArea.isFocusable = false
        selectionInfoArea.isOpaque = false
        selectionInfoArea.lineWrap = true
        selectionInfoArea.wrapStyleWord = true
        selectionInfoArea.border = JBUI.Borders.empty()
        selectionInfoArea.font = JBUI.Fonts.smallFont()
        selectionInfoArea.foreground = INFO_TEXT_FG
        selectionInfoArea.rows = 6

        val infoScrollPane = JBScrollPane(selectionInfoArea).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.isOpaque = false
            isOpaque = false
        }

        selectionInfoCard.add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(selectionInfoTitle, BorderLayout.NORTH)
                add(infoScrollPane, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
        updateSelectionInfo(null)
    }

    private fun buildBindingText(summary: SessionSummary): String {
        return when {
            !summary.worktreeId.isNullOrBlank() -> SpecCodingBundle.message(
                "history.binding.worktree",
                summary.worktreeId,
            )

            !summary.branchName.isNullOrBlank() -> SpecCodingBundle.message(
                "history.binding.branch",
                summary.branchName,
            )

            !summary.specTaskId.isNullOrBlank() -> SpecCodingBundle.message(
                "history.binding.spec",
                summary.specTaskId,
            )

            else -> SpecCodingBundle.message("history.binding.general")
        }
    }

    private fun resolveProviderText(summary: SessionSummary): String {
        return summary.modelProvider
            ?.trim()
            ?.ifBlank { SpecCodingBundle.message("common.unknown") }
            ?: SpecCodingBundle.message("common.unknown")
    }

    private fun updateSelectionInfo(summary: SessionSummary?) {
        if (summary == null) {
            selectionInfoArea.text = SpecCodingBundle.message("history.info.empty")
            selectionInfoArea.caretPosition = 0
            return
        }

        val modeText = SpecCodingBundle.message(
            if (isSpecSession(summary)) "history.mode.spec" else "history.mode.vibe"
        )
        selectionInfoArea.text = buildString {
            appendLine(SpecCodingBundle.message("history.info.titleLine", summary.title))
            appendLine(SpecCodingBundle.message("history.tooltip.mode", modeText))
            appendLine(SpecCodingBundle.message("history.tooltip.id", summary.id))
            appendLine(
                SpecCodingBundle.message(
                    "history.binding.detail",
                    buildBindingText(summary),
                    summary.messageCount,
                )
            )
            appendLine(SpecCodingBundle.message("history.tooltip.provider", resolveProviderText(summary)))
            append(SpecCodingBundle.message("history.tooltip.updated", formatTimestamp(summary.updatedAt)))
        }
        selectionInfoArea.caretPosition = 0
    }

    private inner class HoverInfoSessionList(model: DefaultListModel<SessionSummary>) : JBList<SessionSummary>(model) {
        init {
            // Keep hover behavior visually stable: no cross-pane tooltip popup.
            toolTipText = null
        }

        override fun getToolTipText(event: java.awt.event.MouseEvent?): String? {
            return null
        }
    }

    private inner class SessionCellRenderer : ListCellRenderer<SessionSummary> {
        private val rowPanel = JPanel(BorderLayout())
        private val cardPanel = RoundedCardPanel(JBUI.scale(12))
        private val topRow = JPanel(BorderLayout(6, 0))
        private val modeBadge = JLabel()
        private val titleLabel = JLabel()
        private val detailLabel = JLabel()
        private val providerLabel = JLabel()
        private val updatedLabel = JLabel()

        init {
            rowPanel.isOpaque = false
            rowPanel.border = JBUI.Borders.empty(0, 0, 8, 0)

            cardPanel.layout = BorderLayout()
            cardPanel.border = JBUI.Borders.empty(8, 10)

            topRow.isOpaque = false

            modeBadge.font = modeBadge.font.deriveFont(Font.BOLD, 10f)
            modeBadge.border = JBUI.Borders.empty(1, 6, 1, 6)
            modeBadge.isOpaque = true

            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12.5f)
            titleLabel.horizontalAlignment = SwingConstants.LEFT
            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, 11f)
            detailLabel.horizontalAlignment = SwingConstants.LEFT
            providerLabel.font = providerLabel.font.deriveFont(Font.PLAIN, 10.5f)
            providerLabel.horizontalAlignment = SwingConstants.LEFT
            updatedLabel.font = updatedLabel.font.deriveFont(Font.PLAIN, 10.5f)
            updatedLabel.horizontalAlignment = SwingConstants.LEFT
            topRow.alignmentX = Component.LEFT_ALIGNMENT
            detailLabel.alignmentX = Component.LEFT_ALIGNMENT
            providerLabel.alignmentX = Component.LEFT_ALIGNMENT
            updatedLabel.alignmentX = Component.LEFT_ALIGNMENT

            topRow.add(modeBadge, BorderLayout.WEST)
            topRow.add(titleLabel, BorderLayout.CENTER)

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(topRow)
                add(Box.createVerticalStrut(4))
                add(detailLabel)
                add(Box.createVerticalStrut(3))
                add(providerLabel)
                add(Box.createVerticalStrut(2))
                add(updatedLabel)
            }
            cardPanel.add(content, BorderLayout.CENTER)
            rowPanel.add(cardPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out SessionSummary>,
            value: SessionSummary?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value != null) {
                val isSpec = isSpecSession(value)
                val modeTag = if (isSpec) {
                    SpecCodingBundle.message("history.mode.spec")
                } else {
                    SpecCodingBundle.message("history.mode.vibe")
                }
                modeBadge.text = modeTag
                val bindingDetail = SpecCodingBundle.message(
                    "history.binding.detail",
                    buildBindingText(value),
                    value.messageCount,
                )
                val updated = timestampFormatter.format(Instant.ofEpochMilli(value.updatedAt))
                titleLabel.text = value.title
                detailLabel.text = bindingDetail
                providerLabel.text = SpecCodingBundle.message("history.tooltip.provider", resolveProviderText(value))
                updatedLabel.text = SpecCodingBundle.message("history.tooltip.updated", updated)

                modeBadge.background = if (isSpec) {
                    MODE_SPEC_BADGE_BG
                } else {
                    MODE_VIBE_BADGE_BG
                }
                modeBadge.foreground = if (isSpec) {
                    MODE_SPEC_BADGE_FG
                } else {
                    MODE_VIBE_BADGE_FG
                }
            }

            val cardBackground = if (isSelected) {
                CARD_BG_SELECTED
            } else {
                CARD_BG_DEFAULT
            }
            val cardBorder = if (isSelected) {
                CARD_BORDER_SELECTED
            } else {
                CARD_BORDER_DEFAULT
            }
            val textColor = if (isSelected) {
                JBColor(Color(25, 41, 64), Color(224, 234, 247))
            } else {
                JBColor(Color(22, 24, 29), Color(224, 224, 224))
            }
            val subTextColor = if (isSelected) {
                JBColor(Color(49, 66, 90), Color(192, 210, 233))
            } else {
                JBColor(Color(96, 103, 114), Color(157, 164, 174))
            }
            val badgeBorder = if (isSelected) {
                JBColor(Color(120, 150, 189), Color(150, 175, 208))
            } else {
                JBColor(Color(173, 187, 207), Color(116, 126, 141))
            }

            cardPanel.updateColors(cardBackground, cardBorder)
            titleLabel.foreground = textColor
            detailLabel.foreground = subTextColor
            providerLabel.foreground = subTextColor
            updatedLabel.foreground = subTextColor
            modeBadge.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(badgeBorder, 1),
                JBUI.Borders.empty(1, 6, 1, 6),
            )

            return rowPanel
        }
    }

    private class RoundedCardPanel(private val arc: Int) : JPanel() {
        private var fillColor: Color = CARD_BG_DEFAULT
        private var strokeColor: Color = CARD_BORDER_DEFAULT

        init {
            isOpaque = false
        }

        fun updateColors(fill: Color, stroke: Color) {
            fillColor = fill
            strokeColor = stroke
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = fillColor
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = strokeColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        private val CARD_BG_DEFAULT = JBColor(Color(247, 249, 252), Color(55, 59, 67))
        private val CARD_BORDER_DEFAULT = JBColor(Color(219, 226, 238), Color(74, 80, 90))
        private val CARD_BG_SELECTED = JBColor(Color(220, 234, 253), Color(67, 86, 112))
        private val CARD_BORDER_SELECTED = JBColor(Color(129, 167, 225), Color(100, 130, 167))
        private val MODE_SPEC_BADGE_BG = JBColor(Color(224, 236, 255), Color(77, 95, 130))
        private val MODE_SPEC_BADGE_FG = JBColor(Color(36, 73, 133), Color(214, 226, 250))
        private val MODE_VIBE_BADGE_BG = JBColor(Color(230, 245, 233), Color(74, 107, 81))
        private val MODE_VIBE_BADGE_FG = JBColor(Color(44, 96, 56), Color(204, 236, 210))
        private val ACTION_BUTTON_BG = JBColor(Color(245, 248, 253), Color(62, 67, 77))
        private val ACTION_BUTTON_BORDER = JBColor(Color(194, 206, 224), Color(95, 106, 123))
        private val ACTION_BUTTON_FG = JBColor(Color(58, 78, 107), Color(199, 211, 230))
        private val INFO_CARD_BG = JBColor(Color(242, 246, 253), Color(49, 53, 61))
        private val INFO_CARD_BORDER = JBColor(Color(210, 220, 236), Color(76, 82, 93))
        private val INFO_TITLE_FG = JBColor(Color(58, 74, 101), Color(196, 207, 224))
        private val INFO_TEXT_FG = JBColor(Color(74, 88, 112), Color(174, 186, 204))
    }

    private class RoundedLineBorder(
        private val lineColor: Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: Component?,
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
                    if (drawWidth <= 0f || drawHeight <= 0f) return@repeat
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

        override fun getBorderInsets(c: Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
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
