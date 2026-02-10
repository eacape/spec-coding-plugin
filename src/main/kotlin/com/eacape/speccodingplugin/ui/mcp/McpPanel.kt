package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * MCP 主面板
 * Tool Window 中的 MCP 标签页，管理 MCP Server 的配置和状态
 */
class McpPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var _isDisposed = false

    private val mcpHub by lazy { McpHub.getInstance(project) }
    private val mcpConfigStore by lazy { McpConfigStore.getInstance(project) }

    private val statusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val refreshBtn = JButton(SpecCodingBundle.message("mcp.server.refresh"))

    private val serverListPanel = McpServerListPanel(
        onServerSelected = ::onServerSelected,
        onAddServer = ::onAddServer,
        onDeleteServer = ::onDeleteServer
    )

    private val serverDetailPanel = McpServerDetailPanel(
        onStartServer = ::onStartServer,
        onStopServer = ::onStopServer,
        onRestartServer = ::onRestartServer,
        onEditServer = ::onEditServer
    )

    init {
        border = JBUI.Borders.empty(4)
        setupUI()
        subscribeToEvents()
        loadServers()
    }

    private fun setupUI() {
        // 顶部工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.emptyBottom(4)
        val titleLabel = JBLabel(SpecCodingBundle.message("mcp.panel.title"))
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 13f)
        toolbar.add(titleLabel)
        toolbar.add(refreshBtn)
        toolbar.add(statusLabel)
        add(toolbar, BorderLayout.NORTH)

        // 主体: 左右分割
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            serverListPanel,
            serverDetailPanel
        )
        splitPane.dividerLocation = 220
        splitPane.dividerSize = JBUI.scale(4)
        add(splitPane, BorderLayout.CENTER)

        // 刷新按钮
        refreshBtn.addActionListener { refreshServers() }
    }

    private fun subscribeToEvents() {
        project.messageBus.connect(this).subscribe(
            McpHubListener.TOPIC,
            object : McpHubListener {
                override fun onServerStatusChanged(serverId: String, status: ServerStatus) {
                    invokeLaterSafe { refreshServers() }
                }

                override fun onToolsDiscovered(serverId: String, tools: List<McpTool>) {
                    invokeLaterSafe { refreshServers() }
                }
            }
        )
    }

    private fun loadServers() {
        scope.launch(Dispatchers.IO) {
            mcpHub.loadPersistedConfigs()
            invokeLaterSafe { refreshServers() }
        }
    }

    fun refreshServers() {
        val servers = mcpHub.getAllServers()
        val items = servers.map { server ->
            McpServerListPanel.ServerListItem(
                serverId = server.config.id,
                name = server.config.name,
                status = server.status,
                toolCount = mcpHub.getServerTools(server.config.id).size
            )
        }
        serverListPanel.updateServers(items)
        statusLabel.text = "${servers.size} server(s)"
    }

    // --- 回调方法 ---

    private fun onServerSelected(serverId: String) {
        val server = mcpHub.getServer(serverId) ?: return
        val tools = mcpHub.getServerTools(serverId)
        serverDetailPanel.updateServer(server, tools)
    }

    private fun onAddServer() {
        val dialog = McpServerEditorDialog()
        if (dialog.showAndGet()) {
            val config = dialog.result ?: return
            mcpConfigStore.save(config)
            mcpHub.registerServer(config)
            refreshServers()
        }
    }

    private fun onEditServer(serverId: String) {
        val existing = mcpConfigStore.getById(serverId) ?: return
        val dialog = McpServerEditorDialog(existing)
        if (dialog.showAndGet()) {
            val config = dialog.result ?: return
            mcpConfigStore.save(config)
            mcpHub.updateServerConfig(config)
            refreshServers()
            onServerSelected(serverId)
        }
    }

    private fun onDeleteServer(serverId: String) {
        mcpHub.stopServer(serverId)
        mcpHub.unregisterServer(serverId)
        mcpConfigStore.delete(serverId)
        serverDetailPanel.showEmpty()
        refreshServers()
    }

    private fun onStartServer(serverId: String) {
        scope.launch {
            val result = mcpHub.startServer(serverId)
            invokeLaterSafe {
                if (result.isFailure) {
                    statusLabel.text = result.exceptionOrNull()?.message ?: "Start failed"
                }
                refreshServers()
                onServerSelected(serverId)
            }
        }
    }

    private fun onStopServer(serverId: String) {
        mcpHub.stopServer(serverId)
        refreshServers()
        onServerSelected(serverId)
    }

    private fun onRestartServer(serverId: String) {
        scope.launch {
            mcpHub.restartServer(serverId)
            invokeLaterSafe {
                refreshServers()
                onServerSelected(serverId)
            }
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (_isDisposed) return
        invokeLater {
            if (!_isDisposed) action()
        }
    }

    override fun dispose() {
        _isDisposed = true
        scope.cancel()
        logger.info("McpPanel disposed")
    }
}
