package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 客户端
 * 实现 JSON-RPC over stdio 协议
 */
class McpClient(
    private val server: McpServer,
    private val scope: CoroutineScope
) {
    private val logger = thisLogger()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // 进程 IO
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    // 请求-响应映射
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()

    // 通知通道
    private val notificationChannel = Channel<JsonRpcNotification>(Channel.UNLIMITED)

    // 是否已初始化
    @Volatile
    private var initialized = false

    /**
     * 启动 MCP Server
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            logger.info("Starting MCP server: ${server.config.name}")

            // 构建进程
            val processBuilder = ProcessBuilder(
                listOf(server.config.command) + server.config.args
            )

            // 设置环境变量
            if (server.config.env.isNotEmpty()) {
                processBuilder.environment().putAll(server.config.env)
            }

            // 重定向错误流
            processBuilder.redirectErrorStream(false)

            // 启动进程
            val process = processBuilder.start()
            server.process = process
            server.status = ServerStatus.STARTING

            // 设置 IO
            writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            reader = BufferedReader(InputStreamReader(process.inputStream))

            // 启动读取协程
            scope.launch {
                readLoop()
            }

            // 初始化握手
            initialize()

            server.status = ServerStatus.RUNNING
            logger.info("MCP server started: ${server.config.name}")
        }
    }

    /**
     * 停止 MCP Server
     */
    fun stop() {
        logger.info("Stopping MCP server: ${server.config.name}")

        try {
            writer?.close()
            reader?.close()
            server.process?.destroy()
            server.process?.waitFor()
        } catch (e: Exception) {
            logger.warn("Error stopping MCP server", e)
        }

        server.status = ServerStatus.STOPPED
        server.process = null
        initialized = false

        logger.info("MCP server stopped: ${server.config.name}")
    }

    /**
     * 初始化握手
     */
    private suspend fun initialize() {
        val params = InitializeParams(
            protocolVersion = McpProtocol.VERSION,
            capabilities = ClientCapabilities(),
            clientInfo = ClientInfo(
                name = McpProtocol.CLIENT_NAME,
                version = McpProtocol.CLIENT_VERSION
            )
        )

        val response = sendRequest(McpMethods.INITIALIZE, json.encodeToJsonElement(
            InitializeParams.serializer(),
            params
        ))

        if (response.error != null) {
            throw Exception("Initialize failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(
            InitializeResult.serializer(),
            response.result!!
        )

        server.capabilities = result.capabilities
        initialized = true

        // 发送 initialized 通知
        sendNotification(McpMethods.INITIALIZED, null)

        logger.info("MCP server initialized: ${result.serverInfo.name} ${result.serverInfo.version}")
    }

    /**
     * 列出工具
     */
    suspend fun listTools(): Result<List<McpTool>> = runCatching {
        checkInitialized()

        val response = sendRequest(McpMethods.TOOLS_LIST, null)

        if (response.error != null) {
            throw Exception("List tools failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(
            ToolsListResult.serializer(),
            response.result!!
        )

        result.tools
    }

    /**
     * 调用工具
     */
    suspend fun callTool(toolName: String, arguments: Map<String, Any>): Result<ToolCallResult> = runCatching {
        checkInitialized()

        val params = ToolsCallParams(
            name = toolName,
            arguments = json.parseToJsonElement(json.encodeToString(arguments))
        )

        val response = sendRequest(
            McpMethods.TOOLS_CALL,
            json.encodeToJsonElement(ToolsCallParams.serializer(), params)
        )

        if (response.error != null) {
            return@runCatching ToolCallResult.Error(
                code = response.error.code,
                message = response.error.message,
                data = response.error.data
            )
        }

        val result = json.decodeFromJsonElement(
            ToolsCallResult.serializer(),
            response.result!!
        )

        ToolCallResult.Success(
            content = result.content,
            isError = result.isError ?: false
        )
    }

    /**
     * 发送请求
     */
    private suspend fun sendRequest(method: String, params: JsonElement?): JsonRpcResponse {
        val requestId = generateRequestId()
        val request = JsonRpcRequest(
            id = requestId,
            method = method,
            params = params
        )

        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[requestId] = deferred

        // 发送请求
        val requestJson = json.encodeToString(request)
        withContext(Dispatchers.IO) {
            writer?.write(requestJson)
            writer?.newLine()
            writer?.flush()
        }

        logger.debug("Sent request: $method (id: $requestId)")

        // 等待响应（超时 30 秒）
        return withTimeout(30_000) {
            deferred.await()
        }
    }

    /**
     * 发送通知
     */
    private suspend fun sendNotification(method: String, params: JsonElement?) {
        val notification = JsonRpcNotification(
            method = method,
            params = params
        )

        val notificationJson = json.encodeToString(notification)
        withContext(Dispatchers.IO) {
            writer?.write(notificationJson)
            writer?.newLine()
            writer?.flush()
        }

        logger.debug("Sent notification: $method")
    }

    /**
     * 读取循环
     */
    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val line = reader?.readLine() ?: break

                if (line.isBlank()) continue

                try {
                    handleMessage(line)
                } catch (e: Exception) {
                    logger.warn("Error handling message: $line", e)
                }
            }
        } catch (e: Exception) {
            logger.warn("Read loop error", e)
            server.status = ServerStatus.ERROR
            server.error = e.message
        }
    }

    /**
     * 处理消息
     */
    private fun handleMessage(message: String) {
        logger.debug("Received message: $message")

        // 尝试解析为响应
        try {
            val response = json.decodeFromString<JsonRpcResponse>(message)
            val deferred = pendingRequests.remove(response.id)
            deferred?.complete(response)
            return
        } catch (e: Exception) {
            // 不是响应，继续尝试通知
        }

        // 尝试解析为通知
        try {
            val notification = json.decodeFromString<JsonRpcNotification>(message)
            notificationChannel.trySend(notification)
        } catch (e: Exception) {
            logger.warn("Failed to parse message: $message", e)
        }
    }

    /**
     * 获取通知流
     */
    fun getNotifications(): Flow<JsonRpcNotification> = flow {
        for (notification in notificationChannel) {
            emit(notification)
        }
    }

    /**
     * 检查是否已初始化
     */
    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("MCP client not initialized")
        }
    }

    /**
     * 生成请求 ID
     */
    private fun generateRequestId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 检查 Server 是否运行
     */
    fun isRunning(): Boolean {
        return server.status == ServerStatus.RUNNING && initialized
    }
}
