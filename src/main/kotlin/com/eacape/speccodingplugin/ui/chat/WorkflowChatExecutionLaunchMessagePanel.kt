package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchFallbackReason
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLegacyCompactNotice
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchPresentation
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSection
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSectionKind
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

internal class WorkflowChatExecutionLaunchMessagePanel(
    private val payload: WorkflowChatExecutionLaunchRestorePayload,
    visibleContent: String,
    private val rawPromptContent: String? = null,
    private val onDeleteMessage: ((ChatMessagePanel) -> Unit)? = null,
) : ChatMessagePanel(
    role = MessageRole.USER,
    initialContent = visibleContent,
    onDelete = onDeleteMessage,
) {

    private val wrapper = JPanel(BorderLayout())
    private val bodyPanel = JPanel()
    private val metaChipPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))
    private val userNotePanel = JPanel()
    private val systemContextPanel = JPanel(BorderLayout())
    private val systemContextSummaryPanel = JPanel()
    private val systemContextDetailsPanel = JPanel()
    private val systemContextToggleButton = JButton()
    private val debugPanel = JPanel()
    private val rawPromptContainer = JPanel(BorderLayout())
    private val rawPromptToggleButton = JButton()
    private val notesPanel = JPanel()
    private val titleLabel = JBLabel(SpecCodingBundle.message("chat.execution.launch.title"), AllIcons.Actions.Execute, JBLabel.LEADING)
    private val summaryLabel = JBLabel(SpecCodingBundle.message("chat.execution.launch.summary"))
    private var systemContextExpanded = false
    private var rawPromptVisible = false

    init {
        removeAll()
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)

        wrapper.isOpaque = true
        wrapper.background = CARD_BG
        wrapper.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(CARD_BORDER, 1, 1, 1, 1),
            JBUI.Borders.empty(8, 10, 8, 10),
        )

        val header = createHeader()
        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.isOpaque = false

        metaChipPanel.isOpaque = false
        metaChipPanel.alignmentX = LEFT_ALIGNMENT

        userNotePanel.layout = BoxLayout(userNotePanel, BoxLayout.Y_AXIS)
        userNotePanel.isOpaque = false
        userNotePanel.alignmentX = LEFT_ALIGNMENT

        systemContextPanel.isOpaque = false
        systemContextPanel.alignmentX = LEFT_ALIGNMENT

        systemContextSummaryPanel.layout = BoxLayout(systemContextSummaryPanel, BoxLayout.Y_AXIS)
        systemContextSummaryPanel.isOpaque = false

        systemContextDetailsPanel.layout = BoxLayout(systemContextDetailsPanel, BoxLayout.Y_AXIS)
        systemContextDetailsPanel.isOpaque = false

        debugPanel.layout = BoxLayout(debugPanel, BoxLayout.Y_AXIS)
        debugPanel.isOpaque = false
        debugPanel.alignmentX = LEFT_ALIGNMENT

        rawPromptContainer.isOpaque = false
        rawPromptContainer.alignmentX = LEFT_ALIGNMENT

        notesPanel.layout = BoxLayout(notesPanel, BoxLayout.Y_AXIS)
        notesPanel.isOpaque = false
        notesPanel.alignmentX = LEFT_ALIGNMENT

        bodyPanel.add(metaChipPanel)
        bodyPanel.add(userNotePanel)
        bodyPanel.add(systemContextPanel)
        bodyPanel.add(debugPanel)
        bodyPanel.add(notesPanel)

        wrapper.add(header, BorderLayout.NORTH)
        wrapper.add(bodyPanel, BorderLayout.CENTER)
        add(wrapper, BorderLayout.CENTER)

        renderCard()
    }

    internal fun snapshotForTest(): Map<String, String> {
        return when (val current = payload) {
            is WorkflowChatExecutionLaunchRestorePayload.Presentation -> mapOf(
                "visible" to "true",
                "kind" to "presentation",
                "workflowId" to current.launch.workflowId,
                "taskId" to current.launch.taskId,
                "runId" to current.launch.runId,
                "taskTitle" to current.launch.taskTitle,
                "focusedStage" to current.launch.focusedStage.name,
                "trigger" to current.launch.trigger.name,
                "sectionKinds" to current.launch.sections.joinToString(",") { it.kind.name },
                "rawPromptDebugAvailable" to current.launch.rawPromptDebugAvailable.toString(),
                "userNoteVisible" to (userNotePanel.componentCount > 0).toString(),
                "systemContextExpanded" to systemContextExpanded.toString(),
                "debugEntryVisible" to rawPromptToggleButton.isVisible.toString(),
                "rawPromptVisible" to rawPromptVisible.toString(),
                "content" to getContent(),
            )

            is WorkflowChatExecutionLaunchRestorePayload.LegacyCompact -> mapOf(
                "visible" to "true",
                "kind" to "legacy",
                "workflowId" to current.notice.workflowId.orEmpty(),
                "taskId" to current.notice.taskId.orEmpty(),
                "runId" to current.notice.runId.orEmpty(),
                "taskTitle" to current.notice.taskTitle.orEmpty(),
                "focusedStage" to current.notice.focusedStage?.name.orEmpty(),
                "trigger" to current.notice.trigger?.name.orEmpty(),
                "sectionKinds" to current.notice.sectionKinds.joinToString(",") { it.name },
                "fallbackReason" to current.notice.fallbackReason.name,
                "rawPromptDebugAvailable" to current.notice.rawPromptDebugAvailable.toString(),
                "userNoteVisible" to (userNotePanel.componentCount > 0).toString(),
                "systemContextExpanded" to systemContextExpanded.toString(),
                "debugEntryVisible" to rawPromptToggleButton.isVisible.toString(),
                "rawPromptVisible" to rawPromptVisible.toString(),
                "content" to getContent(),
            )
        }
    }

    internal fun toggleSystemContextForTest() {
        systemContextToggleButton.doClick()
    }

    internal fun toggleRawPromptForTest() {
        rawPromptToggleButton.doClick()
    }

    private fun createHeader(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.isOpaque = false

        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
        titleLabel.foreground = TITLE_FG
        titleLabel.iconTextGap = JBUI.scale(6)
        left.add(titleLabel)

        summaryLabel.font = summaryLabel.font.deriveFont(11f)
        summaryLabel.foreground = SUMMARY_FG
        left.add(summaryLabel)

        panel.add(left, BorderLayout.CENTER)

        if (onDeleteMessage != null) {
            val deleteButton = JButton(AllIcons.General.Remove).apply {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                toolTipText = SpecCodingBundle.message("chat.message.delete")
                addActionListener { onDeleteMessage.invoke(this@WorkflowChatExecutionLaunchMessagePanel) }
            }
            panel.add(deleteButton, BorderLayout.EAST)
        }
        return panel
    }

    private fun renderCard() {
        metaChipPanel.removeAll()
        userNotePanel.removeAll()
        systemContextPanel.removeAll()
        systemContextSummaryPanel.removeAll()
        systemContextDetailsPanel.removeAll()
        debugPanel.removeAll()
        rawPromptContainer.removeAll()
        notesPanel.removeAll()

        when (val current = payload) {
            is WorkflowChatExecutionLaunchRestorePayload.Presentation -> renderPresentation(current.launch)
            is WorkflowChatExecutionLaunchRestorePayload.LegacyCompact -> renderLegacy(current.notice)
        }

        revalidate()
        repaint()
    }

    private fun renderPresentation(launch: WorkflowChatExecutionLaunchPresentation) {
        metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.workflow", launch.workflowId)))
        metaChipPanel.add(
            createMetaChip(
                SpecCodingBundle.message(
                    "chat.execution.launch.meta.task",
                    launch.taskId,
                    launch.taskTitle.ifBlank { launch.taskId },
                ),
            ),
        )
        metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.run", launch.runId)))
        metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.stage", launch.focusedStage.name)))
        metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.trigger", triggerLabel(launch.trigger))))
        launch.taskStatusBeforeExecution?.let { status ->
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.status", status.name)))
        }
        launch.taskPriority?.let { priority ->
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.priority", priority.name)))
        }

        renderUserNote(launch.supplementalInstruction)

        val visibleSections = launch.sections.filter { section ->
            section.itemCount > 0 || !section.emptyStateReason.isNullOrBlank()
        }
        renderSystemContext(
            summaryLines = buildContextSummaryLines(visibleSections),
            detailPanels = visibleSections.map { section ->
                createSectionPanel(sectionLabel(section.kind), sectionPreview(section), sectionBadgeText(section))
            },
            badgeText = visibleSections.size.takeIf { it > 0 }?.toString(),
            allowExpand = visibleSections.isNotEmpty(),
        )

        launch.degradationReasons.forEach { reason ->
            notesPanel.add(createNoteLabel(reason))
        }
        renderDebugEntry(launch.rawPromptDebugAvailable)
    }

    private fun renderLegacy(notice: WorkflowChatExecutionLegacyCompactNotice) {
        notice.workflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.workflow", workflowId)))
        }
        notice.taskId?.takeIf(String::isNotBlank)?.let { taskId ->
            val taskTitle = notice.taskTitle?.ifBlank { taskId } ?: taskId
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.task", taskId, taskTitle)))
        }
        notice.runId?.takeIf(String::isNotBlank)?.let { runId ->
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.run", runId)))
        }
        notice.focusedStage?.let { stage ->
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.stage", stage.name)))
        }
        notice.trigger?.let { trigger ->
            metaChipPanel.add(createMetaChip(SpecCodingBundle.message("chat.execution.launch.meta.trigger", triggerLabel(trigger))))
        }

        if (notice.supplementalInstructionPresent) {
            renderUserNote(SpecCodingBundle.message("chat.execution.launch.note.legacy.userNote"))
        }

        renderSystemContext(
            summaryLines = if (notice.sectionKinds.isEmpty()) {
                listOf(SpecCodingBundle.message("chat.execution.launch.section.empty"))
            } else {
                notice.sectionKinds.map { kind -> sectionLabel(kind) }
            },
            detailPanels = emptyList(),
            badgeText = notice.sectionKinds.size.takeIf { it > 0 }?.toString(),
            allowExpand = false,
        )

        notesPanel.add(createNoteLabel(SpecCodingBundle.message("chat.execution.launch.note.legacy")))
        notesPanel.add(createNoteLabel(legacyReasonLabel(notice.fallbackReason)))
        renderDebugEntry(notice.rawPromptDebugAvailable)
    }

    private fun renderUserNote(userNote: String?) {
        val normalized = userNote?.trim()?.takeIf(String::isNotBlank) ?: return
        userNotePanel.add(
            createSectionPanel(
                title = SpecCodingBundle.message("chat.execution.launch.section.userNote"),
                detail = normalized,
                badgeText = null,
            ),
        )
    }

    private fun renderSystemContext(
        summaryLines: List<String>,
        detailPanels: List<JPanel>,
        badgeText: String?,
        allowExpand: Boolean,
    ) {
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.border = JBUI.Borders.emptyTop(8)

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(
                JBLabel(SpecCodingBundle.message("chat.execution.launch.section.systemContext")).apply {
                    foreground = TITLE_FG
                    font = font.deriveFont(Font.BOLD, 11f)
                },
            )
            badgeText?.takeIf(String::isNotBlank)?.let { badge ->
                add(
                    JBLabel(badge).apply {
                        foreground = SUMMARY_FG
                        font = font.deriveFont(10f)
                    },
                )
            }
        }
        header.add(headerLeft, BorderLayout.WEST)

        systemContextToggleButton.apply {
            isVisible = allowExpand
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            foreground = ACTION_FG
            font = JBUI.Fonts.smallFont()
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener {
                systemContextExpanded = !systemContextExpanded
                refreshSystemContextExpansion()
            }
        }
        if (allowExpand) {
            header.add(systemContextToggleButton, BorderLayout.EAST)
        }

        summaryLines.forEach { line ->
            systemContextSummaryPanel.add(createWrappedLabel(line))
        }

        detailPanels.forEach(systemContextDetailsPanel::add)

        systemContextPanel.add(header, BorderLayout.NORTH)
        systemContextPanel.add(systemContextSummaryPanel, BorderLayout.CENTER)
        if (detailPanels.isNotEmpty()) {
            systemContextPanel.add(systemContextDetailsPanel, BorderLayout.SOUTH)
        }
        refreshSystemContextExpansion()
    }

    private fun refreshSystemContextExpansion() {
        systemContextDetailsPanel.isVisible = systemContextExpanded && systemContextDetailsPanel.componentCount > 0
        systemContextToggleButton.text = if (systemContextExpanded) {
            SpecCodingBundle.message("chat.execution.launch.action.hideContext")
        } else {
            SpecCodingBundle.message("chat.execution.launch.action.showContext")
        }
        systemContextPanel.revalidate()
        systemContextPanel.repaint()
    }

    private fun renderDebugEntry(rawPromptDebugAvailable: Boolean) {
        if (!rawPromptDebugAvailable) {
            return
        }
        val normalizedRawPrompt = rawPromptContent?.trim()?.takeIf(String::isNotBlank)
        debugPanel.add(createNoteLabel(SpecCodingBundle.message("chat.execution.launch.note.rawPromptHidden")))

        rawPromptToggleButton.apply {
            isVisible = normalizedRawPrompt != null
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            foreground = ACTION_FG
            font = JBUI.Fonts.smallFont()
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener {
                rawPromptVisible = !rawPromptVisible
                refreshRawPromptVisibility(normalizedRawPrompt)
            }
        }
        if (normalizedRawPrompt != null) {
            debugPanel.add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(4))).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(4)
                    add(rawPromptToggleButton)
                },
            )
            debugPanel.add(rawPromptContainer)
            refreshRawPromptVisibility(normalizedRawPrompt)
        }
    }

    private fun refreshRawPromptVisibility(rawPrompt: String?) {
        rawPromptContainer.removeAll()
        rawPromptToggleButton.text = if (rawPromptVisible) {
            SpecCodingBundle.message("chat.execution.launch.action.hidePrompt")
        } else {
            SpecCodingBundle.message("chat.execution.launch.action.inspectPrompt")
        }
        if (rawPromptVisible && !rawPrompt.isNullOrBlank()) {
            val area = JTextArea(rawPrompt).apply {
                isEditable = false
                isOpaque = false
                lineWrap = false
                wrapStyleWord = false
                isFocusable = false
                foreground = BODY_FG
                font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 11f)
                border = JBUI.Borders.empty(0, 0, 0, 0)
            }
            rawPromptContainer.add(
                JBScrollPane(area).apply {
                    isOpaque = false
                    viewport.isOpaque = false
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLine(CARD_BORDER, 1),
                        JBUI.Borders.empty(6, 6, 6, 6),
                    )
                    preferredSize = JBUI.size(420, 180)
                    alignmentX = LEFT_ALIGNMENT
                },
                BorderLayout.CENTER,
            )
        }
        rawPromptContainer.isVisible = rawPromptVisible && !rawPrompt.isNullOrBlank()
        debugPanel.revalidate()
        debugPanel.repaint()
    }

    private fun buildContextSummaryLines(
        sections: List<WorkflowChatExecutionPresentationSection>,
    ): List<String> {
        if (sections.isEmpty()) {
            return listOf(SpecCodingBundle.message("chat.execution.launch.section.empty"))
        }
        return sections.map { section ->
            val headline = section.previewItems.firstOrNull()
                ?: section.emptyStateReason
                ?: SpecCodingBundle.message("chat.execution.launch.section.empty")
            val countLabel = sectionBadgeText(section)?.let { " [$it]" }.orEmpty()
            "${sectionLabel(section.kind)}$countLabel: $headline"
        }
    }

    private fun createMetaChip(text: String): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = CHIP_BG
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(CHIP_BORDER, 1),
                JBUI.Borders.empty(2, 6, 2, 6),
            )
            add(
                JBLabel(text).apply {
                    foreground = CHIP_FG
                    font = font.deriveFont(11f)
                },
            )
        }
    }

    private fun createSectionPanel(
        title: String,
        detail: String,
        badgeText: String?,
    ): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyTop(8)

        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        header.isOpaque = false
        header.add(
            JBLabel(title).apply {
                foreground = TITLE_FG
                font = font.deriveFont(Font.BOLD, 11f)
            },
        )
        badgeText?.takeIf(String::isNotBlank)?.let { badge ->
            header.add(
                JBLabel(badge).apply {
                    foreground = SUMMARY_FG
                    font = font.deriveFont(10f)
                },
            )
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(createWrappedLabel(detail), BorderLayout.CENTER)
        return panel
    }

    private fun createWrappedLabel(text: String): JTextArea {
        return JTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            isFocusable = false
            foreground = BODY_FG
            font = JBUI.Fonts.label().deriveFont(11f)
            border = JBUI.Borders.empty(2, 0, 0, 0)
        }
    }

    private fun createNoteLabel(text: String): JTextArea {
        return createWrappedLabel(text).apply {
            foreground = SUMMARY_FG
            border = JBUI.Borders.emptyTop(8)
        }
    }

    private fun sectionLabel(kind: WorkflowChatExecutionPresentationSectionKind): String {
        return when (kind) {
            WorkflowChatExecutionPresentationSectionKind.DEPENDENCIES ->
                SpecCodingBundle.message("chat.execution.launch.section.dependencies")

            WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES ->
                SpecCodingBundle.message("chat.execution.launch.section.artifactSummaries")

            WorkflowChatExecutionPresentationSectionKind.CLARIFICATION_CONCLUSIONS ->
                SpecCodingBundle.message("chat.execution.launch.section.clarificationConclusions")

            WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT ->
                SpecCodingBundle.message("chat.execution.launch.section.codeContext")
        }
    }

    private fun sectionBadgeText(section: WorkflowChatExecutionPresentationSection): String? {
        val count = section.itemCount.takeIf { it > 0 } ?: return null
        return buildString {
            append(count)
            if (section.truncated) {
                append("+")
            }
        }
    }

    private fun sectionPreview(section: WorkflowChatExecutionPresentationSection): String {
        val previewItems = section.previewItems.map { item -> "- $item" }
        val lines = if (previewItems.isNotEmpty()) {
            previewItems.toMutableList()
        } else {
            mutableListOf(section.emptyStateReason ?: SpecCodingBundle.message("chat.execution.launch.section.empty"))
        }
        if (section.truncated) {
            lines += SpecCodingBundle.message("chat.execution.launch.note.moreItems")
        }
        return lines.joinToString(separator = "\n")
    }

    private fun triggerLabel(trigger: ExecutionTrigger): String {
        return when (trigger) {
            ExecutionTrigger.USER_EXECUTE -> SpecCodingBundle.message("chat.execution.launch.trigger.execute")
            ExecutionTrigger.USER_RETRY -> SpecCodingBundle.message("chat.execution.launch.trigger.retry")
            ExecutionTrigger.SYSTEM_RECOVERY -> SpecCodingBundle.message("chat.execution.launch.trigger.recovery")
        }
    }

    private fun legacyReasonLabel(reason: WorkflowChatExecutionLaunchFallbackReason): String {
        return when (reason) {
            WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA ->
                SpecCodingBundle.message("chat.execution.launch.note.legacy.missingPresentation")

            WorkflowChatExecutionLaunchFallbackReason.UNRECOGNIZED_LEGACY_PROMPT ->
                SpecCodingBundle.message("chat.execution.launch.note.legacy.unrecognized")
        }
    }

    companion object {
        private val CARD_BG = JBColor(Color(243, 248, 255), Color(38, 47, 59))
        private val CARD_BORDER = JBColor(Color(198, 213, 232), Color(82, 94, 111))
        private val CHIP_BG = JBColor(Color(234, 241, 252), Color(54, 64, 77))
        private val CHIP_BORDER = JBColor(Color(201, 214, 230), Color(88, 99, 113))
        private val CHIP_FG = JBColor(Color(63, 80, 104), Color(201, 210, 224))
        private val TITLE_FG = JBColor(Color(49, 66, 92), Color(225, 232, 243))
        private val SUMMARY_FG = JBColor(Color(104, 118, 140), Color(159, 170, 186))
        private val BODY_FG = JBColor(Color(72, 84, 103), Color(213, 220, 231))
        private val ACTION_FG = JBColor(Color(41, 104, 174), Color(122, 181, 242))
    }
}
