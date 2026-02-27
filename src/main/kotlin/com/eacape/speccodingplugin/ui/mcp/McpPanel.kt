package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.mcp.*
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.Locale
import java.util.UUID
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
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val settingsState = SpecCodingSettingsState.getInstance()
    private val aiJson = Json { ignoreUnknownKeys = true }

    private val statusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val guideLabel = JBLabel(SpecCodingBundle.message("mcp.panel.guide"))
    private val aiSetupBtn = JButton(SpecCodingBundle.message("mcp.server.aiSetup"))
    private val refreshBtn = JButton(SpecCodingBundle.message("mcp.server.refresh"))
    private val titleLabel = JBLabel(SpecCodingBundle.message("mcp.panel.title"))

    private var selectedServerId: String? = null
    private var aiSetupInProgress: Boolean = false

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
        toolbar.border = JBUI.Borders.emptyBottom(2)
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 13f)
        toolbar.add(titleLabel)
        toolbar.add(aiSetupBtn)
        toolbar.add(refreshBtn)
        toolbar.add(statusLabel)
        aiSetupBtn.toolTipText = SpecCodingBundle.message("mcp.server.aiSetup.tooltip")
        guideLabel.foreground = JBColor(
            java.awt.Color(108, 115, 128),
            java.awt.Color(150, 158, 170),
        )
        guideLabel.border = JBUI.Borders.empty(0, 2, 4, 2)

        val north = JPanel(BorderLayout())
        north.isOpaque = false
        north.add(toolbar, BorderLayout.NORTH)
        north.add(guideLabel, BorderLayout.SOUTH)
        add(north, BorderLayout.NORTH)

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
        aiSetupBtn.addActionListener { onAiQuickSetup() }
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

        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
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
        statusLabel.text = SpecCodingBundle.message("mcp.server.count", servers.size)
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("mcp.panel.title")
        aiSetupBtn.text = SpecCodingBundle.message("mcp.server.aiSetup")
        aiSetupBtn.toolTipText = SpecCodingBundle.message("mcp.server.aiSetup.tooltip")
        refreshBtn.text = SpecCodingBundle.message("mcp.server.refresh")
        guideLabel.text = SpecCodingBundle.message("mcp.panel.guide")
        serverListPanel.refreshLocalizedTexts()
        val servers = mcpHub.getAllServers()
        statusLabel.text = SpecCodingBundle.message("mcp.server.count", servers.size)
        selectedServerId?.let(::onServerSelected) ?: serverDetailPanel.showEmpty()
    }

    // --- 回调方法 ---

    private fun onServerSelected(serverId: String) {
        selectedServerId = serverId
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
            serverListPanel.setSelectedServer(config.id)
            onServerSelected(config.id)
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
        if (selectedServerId == serverId) {
            selectedServerId = null
        }
        mcpHub.stopServer(serverId)
        mcpHub.unregisterServer(serverId)
        mcpConfigStore.delete(serverId)
        serverDetailPanel.showEmpty()
        refreshServers()
    }

    private fun onAiQuickSetup() {
        if (_isDisposed || aiSetupInProgress) return
        val dialog = McpAiQuickSetupDialog()
        if (!dialog.showAndGet()) {
            return
        }
        val prompt = dialog.promptText.trim()
        if (prompt.isBlank()) {
            return
        }

        setAiSetupInProgress(true)
        statusLabel.text = SpecCodingBundle.message("mcp.server.aiSetup.generating")

        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                suggestMcpConfig(prompt)
            }
            invokeLaterSafe {
                setAiSetupInProgress(false)
                result.onSuccess { draft ->
                    val deduplicatedDraft = ensureUniqueId(draft)
                    val editor = McpServerEditorDialog(draft = deduplicatedDraft)
                    if (!editor.showAndGet()) {
                        statusLabel.text = SpecCodingBundle.message("mcp.server.aiSetup.cancelled")
                        return@onSuccess
                    }
                    val config = editor.result ?: return@onSuccess
                    mcpConfigStore.save(config)
                    mcpHub.registerServer(config)
                    refreshServers()
                    serverListPanel.setSelectedServer(config.id)
                    onServerSelected(config.id)
                    statusLabel.text = SpecCodingBundle.message("mcp.server.aiSetup.saved", config.name)
                }.onFailure { error ->
                    statusLabel.text = SpecCodingBundle.message(
                        "mcp.server.aiSetup.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun setAiSetupInProgress(inProgress: Boolean) {
        aiSetupInProgress = inProgress
        aiSetupBtn.isEnabled = !inProgress
        refreshBtn.isEnabled = !inProgress
    }

    private suspend fun suggestMcpConfig(userPrompt: String): McpServerConfig {
        val aiPrompt = buildAiSetupPrompt(userPrompt)
        val providerId = settingsState.defaultProvider.trim().ifBlank { null }
        val modelId = settingsState.selectedCliModel.trim().ifBlank { null }
        val response = StringBuilder()
        val requestId = UUID.randomUUID().toString()

        projectService.chat(
            providerId = providerId,
            userInput = aiPrompt,
            modelId = modelId,
            operationMode = OperationMode.PLAN,
            planExecuteVerifySections = false,
            requestId = requestId,
        ) { chunk ->
            if (chunk.delta.isNotEmpty()) {
                response.append(chunk.delta)
            }
        }

        return parseAiSuggestion(response.toString(), userPrompt)
    }

    private fun buildAiSetupPrompt(userPrompt: String): String {
        return """
            You are generating MCP server configuration draft for IntelliJ plugin UI.
            User requirement:
            $userPrompt

            Return JSON only, no markdown, no explanation.
            Required schema:
            {
              "id": "lowercase letters, numbers, hyphen/underscore only",
              "name": "display name",
              "command": "command binary",
              "args": ["arg1", "arg2"],
              "env": {"KEY": "VALUE"},
              "transport": "STDIO or SSE",
              "autoStart": false,
              "trusted": false
            }

            Rules:
            - command must not be empty.
            - args/env can be empty.
            - keep trusted=false by default unless user explicitly requests trusted startup.
        """.trimIndent()
    }

    private fun parseAiSuggestion(raw: String, userPrompt: String): McpServerConfig {
        val payload = extractJsonPayload(raw)
        val root = try {
            aiJson.parseToJsonElement(payload).jsonObject
        } catch (error: Exception) {
            throw IllegalStateException(
                SpecCodingBundle.message("mcp.server.aiSetup.invalidJson", raw.take(220)),
                error,
            )
        }

        val command = root.string("command")
        if (command.isBlank()) {
            throw IllegalStateException(SpecCodingBundle.message("mcp.server.aiSetup.missingCommand"))
        }

        val idSeed = root.string("id")
            .ifBlank { root.string("name") }
            .ifBlank { userPrompt }
        val id = normalizeServerId(idSeed)
        val name = root.string("name")
            .ifBlank { formatDisplayNameFromId(id) }

        val args = root.arrayOfStrings("args")
        val env = root.mapOfStrings("env")
        val transport = parseTransport(root.string("transport"))
        val autoStart = root.bool("autoStart")
        val trusted = root.bool("trusted")

        return McpServerConfig(
            id = id,
            name = name,
            command = command.trim(),
            args = args,
            env = env,
            transport = transport,
            autoStart = autoStart,
            trusted = trusted,
        )
    }

    private fun extractJsonPayload(raw: String): String {
        val fenced = JSON_FENCE_REGEX.find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fenced.isNullOrBlank()) {
            return fenced
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1)
        }
        return raw.trim()
    }

    private fun ensureUniqueId(config: McpServerConfig): McpServerConfig {
        val baseId = normalizeServerId(config.id)
        if (mcpConfigStore.getById(baseId) == null) {
            return config.copy(id = baseId)
        }
        var suffix = 2
        var candidate = "$baseId-$suffix"
        while (mcpConfigStore.getById(candidate) != null) {
            suffix += 1
            candidate = "$baseId-$suffix"
        }
        return config.copy(id = candidate)
    }

    private fun parseTransport(raw: String): TransportType {
        val normalized = raw.trim().uppercase(Locale.ROOT)
        return runCatching {
            TransportType.valueOf(normalized)
        }.getOrDefault(TransportType.STDIO)
    }

    private fun normalizeServerId(seed: String): String {
        val normalized = seed
            .trim()
            .lowercase(Locale.ROOT)
            .replace(ID_NORMALIZE_REGEX, "-")
            .trim('-', '_')
        return normalized.ifBlank { "mcp-server" }
    }

    private fun formatDisplayNameFromId(id: String): String {
        return id
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
            .ifBlank { id }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive ?: return ""
        return value.contentOrNull?.trim().orEmpty()
    }

    private fun JsonObject.bool(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: false
    }

    private fun JsonObject.arrayOfStrings(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array
            .mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.trim()
            }
            .filter { it.isNotEmpty() }
    }

    private fun JsonObject.mapOfStrings(key: String): Map<String, String> {
        val obj = this[key] as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (rawKey, value) ->
            val normalizedKey = rawKey.trim()
            if (normalizedKey.isEmpty()) return@mapNotNull null
            normalizedKey to jsonElementToString(value)
        }.toMap()
    }

    private fun jsonElementToString(element: JsonElement): String {
        val primitive = element as? JsonPrimitive
        return primitive?.contentOrNull ?: element.toString()
    }

    private fun onStartServer(serverId: String) {
        scope.launch {
            val result = mcpHub.startServer(serverId)
            invokeLaterSafe {
                if (result.isFailure) {
                    statusLabel.text = result.exceptionOrNull()?.message
                        ?: SpecCodingBundle.message("mcp.server.startFailed")
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

    companion object {
        private val JSON_FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val ID_NORMALIZE_REGEX = Regex("[^a-z0-9_-]+")
    }
}
