package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
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
    private val existingPromptIds: Set<String> = emptySet(),
    private val initialName: String = "",
    private val initialContent: String = "",
) : DialogWrapper(true) {

    private val nameField = JBTextField()
    private val contentPane = JTextPane()
    private val variablesLabel = JBLabel("")
    private val previewLabel = JBLabel("")

    var result: PromptTemplate? = null
        private set

    init {
        title = if (existing != null) {
            SpecCodingBundle.message("prompt.editor.title.edit")
        } else {
            SpecCodingBundle.message("prompt.editor.title.new")
        }
        init()
        loadExisting()
    }

    private fun loadExisting() {
        if (existing != null) {
            nameField.text = existing.name
            contentPane.text = existing.content
        } else {
            nameField.text = initialName
            contentPane.text = initialContent
        }
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

        // Name 行
        val nameRow = createLabeledRow(SpecCodingBundle.message("prompt.editor.field.name"), nameField)
        nameField.emptyText.text = SpecCodingBundle.message("prompt.editor.placeholder.name")

        panel.add(nameRow)

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

        val editorLabel = JBLabel(SpecCodingBundle.message("prompt.editor.field.content"))
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
            SpecCodingBundle.message("prompt.editor.hint")
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
                SpecCodingBundle.message(
                    "prompt.editor.variables.detected",
                    vars.joinToString(", ") { "{{$it}}" }
                )
            } else {
                SpecCodingBundle.message("prompt.editor.variables.none")
            }

            // 更新字符计数
            previewLabel.text = SpecCodingBundle.message("prompt.editor.characters", text.length)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) {
            return ValidationInfo(SpecCodingBundle.message("prompt.editor.validation.nameRequired"), nameField)
        }
        if (contentPane.text.isBlank()) {
            return ValidationInfo(SpecCodingBundle.message("prompt.editor.validation.contentRequired"))
        }
        return null
    }

    override fun doOKAction() {
        val templateId = resolveTemplateId()

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
            id = templateId,
            name = nameField.text.trim(),
            content = contentPane.text.trim(),
            variables = mergedVars,
            scope = PromptScope.PROJECT,
            tags = existing?.tags.orEmpty(),
        )

        super.doOKAction()
    }

    private fun resolveTemplateId(): String {
        existing?.id?.let { return it }

        val baseId = nameField.text
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "prompt" }
        if (baseId !in existingPromptIds) {
            return baseId
        }

        var index = 2
        var candidate = "$baseId-$index"
        while (candidate in existingPromptIds) {
            index++
            candidate = "$baseId-$index"
        }
        return candidate
    }
}
