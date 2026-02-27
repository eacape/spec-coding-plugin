package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * MCP AI 快速配置输入对话框。
 */
class McpAiQuickSetupDialog : DialogWrapper(true) {

    private val inputArea = JBTextArea(10, 62)

    var promptText: String = ""
        private set

    init {
        title = SpecCodingBundle.message("mcp.ai.dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8)))
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(300))

        val guidance = JBLabel(
            "<html>${SpecCodingBundle.message("mcp.ai.dialog.guide")}</html>"
        )
        guidance.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        panel.add(guidance, BorderLayout.NORTH)

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.emptyText.text = SpecCodingBundle.message("mcp.ai.dialog.placeholder")
        panel.add(JBScrollPane(inputArea), BorderLayout.CENTER)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (inputArea.text.trim().isEmpty()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.ai.dialog.validation.promptRequired"),
                inputArea,
            )
        }
        return null
    }

    override fun doOKAction() {
        promptText = inputArea.text.trim()
        super.doOKAction()
    }
}

