package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.McpServer
import com.eacape.speccodingplugin.mcp.McpTool
import com.eacape.speccodingplugin.mcp.ServerStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.*
import javax.swing.*

/**
 * MCP Server 详情面板（右侧）
 * 展示选中 Server 的信息、操作按钮和工具列表
 */
class McpServerDetailPanel(
    private val onStartServer: (String) -> Unit,
    private val onStopServer: (String) -> Unit,
    private val onRestartServer: (String) -> Unit,
    private val onEditServer: (String) -> Unit
) : JPanel(BorderLayout()) {

    private val prettyJson = Json { prettyPrint = true }

    private val serverNameLabel = JBLabel("")
    private val statusLabel = JBLabel("")
    private val commandLabel = JBLabel("")
    private val startBtn = JButton(SpecCodingBundle.message("mcp.server.start"))
    private val stopBtn = JButton(SpecCodingBundle.message("mcp.server.stop"))
    private val restartBtn = JButton(SpecCodingBundle.message("mcp.server.restart"))
    private val editBtn = JButton(SpecCodingBundle.message("mcp.server.edit"))

    private val toolsLabel = JBLabel("")
    private val toolListModel = DefaultListModel<McpTool>()
    private val toolList = JBList(toolListModel)
    private val toolDetailArea = JBTextArea()

    private val emptyLabel = JBLabel(SpecCodingBundle.message("mcp.server.select"))

    private var currentServerId: String? = null

    init {
        border = JBUI.Borders.empty(8)
        showEmpty()
    }

    private fun buildContentUI() {
        removeAll()
        layout = BorderLayout()
        add(createHeaderPanel(), BorderLayout.NORTH)
        add(createToolsPanel(), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyBottom(8)

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.isOpaque = false

        serverNameLabel.font = serverNameLabel.font.deriveFont(Font.BOLD, 14f)
        statusLabel.font = statusLabel.font.deriveFont(11f)
        commandLabel.font = commandLabel.font.deriveFont(11f)
        commandLabel.foreground = JBColor.GRAY

        infoPanel.add(serverNameLabel)
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(statusLabel)
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(commandLabel)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        btnPanel.isOpaque = false
        startBtn.addActionListener { currentServerId?.let(onStartServer) }
        stopBtn.addActionListener { currentServerId?.let(onStopServer) }
        restartBtn.addActionListener { currentServerId?.let(onRestartServer) }
        editBtn.addActionListener { currentServerId?.let(onEditServer) }
        btnPanel.add(startBtn)
        btnPanel.add(stopBtn)
        btnPanel.add(restartBtn)
        btnPanel.add(editBtn)

        panel.add(infoPanel, BorderLayout.CENTER)
        panel.add(btnPanel, BorderLayout.EAST)
        return panel
    }

    private fun createToolsPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        toolsLabel.border = JBUI.Borders.emptyBottom(4)

        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        toolList.cellRenderer = ToolCellRenderer()
        toolList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateToolDetail(toolList.selectedValue)
            }
        }

        val listScroll = JBScrollPane(toolList)
        listScroll.preferredSize = Dimension(0, 200)

        toolDetailArea.isEditable = false
        toolDetailArea.font = Font("JetBrains Mono", Font.PLAIN, 12)
        toolDetailArea.border = JBUI.Borders.empty(4)
        val detailScroll = JBScrollPane(toolDetailArea)

        val splitPane = JSplitPane(
            JSplitPane.VERTICAL_SPLIT, listScroll, detailScroll
        )
        splitPane.dividerLocation = 200
        splitPane.dividerSize = JBUI.scale(4)

        panel.add(toolsLabel, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        return panel
    }

    fun updateServer(server: McpServer, tools: List<McpTool>) {
        currentServerId = server.config.id
        serverNameLabel.text = server.config.name
        commandLabel.text = SpecCodingBundle.message("mcp.server.command", server.config.command)
        updateStatusLabel(server.status)
        updateButtonStates(server.status, server.config.trusted)

        toolsLabel.text = SpecCodingBundle.message("mcp.server.tools", tools.size)
        toolListModel.clear()
        tools.forEach { toolListModel.addElement(it) }

        if (tools.isEmpty()) {
            toolDetailArea.text = SpecCodingBundle.message("mcp.server.noTools")
        } else {
            toolDetailArea.text = ""
        }

        buildContentUI()
    }

    fun showEmpty() {
        currentServerId = null
        removeAll()
        layout = BorderLayout()
        emptyLabel.horizontalAlignment = SwingConstants.CENTER
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun updateStatusLabel(status: ServerStatus) {
        val (text, color) = when (status) {
            ServerStatus.RUNNING -> SpecCodingBundle.message("mcp.server.status.running") to
                    JBColor(Color(76, 175, 80), Color(76, 175, 80))
            ServerStatus.STOPPED -> SpecCodingBundle.message("mcp.server.status.stopped") to
                    JBColor.GRAY
            ServerStatus.STARTING -> SpecCodingBundle.message("mcp.server.status.starting") to
                    JBColor(Color(255, 152, 0), Color(255, 167, 38))
            ServerStatus.ERROR -> SpecCodingBundle.message("mcp.server.status.error") to
                    JBColor(Color(244, 67, 54), Color(239, 83, 80))
        }
        statusLabel.text = SpecCodingBundle.message("mcp.server.status.label", text)
        statusLabel.foreground = color
    }

    private fun updateButtonStates(status: ServerStatus, trusted: Boolean) {
        startBtn.isEnabled = status == ServerStatus.STOPPED && trusted
        stopBtn.isEnabled = status == ServerStatus.RUNNING
        restartBtn.isEnabled = status == ServerStatus.RUNNING
        editBtn.isEnabled = status != ServerStatus.STARTING

        if (!trusted && status == ServerStatus.STOPPED) {
            startBtn.toolTipText = SpecCodingBundle.message("mcp.server.untrusted")
        } else {
            startBtn.toolTipText = null
        }
    }

    private fun updateToolDetail(tool: McpTool?) {
        if (tool == null) {
            toolDetailArea.text = ""
            return
        }
        val sb = StringBuilder()
        sb.appendLine(SpecCodingBundle.message("mcp.tool.detail.name", tool.name))
        if (tool.description != null) {
            sb.appendLine(SpecCodingBundle.message("mcp.tool.detail.description", tool.description))
        }
        sb.appendLine()
        sb.appendLine(SpecCodingBundle.message("mcp.tool.detail.inputSchema"))
        try {
            sb.appendLine(prettyJson.encodeToString(
                JsonElement.serializer(), tool.inputSchema
            ))
        } catch (_: Exception) {
            sb.appendLine(tool.inputSchema.toString())
        }
        toolDetailArea.text = sb.toString()
        toolDetailArea.caretPosition = 0
    }

    private class ToolCellRenderer : ListCellRenderer<McpTool> {
        private val panel = JPanel(BorderLayout(4, 2))
        private val nameLabel = JBLabel()
        private val descLabel = JBLabel()

        init {
            panel.border = JBUI.Borders.empty(4)
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 12f)
            descLabel.font = descLabel.font.deriveFont(Font.PLAIN, 11f)
            descLabel.foreground = JBColor.GRAY
            panel.add(nameLabel, BorderLayout.NORTH)
            panel.add(descLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out McpTool>,
            value: McpTool?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value != null) {
                nameLabel.text = value.name
                val desc = value.description ?: ""
                descLabel.text = if (desc.length > 80) {
                    desc.take(80) + "..."
                } else {
                    desc
                }
            }
            panel.background = if (isSelected) {
                list.selectionBackground
            } else {
                list.background
            }
            nameLabel.foreground = if (isSelected) {
                list.selectionForeground
            } else {
                list.foreground
            }
            return panel
        }
    }
}
