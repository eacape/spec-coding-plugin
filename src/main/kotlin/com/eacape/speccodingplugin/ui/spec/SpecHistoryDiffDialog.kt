package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import com.intellij.ui.components.JBCheckBox

class SpecHistoryDiffDialog(
    private val phase: SpecPhase,
    private val currentDocument: SpecDocument,
    snapshots: List<SnapshotVersion>,
    private val onDeleteSnapshot: (SnapshotVersion) -> Boolean,
    private val onPruneSnapshots: (Int) -> Int,
    private val onExportSummary: (String) -> Result<String>,
) : DialogWrapper(true) {

    data class SnapshotVersion(
        val snapshotId: String,
        val createdAt: Long,
        val document: SpecDocument,
    ) {
        override fun toString(): String {
            return SpecCodingBundle.message(
                "spec.history.dialog.snapshot.option",
                formatTimestamp(createdAt),
                snapshotId,
            )
        }
    }

    private sealed interface CompareTargetOption

    private object CurrentDocumentTargetOption : CompareTargetOption {
        override fun toString(): String = SpecCodingBundle.message("spec.history.dialog.compareTarget.current")
    }

    private data class SnapshotTargetOption(
        val snapshot: SnapshotVersion,
    ) : CompareTargetOption {
        override fun toString(): String = snapshot.toString()
    }

    private data class ComparisonTargetSelection(
        val displayName: String,
        val content: String,
    )

    private val snapshotCombo = JComboBox(snapshots.toTypedArray())
    private val compareTargetCombo = JComboBox<CompareTargetOption>()
    private val compareTargetLabel = JBLabel(SpecCodingBundle.message("spec.history.dialog.compareTarget"))
    private val statusLabel = JBLabel("")
    private val summaryOnlyCheckBox = JBCheckBox(SpecCodingBundle.message("spec.history.dialog.summaryOnly"), false)
    private val summaryLinesSpinner = JSpinner(SpinnerNumberModel(DEFAULT_SUMMARY_LINES, 1, 500, 1))
    private val keepLatestSpinner = JSpinner(SpinnerNumberModel(DEFAULT_KEEP_LATEST, 0, 500, 1))
    private val deleteSnapshotButton = JButton(SpecCodingBundle.message("spec.history.dialog.deleteSnapshot"))
    private val pruneSnapshotsButton = JButton(SpecCodingBundle.message("spec.history.dialog.pruneSnapshots"))
    private val exportSummaryButton = JButton(SpecCodingBundle.message("spec.history.dialog.exportSummary"))
    private val baselineArea = JBTextArea()
    private val targetArea = JBTextArea()
    private val targetPanelTitle = JBLabel(SpecCodingBundle.message("spec.history.dialog.target"))
    private var updatingCompareTargetOptions = false

    init {
        title = SpecCodingBundle.message("spec.history.dialog.title")
        setupAreas()
        rebuildCompareTargetOptions(preferredSnapshotId = null)
        snapshotCombo.addActionListener { refreshComparison() }
        compareTargetCombo.addActionListener {
            if (!updatingCompareTargetOptions) {
                refreshComparison()
            }
        }
        summaryOnlyCheckBox.addActionListener {
            summaryLinesSpinner.isEnabled = summaryOnlyCheckBox.isSelected
            refreshComparison()
        }
        summaryLinesSpinner.addChangeListener { refreshComparison() }
        deleteSnapshotButton.addActionListener { onDeleteSnapshotClicked() }
        pruneSnapshotsButton.addActionListener { onPruneSnapshotsClicked() }
        exportSummaryButton.addActionListener { onExportSummaryClicked() }
        summaryLinesSpinner.isEnabled = summaryOnlyCheckBox.isSelected
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(520))
        panel.border = JBUI.Borders.empty(8)

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        header.isOpaque = false
        header.add(JBLabel(SpecCodingBundle.message("spec.history.dialog.phase", phase.displayName)))
        header.add(JBLabel(SpecCodingBundle.message("spec.history.dialog.snapshot")))
        header.add(snapshotCombo)
        header.add(compareTargetLabel)
        header.add(compareTargetCombo)
        header.add(summaryOnlyCheckBox)
        header.add(JBLabel(SpecCodingBundle.message("spec.history.dialog.summaryLines")))
        header.add(summaryLinesSpinner)
        header.add(exportSummaryButton)
        header.add(deleteSnapshotButton)
        header.add(JBLabel(SpecCodingBundle.message("spec.history.dialog.keepLatest")))
        header.add(keepLatestSpinner)
        header.add(pruneSnapshotsButton)
        header.add(statusLabel)
        panel.add(header, BorderLayout.NORTH)

        val baselinePanel = JPanel(BorderLayout())
        baselinePanel.add(JBLabel(SpecCodingBundle.message("spec.history.dialog.baseline")), BorderLayout.NORTH)
        baselinePanel.add(JBScrollPane(baselineArea), BorderLayout.CENTER)

        val targetPanel = JPanel(BorderLayout())
        targetPanel.add(targetPanelTitle, BorderLayout.NORTH)
        targetPanel.add(JBScrollPane(targetArea), BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, baselinePanel, targetPanel)
        split.resizeWeight = 0.5
        split.dividerLocation = JBUI.scale(420)
        panel.add(split, BorderLayout.CENTER)

        refreshComparison()
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun setupAreas() {
        baselineArea.isEditable = false
        baselineArea.lineWrap = true
        baselineArea.wrapStyleWord = true

        targetArea.isEditable = false
        targetArea.lineWrap = true
        targetArea.wrapStyleWord = true
    }

    private fun refreshComparison() {
        val selected = snapshotCombo.selectedItem as? SnapshotVersion
        if (selected == null) {
            baselineArea.text = ""
            targetArea.text = buildDisplayContent(currentDocument.content)
            statusLabel.text = SpecCodingBundle.message("spec.history.noSnapshot")
            deleteSnapshotButton.isEnabled = false
            pruneSnapshotsButton.isEnabled = false
            exportSummaryButton.isEnabled = false
            return
        }

        exportSummaryButton.isEnabled = true
        val canDelete = canDeleteSnapshot(selected, currentSnapshots())
        deleteSnapshotButton.isEnabled = canDelete
        deleteSnapshotButton.toolTipText = if (canDelete) {
            null
        } else {
            SpecCodingBundle.message("spec.history.dialog.deleteLatestBlocked")
        }
        pruneSnapshotsButton.isEnabled = snapshotCombo.itemCount > 0

        val baselineContent = selected.document.content
        val targetSelection = resolveTargetSelection()
        val targetContent = targetSelection.content
        val changed = isChanged(baselineContent, targetContent)
        val diffStats = computeLineDiffStats(baselineContent, targetContent)
        val statusText = if (changed) {
            SpecCodingBundle.message("spec.history.diff.changed")
        } else {
            SpecCodingBundle.message("spec.history.diff.unchanged")
        }

        statusLabel.text = SpecCodingBundle.message(
            "spec.history.dialog.status.withStatsAndTarget",
            selected.snapshotId,
            targetSelection.displayName,
            statusText,
            diffStats.addedLines,
            diffStats.removedLines,
        )
        baselineArea.text = buildDisplayContent(baselineContent)
        baselineArea.caretPosition = 0

        targetArea.text = buildDisplayContent(targetContent)
        targetArea.caretPosition = 0
    }

    private fun onDeleteSnapshotClicked() {
        val selected = snapshotCombo.selectedItem as? SnapshotVersion ?: return
        if (!canDeleteSnapshot(selected, currentSnapshots())) {
            val message = SpecCodingBundle.message("spec.history.dialog.deleteLatestBlocked")
            statusLabel.text = message
            Messages.showWarningDialog(
                message,
                SpecCodingBundle.message("spec.history.dialog.deleteSnapshot"),
            )
            return
        }

        val confirm = Messages.showYesNoDialog(
            SpecCodingBundle.message("spec.history.dialog.deleteConfirm", selected.snapshotId),
            SpecCodingBundle.message("spec.history.dialog.deleteSnapshot"),
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) {
            return
        }

        val deleted = onDeleteSnapshot(selected)
        if (!deleted) {
            statusLabel.text = SpecCodingBundle.message("spec.history.dialog.deleteFailed")
            return
        }

        val currentTargetSnapshotId = selectedTargetSnapshotId()
        snapshotCombo.removeItem(selected)
        rebuildCompareTargetOptions(currentTargetSnapshotId)
        statusLabel.text = SpecCodingBundle.message("spec.history.dialog.deleteDone")
        refreshComparison()
    }

    private fun onPruneSnapshotsClicked() {
        val keepLatest = (keepLatestSpinner.value as? Int) ?: DEFAULT_KEEP_LATEST
        val pruned = onPruneSnapshots(keepLatest)
        if (pruned < 0) {
            statusLabel.text = SpecCodingBundle.message("spec.history.dialog.pruneFailed")
            return
        }

        val items = mutableListOf<SnapshotVersion>()
        for (idx in 0 until snapshotCombo.itemCount) {
            val item = snapshotCombo.getItemAt(idx)
            if (item != null) {
                items += item
            }
        }
        val refreshed = items.sortedByDescending { it.createdAt }.take(keepLatest)
        snapshotCombo.removeAllItems()
        refreshed.forEach { snapshotCombo.addItem(it) }

        val targetSnapshotId = selectedTargetSnapshotId()
        rebuildCompareTargetOptions(targetSnapshotId)
        if (snapshotCombo.itemCount > 0) {
            snapshotCombo.selectedIndex = 0
        }
        statusLabel.text = SpecCodingBundle.message("spec.history.dialog.pruneDone", pruned)
        refreshComparison()
    }

    private fun currentSnapshots(): List<SnapshotVersion> {
        val snapshots = mutableListOf<SnapshotVersion>()
        for (idx in 0 until snapshotCombo.itemCount) {
            val item = snapshotCombo.getItemAt(idx)
            if (item != null) {
                snapshots += item
            }
        }
        return snapshots
    }

    private fun buildDisplayContent(content: String): String {
        if (!summaryOnlyCheckBox.isSelected) {
            return content
        }

        val maxLines = (summaryLinesSpinner.value as? Int) ?: DEFAULT_SUMMARY_LINES
        val summary = summarizeTopLines(content, maxLines)
        if (summary.omittedLines <= 0) {
            return summary.text
        }

        return buildString {
            append(summary.text)
            if (summary.text.isNotEmpty()) {
                appendLine()
            }
            append(SpecCodingBundle.message("spec.history.dialog.summary.more", summary.omittedLines))
        }
    }

    private fun resolveTargetSelection(): ComparisonTargetSelection {
        return when (val option = compareTargetCombo.selectedItem as? CompareTargetOption) {
            is SnapshotTargetOption -> {
                ComparisonTargetSelection(
                    displayName = option.snapshot.snapshotId,
                    content = option.snapshot.document.content,
                )
            }

            else -> {
                ComparisonTargetSelection(
                    displayName = SpecCodingBundle.message("spec.history.dialog.compareTarget.current"),
                    content = currentDocument.content,
                )
            }
        }
    }

    private fun selectedTargetSnapshotId(): String? {
        val option = compareTargetCombo.selectedItem as? SnapshotTargetOption
        return option?.snapshot?.snapshotId
    }

    private fun rebuildCompareTargetOptions(preferredSnapshotId: String?) {
        val preferred = preferredSnapshotId ?: selectedTargetSnapshotId()
        updatingCompareTargetOptions = true
        try {
            compareTargetCombo.removeAllItems()
            compareTargetCombo.addItem(CurrentDocumentTargetOption)
            currentSnapshots().forEach { snapshot ->
                compareTargetCombo.addItem(SnapshotTargetOption(snapshot))
            }

            val targetIndex = if (preferred == null) {
                0
            } else {
                (0 until compareTargetCombo.itemCount)
                    .firstOrNull { index ->
                        val option = compareTargetCombo.getItemAt(index)
                        option is SnapshotTargetOption && option.snapshot.snapshotId == preferred
                    } ?: 0
            }
            if (compareTargetCombo.itemCount > 0) {
                compareTargetCombo.selectedIndex = targetIndex
            }
        } finally {
            updatingCompareTargetOptions = false
        }
    }

    private fun onExportSummaryClicked() {
        val baselineSnapshot = snapshotCombo.selectedItem as? SnapshotVersion ?: return
        val targetSelection = resolveTargetSelection()
        val maxLines = (summaryLinesSpinner.value as? Int) ?: DEFAULT_SUMMARY_LINES
        val summary = buildComparisonSummary(
            baselineContent = baselineSnapshot.document.content,
            targetContent = targetSelection.content,
            maxLines = maxLines,
        )
        val exportContent = buildExportContent(
            phaseDisplayName = phase.displayName,
            baselineLabel = baselineSnapshot.snapshotId,
            targetLabel = targetSelection.displayName,
            summary = summary,
        )

        exportSummaryButton.isEnabled = false
        statusLabel.text = SpecCodingBundle.message("spec.history.dialog.exporting")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = onExportSummary(exportContent)
            ApplicationManager.getApplication().invokeLater {
                exportSummaryButton.isEnabled = snapshotCombo.itemCount > 0
                result.onSuccess { fileName ->
                    statusLabel.text = SpecCodingBundle.message("spec.history.dialog.exportDone", fileName)
                }.onFailure { error ->
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.history.dialog.exportFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun buildExportContent(
        phaseDisplayName: String,
        baselineLabel: String,
        targetLabel: String,
        summary: ComparisonSummary,
    ): String {
        val statusText = if (summary.changed) {
            SpecCodingBundle.message("spec.history.diff.changed")
        } else {
            SpecCodingBundle.message("spec.history.diff.unchanged")
        }
        return buildString {
            appendLine("# ${SpecCodingBundle.message("spec.history.export.title")}")
            appendLine()
            appendLine(SpecCodingBundle.message("spec.history.export.phase", phaseDisplayName))
            appendLine(SpecCodingBundle.message("spec.history.export.baseline", baselineLabel))
            appendLine(SpecCodingBundle.message("spec.history.export.target", targetLabel))
            appendLine(SpecCodingBundle.message("spec.history.export.status", statusText))
            appendLine(SpecCodingBundle.message("spec.history.export.added", summary.addedLines))
            appendLine(SpecCodingBundle.message("spec.history.export.removed", summary.removedLines))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.history.export.baselineSnippet"))
            appendLine()
            appendLine(renderSummaryForExport(summary.baselineSummary))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.history.export.targetSnippet"))
            appendLine()
            append(renderSummaryForExport(summary.targetSummary))
        }
    }

    private fun renderSummaryForExport(summary: SummaryResult): String {
        if (summary.omittedLines <= 0) {
            return summary.text
        }
        return buildString {
            append(summary.text)
            if (summary.text.isNotEmpty()) {
                appendLine()
            }
            append(SpecCodingBundle.message("spec.history.dialog.summary.more", summary.omittedLines))
        }
    }

    companion object {
        private const val DEFAULT_SUMMARY_LINES = 40
        private const val DEFAULT_KEEP_LATEST = 5

        internal data class LineDiffStats(
            val addedLines: Int,
            val removedLines: Int,
        )

        internal data class SummaryResult(
            val text: String,
            val omittedLines: Int,
        )

        internal data class ComparisonSummary(
            val changed: Boolean,
            val addedLines: Int,
            val removedLines: Int,
            val baselineSummary: SummaryResult,
            val targetSummary: SummaryResult,
        )

        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        internal fun isChanged(
            baselineContent: String,
            currentContent: String,
        ): Boolean {
            return baselineContent.replace("\r\n", "\n").trim() != currentContent.replace("\r\n", "\n").trim()
        }

        internal fun summarizeTopLines(
            content: String,
            maxLines: Int,
        ): SummaryResult {
            val normalized = content.replace("\r\n", "\n").trim()
            if (normalized.isBlank()) {
                return SummaryResult("", 0)
            }

            val lines = normalized.split("\n")
            if (lines.size <= maxLines) {
                return SummaryResult(normalized, 0)
            }

            return SummaryResult(
                text = lines.take(maxLines).joinToString("\n"),
                omittedLines = lines.size - maxLines,
            )
        }

        internal fun computeLineDiffStats(
            baselineContent: String,
            currentContent: String,
        ): LineDiffStats {
            val baselineLines = normalizeLines(baselineContent)
            val currentLines = normalizeLines(currentContent)

            if (baselineLines.isEmpty() && currentLines.isEmpty()) {
                return LineDiffStats(0, 0)
            }

            val lcsLength = lcsLength(baselineLines, currentLines)
            val removed = baselineLines.size - lcsLength
            val added = currentLines.size - lcsLength
            return LineDiffStats(
                addedLines = added.coerceAtLeast(0),
                removedLines = removed.coerceAtLeast(0),
            )
        }

        internal fun buildComparisonSummary(
            baselineContent: String,
            targetContent: String,
            maxLines: Int,
        ): ComparisonSummary {
            val normalizedMaxLines = maxLines.coerceAtLeast(1)
            val stats = computeLineDiffStats(baselineContent, targetContent)
            return ComparisonSummary(
                changed = isChanged(baselineContent, targetContent),
                addedLines = stats.addedLines,
                removedLines = stats.removedLines,
                baselineSummary = summarizeTopLines(baselineContent, normalizedMaxLines),
                targetSummary = summarizeTopLines(targetContent, normalizedMaxLines),
            )
        }

        internal fun canDeleteSnapshot(
            selected: SnapshotVersion,
            snapshots: List<SnapshotVersion>,
        ): Boolean {
            val latest = snapshots.maxByOrNull { it.createdAt } ?: return false
            return latest.snapshotId != selected.snapshotId
        }

        private fun normalizeLines(content: String): List<String> {
            val normalized = content.replace("\r\n", "\n").trim()
            if (normalized.isBlank()) {
                return emptyList()
            }
            return normalized.split("\n")
        }

        private fun lcsLength(
            baselineLines: List<String>,
            currentLines: List<String>,
        ): Int {
            val rows = baselineLines.size
            val cols = currentLines.size
            val dp = Array(rows + 1) { IntArray(cols + 1) }

            for (i in 1..rows) {
                for (j in 1..cols) {
                    dp[i][j] = if (baselineLines[i - 1] == currentLines[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
                }
            }

            return dp[rows][cols]
        }

        private fun formatTimestamp(timestamp: Long): String {
            return formatter.format(Instant.ofEpochMilli(timestamp))
        }
    }
}
