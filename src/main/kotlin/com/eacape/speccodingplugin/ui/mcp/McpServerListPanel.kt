package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.ServerStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * MCP Server 列表面板（左侧）
 * 展示已配置的 Server 列表，带状态指示和工具栏
 */
class McpServerListPanel(
    private val onServerSelected: (String) -> Unit,
    private val onAddServer: () -> Unit,
    private val onDeleteServer: (String) -> Unit
) : JPanel(BorderLayout()) {

    data class ServerListItem(
        val serverId: String,
        val name: String,
        val status: ServerStatus,
        val toolCount: Int
    )

    private val listModel = DefaultListModel<ServerListItem>()
    private val serverList = JBList(listModel)
    private val emptyLabel = JBLabel(
        SpecCodingBundle.message("mcp.server.empty")
    )
    private val addBtn = JButton(SpecCodingBundle.message("mcp.server.add"))
    private val deleteBtn = JButton(SpecCodingBundle.message("mcp.server.delete"))

    init {
        border = JBUI.Borders.empty(4)
        setupUI()
    }

    private fun setupUI() {
        // 工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.emptyBottom(4)

        addBtn.addActionListener { onAddServer() }

        deleteBtn.isEnabled = false
        deleteBtn.addActionListener {
            serverList.selectedValue?.let {
                onDeleteServer(it.serverId)
            }
        }

        toolbar.add(addBtn)
        toolbar.add(deleteBtn)

        // 列表
        serverList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        serverList.cellRenderer = ServerCellRenderer()
        serverList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = serverList.selectedValue
                deleteBtn.isEnabled = selected != null
                selected?.let { onServerSelected(it.serverId) }
            }
        }

        val scrollPane = JBScrollPane(serverList)

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    fun updateServers(items: List<ServerListItem>) {
        val selectedId = serverList.selectedValue?.serverId
        listModel.clear()
        items.forEach { listModel.addElement(it) }

        if (selectedId != null) {
            for (i in 0 until listModel.size()) {
                if (listModel[i].serverId == selectedId) {
                    serverList.selectedIndex = i
                    break
                }
            }
        }
    }

    fun setSelectedServer(serverId: String?) {
        if (serverId == null) {
            serverList.clearSelection()
            return
        }
        for (i in 0 until listModel.size()) {
            if (listModel[i].serverId == serverId) {
                serverList.selectedIndex = i
                break
            }
        }
    }

    fun refreshLocalizedTexts() {
        addBtn.text = SpecCodingBundle.message("mcp.server.add")
        deleteBtn.text = SpecCodingBundle.message("mcp.server.delete")
        serverList.repaint()
    }

    private class ServerCellRenderer : ListCellRenderer<ServerListItem> {
        private val panel = JPanel(BorderLayout(8, 2))
        private val nameLabel = JBLabel()
        private val statusDot = JPanel()
        private val infoLabel = JBLabel()

        init {
            panel.border = JBUI.Borders.empty(6, 8)
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 13f)
            infoLabel.font = infoLabel.font.deriveFont(Font.PLAIN, 11f)
            infoLabel.foreground = JBColor.GRAY

            statusDot.preferredSize = Dimension(8, 8)
            statusDot.minimumSize = Dimension(8, 8)
            statusDot.maximumSize = Dimension(8, 8)
            statusDot.isOpaque = false

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            leftPanel.isOpaque = false
            leftPanel.add(statusDot)
            leftPanel.add(nameLabel)

            panel.add(leftPanel, BorderLayout.NORTH)
            panel.add(infoLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out ServerListItem>,
            value: ServerListItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value != null) {
                nameLabel.text = value.name
                val statusText = when (value.status) {
                    ServerStatus.RUNNING -> SpecCodingBundle.message("mcp.server.status.running")
                    ServerStatus.STOPPED -> SpecCodingBundle.message("mcp.server.status.stopped")
                    ServerStatus.STARTING -> SpecCodingBundle.message("mcp.server.status.starting")
                    ServerStatus.ERROR -> SpecCodingBundle.message("mcp.server.status.error")
                }
                infoLabel.text = SpecCodingBundle.message("mcp.server.list.info", statusText, value.toolCount)
                statusDot.background = getStatusColor(value.status)
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

        private fun getStatusColor(status: ServerStatus): Color = when (status) {
            ServerStatus.RUNNING -> JBColor(Color(76, 175, 80), Color(76, 175, 80))
            ServerStatus.STOPPED -> JBColor.GRAY
            ServerStatus.STARTING -> JBColor(Color(255, 152, 0), Color(255, 167, 38))
            ServerStatus.ERROR -> JBColor(Color(244, 67, 54), Color(239, 83, 80))
        }
    }
}
