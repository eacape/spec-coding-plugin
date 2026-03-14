package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateQuickFixKind
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.MissingRequirementsSectionsQuickFixPayload
import com.eacape.speccodingplugin.spec.RequirementsSectionAiSupport
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class SpecWorkflowGateDetailsPanel(
    private val project: Project,
    private val showHeader: Boolean = true,
    private val onClarifyThenFillRequested: ((String, List<RequirementsSectionId>) -> Boolean)? = null,
    private val aiFillUnavailableReasonProvider: (() -> String?)? = null,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private enum class SeverityFilter {
        ALL,
        ERROR,
        WARNING,
    }

    private val headerTitleLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(12.5f)
        foreground = HEADER_FG
    }
    private val headerSummaryLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = HEADER_SECONDARY_FG
    }
    private val headerRefreshedLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.RIGHT
        font = JBUI.Fonts.smallFont().deriveFont(10.5f)
        foreground = HEADER_SECONDARY_FG
    }

    private val statusChipLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder()
        isOpaque = true
        font = JBUI.Fonts.smallFont().deriveFont(10.5f)
    }

    private val severityFilterComboBox = JComboBox<SeverityFilter>().apply {
        model = DefaultComboBoxModel(SeverityFilter.entries.toTypedArray())
        renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = severityFilterLabel(value)
        }
    }

    private val searchField = JBTextField().apply {
        columns = 16
    }

    private val openButton = JButton().apply {
        isEnabled = false
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { openSelectedViolation() }
    }

    private val fixButton = JButton().apply {
        isEnabled = false
        isFocusable = false
        SpecUiStyle.applyRoundRect(this, 14)
        addActionListener { showQuickFixActions() }
    }

    private val listModel = DefaultListModel<Violation>()
    private val violationsList = JBList(listModel).apply {
        cellRenderer = ViolationCellRenderer()
        visibleRowCount = -1
        fixedCellHeight = -1
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = SpecCodingBundle.message("spec.toolwindow.gate.empty")
    }

    private var currentWorkflowId: String? = null
    private var currentGateResult: GateResult? = null
    private var allViolations: List<Violation> = emptyList()
    private var lastRefreshedAtMillis: Long? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 4, 6, 4)
        add(buildHeader(), BorderLayout.NORTH)
        add(
            JBScrollPane(violationsList).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = severityFilterComboBox,
            minWidth = JBUI.scale(82),
            maxWidth = JBUI.scale(170),
            height = severityFilterComboBox.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(28),
        )
        refreshLocalizedTexts()
        showEmpty()

        severityFilterComboBox.addActionListener { applyFilters() }
        searchField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilters()
                override fun removeUpdate(e: DocumentEvent) = applyFilters()
                override fun changedUpdate(e: DocumentEvent) = applyFilters()
            },
        )
        violationsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateControlsForSelection()
            }
        }
        violationsList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2 && e.button == java.awt.event.MouseEvent.BUTTON1) {
                        openSelectedViolation()
                    }
                }
            },
        )
    }

    fun refreshLocalizedTexts() {
        headerTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.gate.title")
        openButton.text = SpecCodingBundle.message("spec.toolwindow.gate.open")
        fixButton.text = SpecCodingBundle.message("spec.toolwindow.gate.fix")
        searchField.emptyText.text = SpecCodingBundle.message("spec.toolwindow.gate.filter.search")
        severityFilterComboBox.toolTipText = SpecCodingBundle.message("spec.toolwindow.gate.filter.severity.tooltip")
        severityFilterComboBox.repaint()
        ComboBoxAutoWidthSupport.refreshSelectedItemAutoWidth(severityFilterComboBox)
        updateHeader()
        applyFilters()
    }

    fun showEmpty() {
        currentWorkflowId = null
        currentGateResult = null
        allViolations = emptyList()
        lastRefreshedAtMillis = null
        listModel.clear()
        violationsList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.gate.empty")
        updateHeader()
        updateControlsForSelection()
    }

    fun showLoading() {
        currentWorkflowId = null
        currentGateResult = null
        allViolations = emptyList()
        lastRefreshedAtMillis = null
        listModel.clear()
        violationsList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.gate.loading")
        updateHeader()
        updateControlsForSelection()
    }

    fun updateGateResult(
        workflowId: String,
        gateResult: GateResult?,
        refreshedAtMillis: Long,
    ) {
        currentWorkflowId = workflowId
        currentGateResult = gateResult
        allViolations = gateResult?.violations.orEmpty()
        lastRefreshedAtMillis = refreshedAtMillis
        applyFilters()
        updateHeader()
        updateControlsForSelection()
    }

    internal fun snapshotForTest(): Map<String, String> {
        return mapOf(
            "headerVisible" to showHeader.toString(),
            "headerTitle" to headerTitleLabel.text.orEmpty(),
            "headerSummary" to headerSummaryLabel.text.orEmpty(),
            "headerRefreshed" to headerRefreshedLabel.text.orEmpty(),
            "statusChip" to statusChipLabel.text.orEmpty(),
            "workflowId" to currentWorkflowId.orEmpty(),
            "violationCount" to listModel.size().toString(),
            "emptyText" to violationsList.emptyText.text.orEmpty(),
        )
    }

    internal fun selectViolationForTest(index: Int) {
        if (index in 0 until listModel.size()) {
            violationsList.selectedIndex = index
        }
    }

    internal fun selectedQuickFixLabelsForTest(): List<String> {
        val selected = violationsList.selectedValue ?: return emptyList()
        return quickFixPresentations(selected).map(SpecGateQuickFixSupport.Presentation::title)
    }

    internal fun selectedQuickFixPopupTextsForTest(): List<String> {
        val selected = violationsList.selectedValue ?: return emptyList()
        return quickFixPresentations(selected).map(SpecGateQuickFixSupport.Presentation::popupText)
    }

    private fun buildHeader(): JPanel {
        val titleRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(headerTitleLabel, BorderLayout.WEST)
            add(headerRefreshedLabel, BorderLayout.EAST)
        }
        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(statusChipLabel)
            add(severityFilterComboBox)
            add(searchField)
            add(openButton)
            add(fixButton)
        }
        if (!showHeader) {
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 2, 4, 2)
                add(controlsRow, BorderLayout.CENTER)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 2, 4, 2)
            add(titleRow, BorderLayout.NORTH)
            add(headerSummaryLabel, BorderLayout.CENTER)
            add(controlsRow, BorderLayout.SOUTH)
        }
    }

    private fun updateHeader() {
        val refreshedAt = lastRefreshedAtMillis
        headerRefreshedLabel.text = refreshedAt?.let { millis ->
            REFRESHED_AT_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }.orEmpty()

        val gateResult = currentGateResult
        when {
            currentWorkflowId.isNullOrBlank() -> {
                headerSummaryLabel.text = SpecCodingBundle.message("spec.toolwindow.gate.summary.none")
                statusChipLabel.text = SpecCodingBundle.message("spec.toolwindow.gate.status.unavailable")
                applyStatusChipStyle(null)
            }

            gateResult == null -> {
                headerSummaryLabel.text = SpecCodingBundle.message("spec.toolwindow.gate.unavailable")
                statusChipLabel.text = SpecCodingBundle.message("spec.toolwindow.gate.status.unavailable")
                applyStatusChipStyle(null)
            }

            else -> {
                val aggregation = gateResult.aggregation
                headerSummaryLabel.text = SpecCodingBundle.message(
                    "spec.toolwindow.gate.summary",
                    aggregation.errorCount,
                    aggregation.warningCount,
                    aggregation.totalViolationCount,
                )
                headerSummaryLabel.toolTipText = aggregation.summary
                statusChipLabel.text = statusChipText(gateResult.status)
                applyStatusChipStyle(gateResult.status)
            }
        }
    }

    private fun statusChipText(status: GateStatus?): String {
        return when (status) {
            GateStatus.PASS -> SpecCodingBundle.message("spec.toolwindow.gate.status.pass")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.toolwindow.gate.status.warning")
            GateStatus.ERROR -> SpecCodingBundle.message("spec.toolwindow.gate.status.error")
            null -> SpecCodingBundle.message("spec.toolwindow.gate.status.unavailable")
        }
    }

    private fun applyStatusChipStyle(status: GateStatus?) {
        val (background, foreground, border) = when (status) {
            GateStatus.PASS -> Triple(PASS_BG, PASS_FG, PASS_BORDER)
            GateStatus.WARNING -> Triple(WARN_BG, WARN_FG, WARN_BORDER)
            GateStatus.ERROR -> Triple(ERROR_BG, ERROR_FG, ERROR_BORDER)
            null -> Triple(NEUTRAL_BG, NEUTRAL_FG, NEUTRAL_BORDER)
        }
        statusChipLabel.background = background
        statusChipLabel.foreground = foreground
        statusChipLabel.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
            JBUI.Borders.empty(2, 8, 2, 8),
        )
    }

    private fun severityFilterLabel(filter: SeverityFilter?): String {
        return when (filter) {
            SeverityFilter.ALL -> SpecCodingBundle.message("spec.toolwindow.gate.filter.severity.all")
            SeverityFilter.ERROR -> SpecCodingBundle.message("spec.toolwindow.gate.filter.severity.errors")
            SeverityFilter.WARNING -> SpecCodingBundle.message("spec.toolwindow.gate.filter.severity.warnings")
            null -> ""
        }
    }

    private fun applyFilters() {
        val gateResult = currentGateResult
        val workflowId = currentWorkflowId
        val rawSearch = searchField.text.orEmpty().trim()
        val severityFilter = severityFilterComboBox.selectedItem as? SeverityFilter ?: SeverityFilter.ALL
        val filtered = allViolations.filter { violation ->
            matchesSeverity(severityFilter, violation) && matchesSearch(rawSearch, violation)
        }
        listModel.clear()
        filtered.forEach(listModel::addElement)

        violationsList.emptyText.text = when {
            workflowId.isNullOrBlank() -> SpecCodingBundle.message("spec.toolwindow.gate.empty")
            gateResult == null -> SpecCodingBundle.message("spec.toolwindow.gate.unavailable")
            gateResult.violations.isEmpty() -> SpecCodingBundle.message("spec.toolwindow.gate.noViolations")
            filtered.isEmpty() -> SpecCodingBundle.message("spec.toolwindow.gate.filter.empty")
            else -> ""
        }
        updateControlsForSelection()
        revalidate()
        repaint()
    }

    private fun matchesSeverity(filter: SeverityFilter, violation: Violation): Boolean {
        return when (filter) {
            SeverityFilter.ALL -> true
            SeverityFilter.ERROR -> violation.severity == GateStatus.ERROR
            SeverityFilter.WARNING -> violation.severity == GateStatus.WARNING
        }
    }

    private fun matchesSearch(search: String, violation: Violation): Boolean {
        if (search.isBlank()) return true
        val normalized = search.lowercase()
        return violation.ruleId.lowercase().contains(normalized) ||
            violation.fileName.lowercase().contains(normalized) ||
            violation.message.lowercase().contains(normalized) ||
            (violation.fixHint?.lowercase()?.contains(normalized) == true) ||
            SpecGateQuickFixSupport.searchableText(violation).lowercase().contains(normalized)
    }

    private fun updateControlsForSelection() {
        val selected = violationsList.selectedValue
        val workflowId = currentWorkflowId
        openButton.isEnabled = selected != null && !workflowId.isNullOrBlank()
        val quickFixSummary = selected?.let(SpecGateQuickFixSupport::summary)
        fixButton.isEnabled = selected != null &&
            (
                !quickFixSummary.isNullOrBlank() ||
                    selected.fixHint?.isNullOrBlank() == false
                )
        fixButton.toolTipText = quickFixSummary ?: selected?.fixHint?.takeIf(String::isNotBlank)
    }

    private fun openSelectedViolation() {
        val workflowId = currentWorkflowId ?: return
        val violation = violationsList.selectedValue ?: return
        if (SpecWorkflowActionSupport.openGateViolation(project, workflowId, violation)) {
            return
        }
        SpecWorkflowActionSupport.showInfo(
            project,
            SpecCodingBundle.message("spec.action.gate.location.unavailable.title"),
            gateViolationDetails(violation),
        )
    }

    private fun showQuickFixActions() {
        val workflowId = currentWorkflowId ?: return
        val violation = violationsList.selectedValue ?: return
        val quickFixes = quickFixPresentations(violation)
        if (quickFixes.isNotEmpty()) {
            if (quickFixes.size == 1 && quickFixes.single().enabled) {
                executeQuickFix(workflowId, violation, quickFixes.single())
                return
            }
            showQuickFixPopup(workflowId, violation, quickFixes)
            return
        }
        showFixPlaceholder(violation)
    }

    private fun showFixPlaceholder(violation: Violation) {
        val hint = violation.fixHint?.takeIf(String::isNotBlank) ?: return
        SpecWorkflowActionSupport.showInfo(
            project,
            SpecCodingBundle.message("spec.toolwindow.gate.fix.unavailable.title"),
            SpecCodingBundle.message("spec.toolwindow.gate.fix.unavailable.message", hint),
        )
    }

    private fun showQuickFixPopup(
        workflowId: String,
        violation: Violation,
        quickFixes: List<SpecGateQuickFixSupport.Presentation>,
    ) {
        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<SpecGateQuickFixSupport.Presentation>(
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.popup.title"),
                quickFixes,
            ) {
                override fun getTextFor(value: SpecGateQuickFixSupport.Presentation): String = value.popupText()

                override fun isSelectable(value: SpecGateQuickFixSupport.Presentation?): Boolean =
                    value?.enabled == true

                override fun onChosen(
                    selectedValue: SpecGateQuickFixSupport.Presentation,
                    finalChoice: Boolean,
                ): PopupStep<*>? {
                    if (finalChoice && selectedValue.enabled) {
                        ApplicationManager.getApplication().invokeLater {
                            executeQuickFix(workflowId, violation, selectedValue)
                        }
                    }
                    return PopupStep.FINAL_CHOICE
                }
            },
        )
        popup.showUnderneathOf(fixButton)
    }

    private fun executeQuickFix(
        workflowId: String,
        violation: Violation,
        quickFix: SpecGateQuickFixSupport.Presentation,
    ) {
        when (quickFix.descriptor.kind) {
            GateQuickFixKind.OPEN_FOR_MANUAL_EDIT -> {
                if (!SpecWorkflowActionSupport.openGateViolation(project, workflowId, violation)) {
                    SpecWorkflowActionSupport.showInfo(
                        project,
                        SpecCodingBundle.message("spec.action.gate.location.unavailable.title"),
                        gateViolationDetails(violation),
                    )
                }
            }

            GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS -> {
                runRequirementsSectionRepair(workflowId, quickFix)
            }

            GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS -> {
                val payload = quickFix.descriptor.payload as? MissingRequirementsSectionsQuickFixPayload ?: return
                if (onClarifyThenFillRequested?.invoke(workflowId, payload.missingSections) == true) {
                    return
                }
                val sections = missingSectionsDescription(quickFix)
                SpecWorkflowActionSupport.showInfo(
                    project,
                    SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.pending.title"),
                    SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.pending.message", sections),
                )
            }
        }
    }

    private fun quickFixPresentations(violation: Violation): List<SpecGateQuickFixSupport.Presentation> {
        val aiDisabledReason = aiFillUnavailableReasonProvider?.invoke() ?: RequirementsSectionAiSupport.unavailableReason()
        return SpecGateQuickFixSupport.presentations(violation).map { presentation ->
            if (
                presentation.descriptor.kind == GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS &&
                aiDisabledReason != null
            ) {
                presentation.copy(
                    enabled = false,
                    disabledReason = aiDisabledReason,
                )
            } else {
                presentation
            }
        }
    }

    private fun runRequirementsSectionRepair(
        workflowId: String,
        quickFix: SpecGateQuickFixSupport.Presentation,
    ) {
        val payload = quickFix.descriptor.payload as? MissingRequirementsSectionsQuickFixPayload ?: return
        RequirementsSectionRepairUiSupport.previewAndApply(
            project = project,
            workflowId = workflowId,
            missingSections = payload.missingSections,
        )
    }

    private fun missingSectionsDescription(quickFix: SpecGateQuickFixSupport.Presentation): String {
        val payload = quickFix.descriptor.payload as? MissingRequirementsSectionsQuickFixPayload
        return payload
            ?.missingSections
            ?.takeIf { sections -> sections.isNotEmpty() }
            ?.let(RequirementsSectionSupport::describeSections)
            ?: SpecCodingBundle.message("spec.toolwindow.gate.quickFix.missingSections.fallback")
    }

    private fun gateViolationDetails(violation: Violation): String {
        val location = SpecCodingBundle.message("spec.action.gate.review.location", violation.fileName, violation.line)
        val rule = SpecCodingBundle.message("spec.action.gate.review.rule", violation.ruleId)
        val message = violation.message
        val fix = violation.fixHint
            ?.takeIf(String::isNotBlank)
            ?.let { hint -> SpecCodingBundle.message("spec.action.gate.fix", hint) }
        return buildString {
            append(location)
            append('\n')
            append(rule)
            append('\n')
            append(message)
            if (!fix.isNullOrBlank()) {
                append('\n')
                append(fix)
            }
            SpecGateQuickFixSupport.summary(violation)?.let { summary ->
                append('\n')
                append(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.summary", summary))
            }
        }
    }

    private class ViolationCellRenderer : javax.swing.ListCellRenderer<Violation> {
        private val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(8, 10)
            isOpaque = true
        }

        private val severityChipLabel = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder()
            isOpaque = true
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
        }

        private val messageLabel = JBLabel().apply {
            font = JBUI.Fonts.label().deriveFont(12.5f)
            foreground = TITLE_FG
        }

        private val metaLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = META_FG
        }

        private val fixHintLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = META_FG
        }

        private val textPanel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(messageLabel, BorderLayout.NORTH)
            add(metaLabel, BorderLayout.CENTER)
            add(fixHintLabel, BorderLayout.SOUTH)
        }

        init {
            panel.add(severityChipLabel, BorderLayout.WEST)
            panel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out Violation>,
            value: Violation?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value == null) return panel
            messageLabel.text = value.message
            metaLabel.text = "${value.ruleId} · ${value.fileName}:${value.line}"
            val quickFixSummary = SpecGateQuickFixSupport.summary(value)
            val hint = value.fixHint?.trim().orEmpty()
            fixHintLabel.text = when {
                !quickFixSummary.isNullOrBlank() ->
                    SpecCodingBundle.message("spec.toolwindow.gate.quickFix.summary", quickFixSummary)

                hint.isNotEmpty() -> SpecCodingBundle.message("spec.action.gate.fix", hint)
                else -> ""
            }

            applySeverityChipStyle(severityChipLabel, value.severity, isSelected)
            val bg = if (isSelected) SELECTED_BG else ROW_BG
            panel.background = bg
            panel.border = JBUI.Borders.empty(8, 10)
            return panel
        }

        private fun applySeverityChipStyle(label: JBLabel, severity: GateStatus, selected: Boolean) {
            val labelText = when (severity) {
                GateStatus.ERROR -> SpecCodingBundle.message("spec.action.gate.severity.error")
                GateStatus.WARNING -> SpecCodingBundle.message("spec.action.gate.severity.warning")
                GateStatus.PASS -> SpecCodingBundle.message("spec.action.gate.severity.pass")
            }
            val palette = when (severity) {
                GateStatus.ERROR -> ChipPalette(
                    bg = JBColor(Color(252, 235, 236), Color(97, 54, 58)),
                    fg = JBColor(Color(166, 53, 60), Color(250, 196, 200)),
                    border = JBColor(Color(226, 140, 147), Color(145, 92, 98)),
                )

                GateStatus.WARNING -> ChipPalette(
                    bg = JBColor(Color(255, 244, 229), Color(97, 77, 42)),
                    fg = JBColor(Color(150, 96, 0), Color(245, 212, 152)),
                    border = JBColor(Color(232, 191, 111), Color(138, 112, 63)),
                )

                GateStatus.PASS -> ChipPalette(
                    bg = JBColor(Color(233, 246, 237), Color(54, 85, 63)),
                    fg = JBColor(Color(39, 94, 57), Color(194, 232, 204)),
                    border = JBColor(Color(154, 204, 170), Color(87, 134, 102)),
                )
            }
            label.text = labelText
            label.background = if (selected) palette.bg.darker() else palette.bg
            label.foreground = palette.fg
            label.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(palette.border, JBUI.scale(10)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6),
            )
        }

        private data class ChipPalette(
            val bg: Color,
            val fg: Color,
            val border: Color,
        )

        companion object {
            private val TITLE_FG = JBColor(Color(30, 36, 44), Color(225, 229, 235))
            private val META_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            private val ROW_BG = JBColor(Color(255, 255, 255), Color(47, 51, 56))
            private val SELECTED_BG = JBColor(Color(231, 241, 255), Color(59, 77, 92))
        }
    }

    companion object {
        private val HEADER_FG = JBColor(Color(35, 40, 47), Color(222, 226, 232))
        private val HEADER_SECONDARY_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val REFRESHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val PASS_BG = JBColor(Color(233, 246, 237), Color(54, 85, 63))
        private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))
        private val PASS_BORDER = JBColor(Color(154, 204, 170), Color(87, 134, 102))

        private val WARN_BG = JBColor(Color(255, 244, 229), Color(97, 77, 42))
        private val WARN_FG = JBColor(Color(150, 96, 0), Color(245, 212, 152))
        private val WARN_BORDER = JBColor(Color(232, 191, 111), Color(138, 112, 63))

        private val ERROR_BG = JBColor(Color(252, 235, 236), Color(97, 54, 58))
        private val ERROR_FG = JBColor(Color(166, 53, 60), Color(250, 196, 200))
        private val ERROR_BORDER = JBColor(Color(226, 140, 147), Color(145, 92, 98))

        private val NEUTRAL_BG = JBColor(Color(241, 244, 248), Color(72, 77, 85))
        private val NEUTRAL_FG = JBColor(Color(85, 94, 106), Color(192, 199, 208))
        private val NEUTRAL_BORDER = JBColor(Color(201, 208, 217), Color(98, 108, 121))
    }
}
