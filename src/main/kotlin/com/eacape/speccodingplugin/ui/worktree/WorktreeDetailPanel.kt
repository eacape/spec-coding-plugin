package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

class WorktreeDetailPanel : JPanel(BorderLayout()) {

    private val emptyLabel = JBLabel(SpecCodingBundle.message("worktree.empty.select"), SwingConstants.CENTER)

    private val specTaskIdLabel = JBLabel()
    private val specTitleLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val branchLabel = JBLabel()
    private val baseBranchLabel = JBLabel()
    private val pathLabel = JBLabel()
    private val updatedAtLabel = JBLabel()
    private val errorArea = JBTextArea()

    init {
        border = JBUI.Borders.empty(8)
        showEmpty()
    }

    fun updateWorktree(item: WorktreeListItem) {
        removeAll()

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        specTaskIdLabel.text = "${SpecCodingBundle.message("worktree.detail.specTaskId")}: ${item.specTaskId}"
        specTaskIdLabel.font = specTaskIdLabel.font.deriveFont(Font.BOLD, 14f)
        specTitleLabel.text = "${SpecCodingBundle.message("worktree.detail.specTitle")}: ${item.specTitle}"
        statusLabel.text = "${SpecCodingBundle.message("worktree.detail.status")}: ${item.status.name}${if (item.isActive) " (${SpecCodingBundle.message("worktree.detail.active")})" else ""}"
        branchLabel.text = "${SpecCodingBundle.message("worktree.detail.branch")}: ${item.branchName}"
        baseBranchLabel.text = "${SpecCodingBundle.message("worktree.detail.baseBranch")}: ${item.baseBranch}"
        pathLabel.text = "${SpecCodingBundle.message("worktree.detail.path")}: ${item.worktreePath}"
        updatedAtLabel.text = "${SpecCodingBundle.message("worktree.detail.updatedAt")}: ${item.updatedAt}"

        content.add(specTaskIdLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(specTitleLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(statusLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(branchLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(baseBranchLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(pathLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(updatedAtLabel)

        add(content, BorderLayout.NORTH)

        errorArea.isEditable = false
        errorArea.lineWrap = true
        errorArea.wrapStyleWord = true
        errorArea.border = JBUI.Borders.empty(4)
        errorArea.text = item.lastError ?: SpecCodingBundle.message("worktree.detail.noError")

        val errorPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(JBLabel(SpecCodingBundle.message("worktree.detail.lastError")), BorderLayout.NORTH)
            add(JScrollPane(errorArea), BorderLayout.CENTER)
        }
        add(errorPanel, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }
}

