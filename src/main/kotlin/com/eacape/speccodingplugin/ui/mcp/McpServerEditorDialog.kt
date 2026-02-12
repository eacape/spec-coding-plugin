package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.McpServerConfig
import com.eacape.speccodingplugin.mcp.TransportType
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * MCP Server 编辑对话框
 * 支持添加和编辑 MCP Server 配置
 */
class McpServerEditorDialog(
    private val existing: McpServerConfig? = null
) : DialogWrapper(true) {

    private val idField = JBTextField()
    private val nameField = JBTextField()
    private val commandField = JBTextField()
    private val argsField = JBTextField()
    private val envArea = JTextArea(3, 40)
    private val transportCombo = ComboBox(TransportType.entries.toTypedArray())
    private val autoStartCheckBox = JBCheckBox(
        SpecCodingBundle.message("mcp.dialog.field.autoStart")
    )
    private val trustedCheckBox = JBCheckBox(
        SpecCodingBundle.message("mcp.dialog.field.trusted")
    )

    var result: McpServerConfig? = null
        private set

    init {
        title = if (existing != null) {
            SpecCodingBundle.message("mcp.dialog.editServer.title")
        } else {
            SpecCodingBundle.message("mcp.dialog.addServer.title")
        }
        init()
        loadExisting()
    }

    private fun loadExisting() {
        if (existing == null) return
        idField.text = existing.id
        idField.isEditable = false
        nameField.text = existing.name
        commandField.text = existing.command
        argsField.text = existing.args.joinToString(", ")
        envArea.text = existing.env.entries.joinToString("\n") { "${it.key}=${it.value}" }
        transportCombo.selectedItem = existing.transport
        autoStartCheckBox.isSelected = existing.autoStart
        trustedCheckBox.isSelected = existing.trusted
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(550, 420)
        panel.border = JBUI.Borders.empty(8)

        val formPanel = createFormPanel()
        panel.add(formPanel, BorderLayout.CENTER)

        return panel
    }

    private fun createFormPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false

        // ID
        idField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.id")
        panel.add(createRow(SpecCodingBundle.message("mcp.dialog.field.id"), idField))

        // Name
        nameField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.name")
        panel.add(createRow(SpecCodingBundle.message("mcp.dialog.field.name"), nameField))

        // Command
        commandField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.command")
        panel.add(createRow(SpecCodingBundle.message("mcp.dialog.field.command"), commandField))

        // Args
        argsField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.args")
        panel.add(createRow(SpecCodingBundle.message("mcp.dialog.field.args"), argsField))

        // Transport
        panel.add(createRow(SpecCodingBundle.message("mcp.dialog.field.transport"), transportCombo))

        // Env
        panel.add(createEnvRow())

        // Checkboxes
        val checkPanel = JPanel()
        checkPanel.layout = BoxLayout(checkPanel, BoxLayout.X_AXIS)
        checkPanel.isOpaque = false
        checkPanel.border = JBUI.Borders.emptyTop(8)
        checkPanel.add(autoStartCheckBox)
        checkPanel.add(Box.createHorizontalStrut(16))
        checkPanel.add(trustedCheckBox)
        checkPanel.add(Box.createHorizontalGlue())
        panel.add(checkPanel)

        return panel
    }

    private fun createRow(label: String, field: JComponent): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.isOpaque = false
        row.border = JBUI.Borders.emptyBottom(4)
        val lbl = JBLabel(label)
        lbl.preferredSize = Dimension(120, 24)
        row.add(lbl, BorderLayout.WEST)
        row.add(field, BorderLayout.CENTER)
        return row
    }

    private fun createEnvRow(): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.isOpaque = false
        row.border = JBUI.Borders.emptyBottom(4)
        val lbl = JBLabel(SpecCodingBundle.message("mcp.dialog.field.env"))
        lbl.preferredSize = Dimension(120, 24)
        row.add(lbl, BorderLayout.WEST)
        envArea.font = commandField.font
        val scroll = JBScrollPane(envArea)
        scroll.preferredSize = Dimension(400, 60)
        row.add(scroll, BorderLayout.CENTER)
        return row
    }

    override fun doValidate(): ValidationInfo? {
        if (idField.text.isBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.idRequired"), idField
            )
        }
        if (!idField.text.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.idFormat"), idField
            )
        }
        if (nameField.text.isBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.nameRequired"), nameField
            )
        }
        if (commandField.text.isBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.commandRequired"), commandField
            )
        }
        return null
    }

    override fun doOKAction() {
        val args = argsField.text
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val env = envArea.text
            .lines()
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }

        result = McpServerConfig(
            id = idField.text.trim(),
            name = nameField.text.trim(),
            command = commandField.text.trim(),
            args = args,
            env = env,
            transport = transportCombo.selectedItem as TransportType,
            autoStart = autoStartCheckBox.isSelected,
            trusted = trustedCheckBox.isSelected
        )

        super.doOKAction()
    }
}
