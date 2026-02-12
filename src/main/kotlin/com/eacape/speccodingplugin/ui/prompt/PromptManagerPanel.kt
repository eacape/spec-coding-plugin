package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
) : JPanel(BorderLayout()), Disposable {

    private val promptManager = PromptManager.getInstance(project)
    private val listModel = DefaultListModel<PromptTemplate>()
    private val promptList = JBList(listModel)

    private val titleLabel = JBLabel()
    private val newBtn = JButton()
    private val editBtn = JButton()
    private val deleteBtn = JButton()
    private val activeLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)
        setupUI()
        subscribeToLocaleEvents()
        refreshLocalizedTexts()
        refresh()
    }

    private fun setupUI() {
        // 顶部标题栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        toolbar.isOpaque = false

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

        val activeName = templates.firstOrNull { it.id == activeId }?.name
            ?: activeId
            ?: SpecCodingBundle.message("prompt.manager.active.none")
        activeLabel.text = SpecCodingBundle.message("prompt.manager.active", activeName, templates.size)

        updateButtonStates()
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("prompt.manager.title")
        newBtn.text = SpecCodingBundle.message("prompt.manager.new")
        editBtn.text = SpecCodingBundle.message("prompt.manager.edit")
        deleteBtn.text = SpecCodingBundle.message("prompt.manager.delete")
    }

    private fun subscribeToLocaleEvents() {
        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        refreshLocalizedTexts()
                        refresh()
                    }
                }
            },
        )
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

    override fun dispose() {
    }
}
