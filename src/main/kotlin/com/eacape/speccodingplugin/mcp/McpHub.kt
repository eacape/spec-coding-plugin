package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Hub 状态变更监听器
 */
interface McpHubListener {
    fun onServerStatusChanged(serverId: String, status: ServerStatus) {}
    fun onToolsDiscovered(serverId: String, tools: List<McpTool>) {}

    companion object {
        val TOPIC: Topic<McpHubListener> = Topic.create(
            "McpHub.StatusChanged",
            McpHubListener::class.java
        )
    }
}

/**
 * MCP Hub
 * 管理多个 MCP Server 的生命周期
 */
@Service(Service.Level.PROJECT)
class McpHub(private val project: Project) : Disposable {
    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Server 实例映射
    private val servers = ConcurrentHashMap<String, McpServer>()

    // Client 实例映射
    private val clients = ConcurrentHashMap<String, McpClient>()

    // 工具注册表
    private val toolRegistry = McpToolRegistry()

    /**
     * 注册 Server 配置
     */
    fun registerServer(config: McpServerConfig): Result<Unit> {
        return runCatching {
            if (servers.containsKey(config.id)) {
                throw IllegalArgumentException("Server already registered: ${config.id}")
            }

            val server = McpServer(config)
            servers[config.id] = server

            logger.info("Registered MCP server: ${config.name} (${config.id})")

            // 如果配置了自动启动，则启动
            if (config.autoStart) {
                scope.launch {
                    startServer(config.id)
                }
            }
        }
    }

    /**
     * 注销 Server
     */
    fun unregisterServer(serverId: String): Result<Unit> {
        return runCatching {
            // 先停止 Server
            stopServer(serverId).getOrThrow()

            // 移除注册
            servers.remove(serverId)
            logger.info("Unregistered MCP server: $serverId")
        }
    }

    /**
     * 启动 Server
     */
    suspend fun startServer(serverId: String): Result<Unit> {
        return runCatching {
            val server = servers[serverId]
                ?: throw IllegalArgumentException("Server not found: $serverId")

            if (server.status == ServerStatus.RUNNING) {
                logger.warn("Server already running: $serverId")
                return@runCatching
            }

            // 安全校验
            McpSecurityValidator.validateBeforeStart(server.config).getOrThrow()

            // 创建客户端
            val client = McpClient(server, scope)
            clients[serverId] = client

            // 启动 Server
            client.start().getOrThrow()

            // 发现工具
            discoverTools(serverId)

            // 通知状态变更
            notifyStatusChanged(serverId, ServerStatus.RUNNING)

            logger.info("Started MCP server: ${server.config.name}")
        }
    }

    /**
     * 停止 Server
     */
    fun stopServer(serverId: String): Result<Unit> {
        return runCatching {
            val client = clients.remove(serverId)
            if (client != null) {
                client.stop()
                logger.info("Stopped MCP server: $serverId")
            }

            // 更新状态
            servers[serverId]?.status = ServerStatus.STOPPED

            // 清除工具注册
            toolRegistry.clearServerTools(serverId)

            // 通知状态变更
            notifyStatusChanged(serverId, ServerStatus.STOPPED)
        }
    }

    /**
     * 重启 Server
     */
    suspend fun restartServer(serverId: String): Result<Unit> {
        return runCatching {
            stopServer(serverId).getOrThrow()
            delay(1000) // 等待 1 秒
            startServer(serverId).getOrThrow()
        }
    }

    /**
     * 发现工具
     */
    private suspend fun discoverTools(serverId: String) {
        val client = clients[serverId] ?: return

        try {
            val tools = client.listTools().getOrThrow()
            toolRegistry.registerTools(serverId, tools)
            logger.info("Discovered ${tools.size} tools from server: $serverId")

            // 通知工具发现
            notifyToolsDiscovered(serverId, tools)
        } catch (e: Exception) {
            logger.warn("Failed to discover tools from server: $serverId", e)
        }
    }

    /**
     * 调用工具
     */
    suspend fun callTool(request: ToolCallRequest): Result<ToolCallResult> {
        return runCatching {
            val client = clients[request.serverId]
                ?: throw IllegalArgumentException("Server not running: ${request.serverId}")

            client.callTool(request.toolName, request.arguments).getOrThrow()
        }
    }

    /**
     * 获取所有 Server
     */
    fun getAllServers(): List<McpServer> {
        return servers.values.toList()
    }

    /**
     * 获取运行中的 Server
     */
    fun getRunningServers(): List<McpServer> {
        return servers.values.filter { it.status == ServerStatus.RUNNING }
    }

    /**
     * 获取 Server
     */
    fun getServer(serverId: String): McpServer? {
        return servers[serverId]
    }

    /**
     * 获取所有工具
     */
    fun getAllTools(): List<Pair<String, McpTool>> {
        return toolRegistry.getAllTools()
    }

    /**
     * 获取指定 Server 的工具
     */
    fun getServerTools(serverId: String): List<McpTool> {
        return toolRegistry.getServerTools(serverId)
    }

    /**
     * 查找工具
     */
    fun findTool(serverId: String, toolName: String): McpTool? {
        return toolRegistry.findTool(serverId, toolName)
    }

    /**
     * 健康检查
     */
    suspend fun healthCheck(serverId: String): Result<Boolean> {
        return runCatching {
            val client = clients[serverId]
                ?: return@runCatching false

            client.isRunning()
        }
    }

    /**
     * 自动重连
     */
    fun enableAutoReconnect(serverId: String, enabled: Boolean = true) {
        if (!enabled) return

        scope.launch {
            while (true) {
                delay(10_000) // 每 10 秒检查一次

                val server = servers[serverId] ?: break
                if (server.status == ServerStatus.ERROR || server.status == ServerStatus.STOPPED) {
                    logger.info("Auto-reconnecting to server: $serverId")
                    try {
                        restartServer(serverId).getOrThrow()
                    } catch (e: Exception) {
                        logger.warn("Auto-reconnect failed: $serverId", e)
                    }
                }
            }
        }
    }

    /**
     * 从持久化存储加载配置
     */
    fun loadPersistedConfigs() {
        val configStore = McpConfigStore.getInstance(project)
        val configs = configStore.getAll()
        for (config in configs) {
            if (!servers.containsKey(config.id)) {
                registerServer(config)
            }
        }
        logger.info("Loaded ${configs.size} persisted MCP server configs")
    }

    /**
     * 更新 Server 配置（同步到内存）
     */
    fun updateServerConfig(config: McpServerConfig) {
        val existing = servers[config.id]
        if (existing != null) {
            servers[config.id] = existing.copy(config = config)
        } else {
            servers[config.id] = McpServer(config)
        }
    }

    /**
     * 通知状态变更
     */
    private fun notifyStatusChanged(serverId: String, status: ServerStatus) {
        try {
            project.messageBus.syncPublisher(McpHubListener.TOPIC)
                .onServerStatusChanged(serverId, status)
        } catch (e: Exception) {
            logger.debug("Failed to notify status change", e)
        }
    }

    /**
     * 通知工具发现
     */
    private fun notifyToolsDiscovered(serverId: String, tools: List<McpTool>) {
        try {
            project.messageBus.syncPublisher(McpHubListener.TOPIC)
                .onToolsDiscovered(serverId, tools)
        } catch (e: Exception) {
            logger.debug("Failed to notify tools discovered", e)
        }
    }

    /**
     * 清理资源
     */
    override fun dispose() {
        // 停止所有 Server
        servers.keys.forEach { serverId ->
            stopServer(serverId)
        }

        // 取消协程作用域
        scope.cancel()

        logger.info("MCP Hub disposed")
    }

    companion object {
        fun getInstance(project: Project): McpHub = project.service()
    }
}

/**
 * MCP 工具注册表
 */
class McpToolRegistry {
    private val logger = thisLogger()

    // Server ID -> Tools 映射
    private val serverTools = ConcurrentHashMap<String, List<McpTool>>()

    /**
     * 注册工具
     */
    fun registerTools(serverId: String, tools: List<McpTool>) {
        serverTools[serverId] = tools
        logger.info("Registered ${tools.size} tools for server: $serverId")
    }

    /**
     * 清除 Server 的工具
     */
    fun clearServerTools(serverId: String) {
        serverTools.remove(serverId)
        logger.info("Cleared tools for server: $serverId")
    }

    /**
     * 获取所有工具
     */
    fun getAllTools(): List<Pair<String, McpTool>> {
        return serverTools.flatMap { (serverId, tools) ->
            tools.map { serverId to it }
        }
    }

    /**
     * 获取指定 Server 的工具
     */
    fun getServerTools(serverId: String): List<McpTool> {
        return serverTools[serverId] ?: emptyList()
    }

    /**
     * 查找工具
     */
    fun findTool(serverId: String, toolName: String): McpTool? {
        return serverTools[serverId]?.firstOrNull { it.name == toolName }
    }

    /**
     * 搜索工具
     */
    fun searchTools(query: String): List<Pair<String, McpTool>> {
        return getAllTools().filter { (_, tool) ->
            tool.name.contains(query, ignoreCase = true) ||
                    tool.description?.contains(query, ignoreCase = true) == true
        }
    }
}
