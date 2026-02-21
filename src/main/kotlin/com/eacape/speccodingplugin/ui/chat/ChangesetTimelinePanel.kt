package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.ChangesetChangedEvent
import com.eacape.speccodingplugin.rollback.ChangesetChangedListener
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.FileChange
import com.eacape.speccodingplugin.rollback.RollbackManager
import com.eacape.speccodingplugin.rollback.RollbackOptions
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * 变更时间线面板
 * 展示 AI 操作的变更历史，支持回滚操作
 */
class ChangesetTimelinePanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val store = ChangesetStore.getInstance(project)
    private val rollbackManager = RollbackManager.getInstance(project)
    private val timelineContainer = JPanel()
    private val formatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        border = JBUI.Borders.empty(6)
        setupUI()
        subscribeToChangesetEvents()
        refresh()
    }

    private fun setupUI() {
        // 时间线容器
        timelineContainer.layout = BoxLayout(
            timelineContainer, BoxLayout.Y_AXIS
        )
        timelineContainer.border = JBUI.Borders.empty(0, 2)

        val scrollPane = JBScrollPane(timelineContainer)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.verticalScrollBar.unitIncrement = 16

        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * 刷新时间线
     */
    fun refresh() {
        val changesets = store.getRecent(20)
        timelineContainer.removeAll()

        if (changesets.isEmpty()) {
            val emptyLabel = JBLabel(SpecCodingBundle.message("changeset.timeline.empty"))
            emptyLabel.foreground = SECONDARY_TEXT_COLOR
            emptyLabel.border = JBUI.Borders.empty(10)
            timelineContainer.add(emptyLabel)
        } else {
            val total = changesets.size
            changesets.forEachIndexed { index, changeset ->
                timelineContainer.add(createChangesetRow(changeset, total - index))
            }
            timelineContainer.add(Box.createVerticalGlue())
        }

        timelineContainer.revalidate()
        timelineContainer.repaint()
    }

    private fun createChangesetRow(
        changeset: Changeset,
        displayIndex: Int,
    ): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = ROW_BACKGROUND
        row.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ROW_DIVIDER, 0, 0, 1, 0),
            JBUI.Borders.empty(4, 8),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        leftHeader.isOpaque = false

        val indexLabel = JBLabel("[$displayIndex]")
        indexLabel.foreground = INDEX_COLOR
        indexLabel.font = indexLabel.font.deriveFont(Font.BOLD, 12f)
        leftHeader.add(indexLabel)

        val descLabel = JBLabel(resolveDescription(changeset))
        descLabel.font = descLabel.font.deriveFont(
            java.awt.Font.BOLD, 11.5f
        )
        leftHeader.add(descLabel)
        header.add(leftHeader, BorderLayout.WEST)

        val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        rightHeader.isOpaque = false
        val timeStr = formatter.format(changeset.timestamp)
        val timeLabel = JBLabel(timeStr)
        timeLabel.foreground = SECONDARY_TEXT_COLOR
        timeLabel.font = timeLabel.font.deriveFont(10.5f)
        rightHeader.add(timeLabel)

        if (changeset.changes.isNotEmpty()) {
            val rollbackBtn = JButton()
            styleIconActionButton(
                rollbackBtn,
                AllIcons.Actions.Undo,
                SpecCodingBundle.message("changeset.timeline.rollback"),
            )
            rollbackBtn.addActionListener { performRollback(changeset.id) }
            rightHeader.add(rollbackBtn)
        }

        val deleteBtn = JButton()
        styleIconActionButton(
            deleteBtn,
            AllIcons.Actions.GC,
            SpecCodingBundle.message("changeset.timeline.remove"),
        )
        deleteBtn.addActionListener { store.delete(changeset.id) }
        rightHeader.add(deleteBtn)
        header.add(rightHeader, BorderLayout.EAST)

        val secondaryLabel = JBLabel(buildSecondaryLineText(changeset)).apply {
            foreground = SECONDARY_TEXT_COLOR
            font = font.deriveFont(Font.PLAIN, 10f)
            border = JBUI.Borders.emptyTop(1)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val content = JPanel(GridBagLayout())
        content.isOpaque = false
        addRowLine(content, 0, header)
        addRowLine(content, 1, secondaryLabel)

        row.add(content, BorderLayout.CENTER)
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = java.awt.Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun addRowLine(parent: JPanel, rowIndex: Int, component: JComponent) {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = rowIndex
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = if (rowIndex == 0) JBUI.insets(0) else JBUI.insetsTop(2)
        }
        parent.add(component, constraints)
    }

    private fun styleIconActionButton(button: JButton, icon: Icon, tooltip: String) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.margin = JBUI.insets(0)
        button.icon = icon
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.preferredSize = JBUI.size(20, 20)
        button.minimumSize = JBUI.size(20, 20)
    }

    private fun buildSecondaryLineText(changeset: Changeset): String {
        val parts = mutableListOf<String>()
        parts += SpecCodingBundle.message("changeset.timeline.affectedFiles", changeset.changes.size)
        changeset.metadata["source"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += SpecCodingBundle.message("changeset.timeline.meta.source", resolveSourceText(it)) }
        changeset.metadata["status"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += SpecCodingBundle.message("changeset.timeline.meta.status", resolveStatusText(it)) }
        changeset.metadata["exitCode"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += SpecCodingBundle.message("changeset.timeline.meta.exitCode", it) }
        changeset.changes.firstOrNull()?.let { first ->
            val filePart = SpecCodingBundle.message(
                "changeset.timeline.file.entry",
                resolveFileTypeText(first.changeType),
                toDisplayPath(first.filePath),
            )
            parts += filePart
            val more = changeset.changes.size - 1
            if (more > 0) {
                parts += SpecCodingBundle.message("changeset.timeline.more", more)
            }
        }
        return parts.joinToString("  ·  ")
    }

    private fun resolveFileTypeText(changeType: FileChange.ChangeType): String {
        return when (changeType) {
            FileChange.ChangeType.CREATED -> SpecCodingBundle.message("changeset.timeline.file.type.created")
            FileChange.ChangeType.MODIFIED -> SpecCodingBundle.message("changeset.timeline.file.type.modified")
            FileChange.ChangeType.DELETED -> SpecCodingBundle.message("changeset.timeline.file.type.deleted")
        }
    }

    private fun resolveSourceText(source: String): String {
        return when (source.lowercase()) {
            "workflow-command" -> SpecCodingBundle.message("changeset.timeline.source.workflowCommand")
            "assistant-response" -> SpecCodingBundle.message("changeset.timeline.source.assistantResponse")
            else -> source
        }
    }

    private fun resolveStatusText(status: String): String {
        return when (status.lowercase()) {
            "success" -> SpecCodingBundle.message("changeset.timeline.status.success")
            "failed" -> SpecCodingBundle.message("changeset.timeline.status.failed")
            "timeout" -> SpecCodingBundle.message("changeset.timeline.status.timeout")
            "stopped" -> SpecCodingBundle.message("changeset.timeline.status.stopped")
            "error" -> SpecCodingBundle.message("changeset.timeline.status.error")
            "no-file-change" -> SpecCodingBundle.message("changeset.timeline.status.noFileChange")
            else -> status
        }
    }

    private fun resolveDescription(changeset: Changeset): String {
        val command = changeset.metadata["command"]?.trim().orEmpty()
        if (command.isNotEmpty()) {
            return SpecCodingBundle.message("changeset.timeline.command.title", compactText(command, COMMAND_MAX_LENGTH))
        }
        return compactText(changeset.description, COMMAND_MAX_LENGTH + 9)
    }

    private fun toDisplayPath(path: String): String {
        val normalized = path.replace('\\', '/')
        val projectBase = project.basePath?.replace('\\', '/')
            ?.trimEnd('/')
            .orEmpty()
        if (projectBase.isBlank()) {
            return compactText(normalized, DISPLAY_PATH_MAX_LENGTH)
        }
        val prefix = "$projectBase/"
        val relative = if (normalized.startsWith(prefix, ignoreCase = true)) {
            normalized.substring(prefix.length)
        } else {
            normalized
        }
        return compactText(relative, DISPLAY_PATH_MAX_LENGTH)
    }

    private fun compactText(value: String, maxLength: Int): String {
        val normalized = value.trim()
        if (normalized.length <= maxLength) {
            return normalized
        }
        val head = max(8, maxLength / 2 - 2)
        val tail = max(8, maxLength - head - 3)
        return normalized.take(head) + "..." + normalized.takeLast(tail)
    }

    private fun performRollback(changesetId: String) {
        val result = rollbackManager.rollback(
            changesetId,
            RollbackOptions(createBackup = true)
        )

        SwingUtilities.invokeLater {
            showRollbackNotification(result)
            refresh()
        }
    }

    private fun showRollbackNotification(result: com.eacape.speccodingplugin.rollback.RollbackResult) {
        val type = when (result) {
            is com.eacape.speccodingplugin.rollback.RollbackResult.Success -> NotificationType.INFORMATION
            is com.eacape.speccodingplugin.rollback.RollbackResult.PartialSuccess -> NotificationType.WARNING
            is com.eacape.speccodingplugin.rollback.RollbackResult.Failure -> NotificationType.ERROR
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(result.getSummary(), type)
            .notify(project)
    }

    private fun subscribeToChangesetEvents() {
        project.messageBus.connect(this).subscribe(
            ChangesetChangedListener.TOPIC,
            object : ChangesetChangedListener {
                override fun onChanged(event: ChangesetChangedEvent) {
                    SwingUtilities.invokeLater {
                        if (!project.isDisposed) {
                            refresh()
                        }
                    }
                }
            },
        )
    }

    override fun dispose() {
    }

    companion object {
        private val ROW_BACKGROUND = JBColor(
            java.awt.Color(248, 249, 252),
            java.awt.Color(50, 53, 60),
        )
        private val ROW_DIVIDER = JBColor(
            java.awt.Color(214, 220, 230),
            java.awt.Color(78, 84, 96),
        )
        private val SECONDARY_TEXT_COLOR = JBColor(
            java.awt.Color(102, 111, 126),
            java.awt.Color(154, 162, 178),
        )
        private val INDEX_COLOR = JBColor(
            java.awt.Color(18, 103, 192),
            java.awt.Color(111, 170, 255),
        )
        private const val DISPLAY_PATH_MAX_LENGTH = 96
        private const val COMMAND_MAX_LENGTH = 80

        fun getInstance(project: Project): RollbackManager {
            return RollbackManager.getInstance(project)
        }
    }
}
