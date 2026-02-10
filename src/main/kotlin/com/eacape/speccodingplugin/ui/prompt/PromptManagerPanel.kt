package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Prompt 管理面板
 * 展示 Prompt 列表，支持新建、编辑、删除操作
 */
class PromptManagerPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val promptManager = PromptManager.getInstance(project)
    private val listModel = DefaultListModel<PromptTemplate>()
    private val promptList = JBList(listModel)

    private val newBtn = JButton("New")
    private val editBtn = JButton("Edit")
    private val deleteBtn = JButton("Delete")
    private val activeLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)
        setupUI()
        refresh()
    }

    private fun setupUI() {
        // 顶部标题栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        toolbar.isOpaque = false

        val titleLabel = JBLabel("Prompt Templates")
        titleLabel.font = titleLabel.font.deriveFont(
            java.awt.Font.BOLD, 13f
        )
        toolbar.add(titleLabel)
        toolbar.add(newBtn)
        toolbar.add(editBtn)
        toolbar.add(deleteBtn)

        // 列表
        promptList.selectionMode =
            ListSelectionModel.SINGLE_SELECTION
        promptList.cellRenderer = PromptListCellRenderer()
        promptList.addListSelectionListener {
            updateButtonStates()
        }

        val scrollPane = JBScrollPane(promptList)
        scrollPane.border = JBUI.Borders.emptyTop(8)

        // 底部状态
        activeLabel.foreground = JBColor.GRAY
        activeLabel.font = activeLabel.font.deriveFont(11f)
        activeLabel.border = JBUI.Borders.emptyTop(4)

        // 按钮事件
        newBtn.addActionListener { onNew() }
        editBtn.addActionListener { onEdit() }
        deleteBtn.addActionListener { onDelete() }

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(activeLabel, BorderLayout.SOUTH)

        updateButtonStates()
    }

    fun refresh() {
        val templates = promptManager.listPromptTemplates()
        val activeId = promptManager.getActivePromptId()

        listModel.clear()
        templates.forEach { listModel.addElement(it) }

        activeLabel.text = "Active: ${
            templates.firstOrNull { it.id == activeId }?.name
                ?: activeId
        } (${templates.size} templates)"

        updateButtonStates()
    }

    private fun updateButtonStates() {
        val selected = promptList.selectedValue
        editBtn.isEnabled = selected != null
        deleteBtn.isEnabled = selected != null &&
            selected.id != PromptManager.DEFAULT_PROMPT_ID
    }

    private fun onNew() {
        val dialog = PromptEditorDialog()
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onEdit() {
        val selected = promptList.selectedValue ?: return
        val dialog = PromptEditorDialog(existing = selected)
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onDelete() {
        val selected = promptList.selectedValue ?: return
        if (selected.id == PromptManager.DEFAULT_PROMPT_ID) {
            return
        }
        promptManager.deleteTemplate(selected.id)
        refresh()
    }
}
