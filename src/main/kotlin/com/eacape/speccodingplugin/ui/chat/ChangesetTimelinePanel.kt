package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.FileChange
import com.eacape.speccodingplugin.rollback.RollbackManager
import com.eacape.speccodingplugin.rollback.RollbackOptions
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 变更时间线面板
 * 展示 AI 操作的变更历史，支持回滚操作
 */
class ChangesetTimelinePanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val store = ChangesetStore.getInstance(project)
    private val rollbackManager = RollbackManager.getInstance(project)
    private val timelineContainer = JPanel()
    private val statusLabel = JBLabel("")
    private val titleLabel = JBLabel()
    private val refreshButton = JButton()
    private val clearButton = JButton()
    private val formatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        border = JBUI.Borders.empty(8)
        setupUI()
        refresh()
    }

    private fun setupUI() {
        // 顶部工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        toolbar.isOpaque = false

        titleLabel.font = titleLabel.font.deriveFont(
            java.awt.Font.BOLD, 13f
        )
        toolbar.add(titleLabel)

        refreshButton.addActionListener { refresh() }
        toolbar.add(refreshButton)

        clearButton.addActionListener { clearAll() }
        toolbar.add(clearButton)

        toolbar.add(statusLabel)

        // 时间线容器
        timelineContainer.layout = BoxLayout(
            timelineContainer, BoxLayout.Y_AXIS
        )
        timelineContainer.border = JBUI.Borders.empty(4)

        val scrollPane = JBScrollPane(timelineContainer)
        scrollPane.border = JBUI.Borders.emptyTop(8)
        scrollPane.verticalScrollBar.unitIncrement = 16

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        refreshLocalizedTexts()
    }

    /**
     * 刷新时间线
     */
    fun refresh() {
        val changesets = store.getRecent(20)
        timelineContainer.removeAll()

        if (changesets.isEmpty()) {
            val emptyLabel = JBLabel(SpecCodingBundle.message("changeset.timeline.empty"))
            emptyLabel.foreground = JBColor.GRAY
            emptyLabel.border = JBUI.Borders.empty(16)
            timelineContainer.add(emptyLabel)
        } else {
            for (changeset in changesets) {
                timelineContainer.add(
                    createChangesetCard(changeset)
                )
            }
        }

        statusLabel.text = SpecCodingBundle.message("changeset.timeline.count", changesets.size)
        statusLabel.foreground = JBColor.GRAY

        timelineContainer.revalidate()
        timelineContainer.repaint()
    }

    private fun createChangesetCard(
        changeset: Changeset
    ): JPanel {
        val card = JPanel(BorderLayout())
        card.isOpaque = true
        card.background = JBColor(
            java.awt.Color(248, 248, 252),
            java.awt.Color(40, 42, 48)
        )
        card.border = JBUI.Borders.empty(8)

        // 头部：描述 + 时间
        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val descLabel = JBLabel(changeset.description)
        descLabel.font = descLabel.font.deriveFont(
            java.awt.Font.BOLD, 12f
        )
        header.add(descLabel, BorderLayout.WEST)

        val timeStr = formatter.format(changeset.timestamp)
        val timeLabel = JBLabel(timeStr)
        timeLabel.foreground = JBColor.GRAY
        timeLabel.font = timeLabel.font.deriveFont(11f)
        header.add(timeLabel, BorderLayout.EAST)

        // 统计信息
        val stats = changeset.getStatistics()
        val statsText = buildStatsText(stats)
        val statsLabel = JBLabel(statsText)
        statsLabel.foreground = JBColor.GRAY
        statsLabel.font = statsLabel.font.deriveFont(11f)
        statsLabel.border = JBUI.Borders.emptyTop(4)

        // 文件列表（最多显示 5 个）
        val filesPanel = JPanel()
        filesPanel.layout = BoxLayout(filesPanel, BoxLayout.Y_AXIS)
        filesPanel.isOpaque = false
        filesPanel.border = JBUI.Borders.emptyTop(4)

        val displayChanges = changeset.changes.take(5)
        for (change in displayChanges) {
            filesPanel.add(createFileChangeLabel(change))
        }
        if (changeset.changes.size > 5) {
            val moreLabel = JBLabel(
                SpecCodingBundle.message("changeset.timeline.more", changeset.changes.size - 5)
            )
            moreLabel.foreground = JBColor.GRAY
            moreLabel.font = moreLabel.font.deriveFont(11f)
            filesPanel.add(moreLabel)
        }

        // 操作按钮
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        actions.isOpaque = false
        actions.border = JBUI.Borders.emptyTop(6)

        val rollbackBtn = JButton(SpecCodingBundle.message("changeset.timeline.rollback"))
        rollbackBtn.addActionListener {
            performRollback(changeset.id)
        }
        actions.add(rollbackBtn)

        val deleteBtn = JButton(SpecCodingBundle.message("changeset.timeline.remove"))
        deleteBtn.addActionListener {
            store.delete(changeset.id)
            refresh()
        }
        actions.add(deleteBtn)

        // 组装
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.add(header)
        content.add(statsLabel)
        content.add(filesPanel)
        content.add(actions)

        card.add(content, BorderLayout.CENTER)

        // 卡片间距
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.border = JBUI.Borders.emptyBottom(6)
        wrapper.add(card, BorderLayout.CENTER)

        return wrapper
    }

    private fun buildStatsText(
        stats: com.eacape.speccodingplugin.rollback.ChangesetStatistics
    ): String {
        val parts = mutableListOf<String>()
        if (stats.createdFiles > 0) {
            parts.add(SpecCodingBundle.message("changeset.timeline.stats.created", stats.createdFiles))
        }
        if (stats.modifiedFiles > 0) {
            parts.add(SpecCodingBundle.message("changeset.timeline.stats.modified", stats.modifiedFiles))
        }
        if (stats.deletedFiles > 0) {
            parts.add(SpecCodingBundle.message("changeset.timeline.stats.deleted", stats.deletedFiles))
        }
        return parts.joinToString("  ")
    }

    private fun createFileChangeLabel(
        change: FileChange
    ): JBLabel {
        val icon = when (change.changeType) {
            FileChange.ChangeType.CREATED -> "+"
            FileChange.ChangeType.MODIFIED -> "~"
            FileChange.ChangeType.DELETED -> "-"
        }
        val color = when (change.changeType) {
            FileChange.ChangeType.CREATED -> JBColor(
                java.awt.Color(16, 124, 16),
                java.awt.Color(76, 175, 80)
            )
            FileChange.ChangeType.MODIFIED -> JBColor(
                java.awt.Color(0, 120, 215),
                java.awt.Color(78, 154, 241)
            )
            FileChange.ChangeType.DELETED -> JBColor.RED
        }

        val fileName = change.filePath
            .substringAfterLast("/")
            .substringAfterLast("\\")
        val label = JBLabel(SpecCodingBundle.message("changeset.timeline.file.entry", icon, fileName))
        label.foreground = color
        label.font = label.font.deriveFont(11f)
        return label
    }

    private fun performRollback(changesetId: String) {
        val result = rollbackManager.rollback(
            changesetId,
            RollbackOptions(createBackup = true)
        )

        SwingUtilities.invokeLater {
            statusLabel.text = result.getSummary()
            statusLabel.foreground = if (result.isSuccess()) {
                JBColor(
                    java.awt.Color(16, 124, 16),
                    java.awt.Color(76, 175, 80)
                )
            } else {
                JBColor.RED
            }
            refresh()
        }
    }

    private fun clearAll() {
        store.clear()
        refresh()
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("changeset.timeline.title")
        refreshButton.text = SpecCodingBundle.message("changeset.timeline.refresh")
        clearButton.text = SpecCodingBundle.message("changeset.timeline.clearAll")
    }

    companion object {
        fun getInstance(project: Project): RollbackManager {
            return RollbackManager.getInstance(project)
        }
    }
}
