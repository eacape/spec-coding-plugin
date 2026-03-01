package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.ServerStatus
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.openapi.util.IconLoader
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
    private val listTitleLabel = JBLabel(SpecCodingBundle.message("mcp.server.list.title"))
    private val deleteBtn = JButton()

    init {
        border = JBUI.Borders.empty()
        isOpaque = true
        background = LIST_SECTION_BG
        setupUI()
    }

    private fun setupUI() {
        refreshDeleteButtonPresentation()
        styleActionButton(deleteBtn)

        // 工具栏
        val toolbar = JPanel(BorderLayout())
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(1, 2, 1, 2)
        listTitleLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        listTitleLabel.foreground = TITLE_FG

        deleteBtn.isEnabled = false
        deleteBtn.addActionListener {
            serverList.selectedValue?.let {
                onDeleteServer(it.serverId)
            }
        }
        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        actionPanel.isOpaque = false
        actionPanel.add(deleteBtn)

        toolbar.add(listTitleLabel, BorderLayout.WEST)
        toolbar.add(actionPanel, BorderLayout.EAST)

        val toolbarCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(10),
                top = 4,
                left = 6,
                bottom = 4,
                right = 6,
            )
            add(toolbar, BorderLayout.CENTER)
        }

        // 列表
        serverList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        serverList.cellRenderer = ServerCellRenderer()
        serverList.emptyText.text = SpecCodingBundle.message("mcp.server.empty")
        serverList.background = LIST_BG
        serverList.selectionBackground = LIST_ROW_SELECTED_BG
        serverList.selectionForeground = LIST_ROW_SELECTED_FG
        serverList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = serverList.selectedValue
                deleteBtn.isEnabled = selected != null
                selected?.let { onServerSelected(it.serverId) }
            }
        }

        val scrollPane = JBScrollPane(serverList).apply {
            border = JBUI.Borders.empty()
            viewport.background = LIST_BG
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(toolbarCard, BorderLayout.NORTH)
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
        listTitleLabel.text = SpecCodingBundle.message("mcp.server.list.title")
        refreshDeleteButtonPresentation()
        styleActionButton(deleteBtn)
        serverList.emptyText.text = SpecCodingBundle.message("mcp.server.empty")
        serverList.repaint()
        revalidate()
    }

    private fun refreshDeleteButtonPresentation() {
        val tooltip = SpecCodingBundle.message("mcp.server.delete")
        deleteBtn.text = ""
        deleteBtn.icon = MCP_SERVER_DELETE_ICON
        deleteBtn.iconTextGap = 0
        deleteBtn.toolTipText = tooltip
        deleteBtn.accessibleContext?.accessibleName = tooltip
        deleteBtn.accessibleContext?.accessibleDescription = tooltip
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
            infoLabel.foreground = INFO_FG

            statusDot.preferredSize = Dimension(8, 8)
            statusDot.minimumSize = Dimension(8, 8)
            statusDot.maximumSize = Dimension(8, 8)
            statusDot.isOpaque = true

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
                LIST_ROW_SELECTED_BG
            } else {
                LIST_ROW_BG
            }
            nameLabel.foreground = if (isSelected) {
                LIST_ROW_SELECTED_FG
            } else {
                LIST_ROW_FG
            }
            infoLabel.foreground = if (isSelected) INFO_FG_SELECTED else INFO_FG
            return panel
        }

        private fun getStatusColor(status: ServerStatus): Color = when (status) {
            ServerStatus.RUNNING -> JBColor(Color(74, 162, 98), Color(118, 192, 140))
            ServerStatus.STOPPED -> JBColor(Color(146, 154, 167), Color(130, 140, 154))
            ServerStatus.STARTING -> JBColor(Color(255, 152, 0), Color(255, 167, 38))
            ServerStatus.ERROR -> JBColor(Color(244, 67, 54), Color(239, 83, 80))
        }
    }

    private fun styleActionButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.isOpaque = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.emptyInsets() else JBUI.insets(1, 4, 1, 4)
        button.background = BUTTON_BG
        button.foreground = BUTTON_FG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            if (iconOnly) JBUI.Borders.empty(4) else JBUI.Borders.empty(1, 6, 1, 6),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        button.preferredSize = if (iconOnly) {
            JBUI.size(JBUI.scale(28), JBUI.scale(28))
        } else {
            JBUI.size(
                maxOf(button.preferredSize.width, JBUI.scale(56)),
                JBUI.scale(28),
            )
        }
    }

    companion object {
        private val MCP_SERVER_DELETE_ICON = IconLoader.getIcon("/icons/mcp-server-delete.svg", McpServerListPanel::class.java)
        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val TITLE_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val LIST_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val LIST_BG = JBColor(Color(248, 251, 255), Color(56, 62, 72))
        private val LIST_ROW_BG = JBColor(Color(248, 251, 255), Color(56, 62, 72))
        private val LIST_ROW_SELECTED_BG = JBColor(Color(226, 238, 255), Color(75, 91, 114))
        private val LIST_ROW_FG = JBColor(Color(45, 62, 88), Color(213, 223, 238))
        private val LIST_ROW_SELECTED_FG = JBColor(Color(35, 55, 86), Color(229, 237, 249))
        private val INFO_FG = JBColor(Color(102, 117, 138), Color(168, 181, 202))
        private val INFO_FG_SELECTED = JBColor(Color(87, 104, 129), Color(206, 219, 238))
    }
}
