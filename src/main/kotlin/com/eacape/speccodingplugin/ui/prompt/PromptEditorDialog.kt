package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Prompt 编辑器对话框
 * 支持创建和编辑 Prompt 模板，带变量语法高亮
 */
class PromptEditorDialog(
    private val existing: PromptTemplate? = null,
) : DialogWrapper(true) {

    private val idField = JBTextField()
    private val nameField = JBTextField()
    private val tagsField = JBTextField()
    private val contentPane = JTextPane()
    private val variablesLabel = JBLabel("")
    private val previewLabel = JBLabel("")

    var result: PromptTemplate? = null
        private set

    init {
        title = if (existing != null) "Edit Prompt Template" else "New Prompt Template"
        init()
        loadExisting()
    }

    private fun loadExisting() {
        if (existing == null) return
        idField.text = existing.id
        idField.isEditable = false
        nameField.text = existing.name
        tagsField.text = existing.tags.joinToString(", ")
        contentPane.text = existing.content
        updateHighlightAndPreview()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(600, 500)
        panel.border = JBUI.Borders.empty(8)

        // 顶部表单
        val formPanel = createFormPanel()

        // 内容编辑区
        val editorPanel = createEditorPanel()

        // 底部信息
        val infoPanel = createInfoPanel()

        panel.add(formPanel, BorderLayout.NORTH)
        panel.add(editorPanel, BorderLayout.CENTER)
        panel.add(infoPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createFormPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyBottom(8)

        // ID 行
        val idRow = createLabeledRow("ID:", idField)
        idField.emptyText.text = "e.g. my-custom-prompt"

        // Name 行
        val nameRow = createLabeledRow("Name:", nameField)
        nameField.emptyText.text = "e.g. My Custom Prompt"

        // Tags 行
        val tagsRow = createLabeledRow("Tags:", tagsField)
        tagsField.emptyText.text = "e.g. coding, review (comma separated)"

        panel.add(idRow)
        panel.add(nameRow)
        panel.add(tagsRow)

        return panel
    }

    private fun createLabeledRow(
        label: String, field: JBTextField
    ): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.isOpaque = false
        row.border = JBUI.Borders.emptyBottom(4)

        val lbl = JBLabel(label)
        lbl.preferredSize = Dimension(50, 24)
        row.add(lbl, BorderLayout.WEST)
        row.add(field, BorderLayout.CENTER)

        return row
    }

    private fun createEditorPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val editorLabel = JBLabel("Content:")
        editorLabel.border = JBUI.Borders.emptyBottom(4)

        // 配置编辑器
        contentPane.font = Font("JetBrains Mono", Font.PLAIN, 13)
        contentPane.border = JBUI.Borders.empty(8)

        // 监听内容变化，实时高亮
        contentPane.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) {
                    updateHighlightAndPreview()
                }
                override fun removeUpdate(e: DocumentEvent) {
                    updateHighlightAndPreview()
                }
                override fun changedUpdate(e: DocumentEvent) {}
            }
        )

        val scrollPane = JBScrollPane(contentPane)
        scrollPane.preferredSize = Dimension(580, 280)

        // 提示文字
        val hintLabel = JBLabel(
            "Use {{variable_name}} for variables. " +
            "Built-in: {{project_name}}, {{project_path}}"
        )
        hintLabel.foreground = JBColor.GRAY
        hintLabel.font = hintLabel.font.deriveFont(11f)
        hintLabel.border = JBUI.Borders.emptyTop(4)

        panel.add(editorLabel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(hintLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun createInfoPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyTop(8)

        variablesLabel.foreground = JBColor(
            java.awt.Color(156, 39, 176),
            java.awt.Color(206, 147, 216)
        )
        variablesLabel.font = variablesLabel.font.deriveFont(11f)

        previewLabel.foreground = JBColor.GRAY
        previewLabel.font = previewLabel.font.deriveFont(11f)

        panel.add(variablesLabel)
        panel.add(previewLabel)

        return panel
    }

    private fun updateHighlightAndPreview() {
        // 延迟执行以避免在文档修改期间操作
        javax.swing.SwingUtilities.invokeLater {
            // 语法高亮
            val caretPos = contentPane.caretPosition
            PromptSyntaxHighlighter.highlight(contentPane)
            try {
                contentPane.caretPosition = caretPos.coerceAtMost(
                    contentPane.document.length
                )
            } catch (_: Exception) { }

            // 更新变量列表
            val text = contentPane.text
            val vars = PromptSyntaxHighlighter.extractVariables(text)
            variablesLabel.text = if (vars.isNotEmpty()) {
                "Variables: ${vars.joinToString(", ") { "{{$it}}" }}"
            } else {
                "No variables detected"
            }

            // 更新字符计数
            previewLabel.text = "${text.length} characters"
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (idField.text.isBlank()) {
            return ValidationInfo("ID is required", idField)
        }
        if (!idField.text.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return ValidationInfo(
                "ID can only contain letters, numbers, hyphens and underscores",
                idField
            )
        }
        if (nameField.text.isBlank()) {
            return ValidationInfo("Name is required", nameField)
        }
        if (contentPane.text.isBlank()) {
            return ValidationInfo("Content is required")
        }
        return null
    }

    override fun doOKAction() {
        val tags = tagsField.text
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val variables = PromptSyntaxHighlighter
            .extractVariables(contentPane.text)
            .associateWith { "" }

        // 保留已有变量的值
        val mergedVars = if (existing != null) {
            val merged = existing.variables.toMutableMap()
            variables.keys.forEach { key ->
                merged.putIfAbsent(key, "")
            }
            merged
        } else {
            variables
        }

        result = PromptTemplate(
            id = idField.text.trim(),
            name = nameField.text.trim(),
            content = contentPane.text.trim(),
            variables = mergedVars,
            scope = PromptScope.PROJECT,
            tags = tags,
        )

        super.doOKAction()
    }
}
