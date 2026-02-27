package com.eacape.speccodingplugin.ui.hook

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.hook.HookDefinition
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookExecutionLog
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookYamlCodec
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane

class HookPanel(
    private val project: Project,
    private val listHooksAction: () -> List<HookDefinition> = { HookManager.getInstance(project).listHooks() },
    private val setHookEnabledAction: (hookId: String, enabled: Boolean) -> Boolean = { hookId, enabled ->
        HookManager.getInstance(project).setHookEnabled(hookId, enabled)
    },
    private val listLogsAction: (limit: Int) -> List<HookExecutionLog> = { limit ->
        HookManager.getInstance(project).getExecutionLogs(limit)
    },
    private val clearLogsAction: () -> Unit = { HookManager.getInstance(project).clearExecutionLogs() },
    private val requestAiIntentAction: () -> String? = {
        Messages.showInputDialog(
            project,
            SpecCodingBundle.message("hook.ai.dialog.message"),
            SpecCodingBundle.message("hook.ai.dialog.title"),
            Messages.getQuestionIcon(),
        )
    },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llmRouter by lazy(LazyThreadSafetyMode.NONE) { LlmRouter.getInstance() }
    private val modelRegistry by lazy(LazyThreadSafetyMode.NONE) { ModelRegistry.getInstance() }
    private val settings by lazy(LazyThreadSafetyMode.NONE) { SpecCodingSettingsState.getInstance() }

    @Volatile
    private var isDisposed = false

    private val hooksModel = DefaultListModel<HookDefinition>()
    private val hooksList = JBList(hooksModel)
    private val logArea = JBTextArea()

    private val titleLabel = JBLabel(SpecCodingBundle.message("hook.panel.title"))
    private val statusLabel = JBLabel("")
    private val guideLabel = JBLabel(SpecCodingBundle.message("hook.guide.quickStart"))
    private val refreshButton = JButton(SpecCodingBundle.message("hook.action.refresh"))
    private val openConfigButton = JButton(SpecCodingBundle.message("hook.action.openConfig"))
    private val aiQuickConfigButton = JButton(SpecCodingBundle.message("hook.action.aiQuickConfig"))
    private val enableButton = JButton(SpecCodingBundle.message("hook.action.enable"))
    private val disableButton = JButton(SpecCodingBundle.message("hook.action.disable"))
    private val refreshLogButton = JButton(SpecCodingBundle.message("hook.log.refresh"))
    private val clearLogButton = JButton(SpecCodingBundle.message("hook.log.clear"))
    private val statusChipPanel = JPanel(BorderLayout())

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
        subscribeToEvents()
        refreshData()
    }

    private fun setupUi() {
        listOf(refreshButton, openConfigButton, aiQuickConfigButton, enableButton, disableButton, refreshLogButton, clearLogButton)
            .forEach(::styleActionButton)

        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(titleLabel)
            add(refreshButton)
            add(openConfigButton)
            add(aiQuickConfigButton)
            add(enableButton)
            add(disableButton)
            add(refreshLogButton)
            add(clearLogButton)
        }
        titleLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        titleLabel.foreground = STATUS_TEXT_FG

        guideLabel.font = JBUI.Fonts.smallFont()
        guideLabel.foreground = STATUS_TEXT_FG

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG

        statusChipPanel.isOpaque = true
        statusChipPanel.background = STATUS_CHIP_BG
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = STATUS_CHIP_BORDER,
            arc = JBUI.scale(12),
            top = 4,
            left = 10,
            bottom = 4,
            right = 10,
        )
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)

        val footerPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(guideLabel, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(statusChipPanel, BorderLayout.WEST)
                },
                BorderLayout.SOUTH,
            )
        }

        val toolbarCard = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(14),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(actionRow, BorderLayout.CENTER)
            add(footerPanel, BorderLayout.SOUTH)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        hooksList.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        hooksList.cellRenderer = HookListRenderer()
        hooksList.addListSelectionListener { updateButtonState() }

        hooksList.background = PANEL_SECTION_BG
        hooksList.fixedCellHeight = JBUI.scale(24)

        logArea.isEditable = false
        logArea.lineWrap = true
        logArea.wrapStyleWord = true
        logArea.background = PANEL_SECTION_BG
        logArea.text = SpecCodingBundle.message("hook.log.empty")

        val hooksScroll = JBScrollPane(hooksList).apply {
            border = JBUI.Borders.empty()
        }
        val logsScroll = JBScrollPane(logArea).apply {
            border = JBUI.Borders.empty()
        }

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            createSectionContainer(hooksScroll),
            createSectionContainer(logsScroll),
        ).apply {
            dividerLocation = 320
            dividerSize = JBUI.scale(4)
            border = JBUI.Borders.empty()
            isContinuousLayout = true
            background = PANEL_SECTION_BG
        }
        add(splitPane, BorderLayout.CENTER)

        refreshButton.addActionListener { refreshData() }
        openConfigButton.addActionListener { openHookConfig() }
        aiQuickConfigButton.addActionListener { quickSetupWithAi() }
        refreshLogButton.addActionListener { refreshLogs() }
        enableButton.addActionListener { updateSelectedHookEnabled(true) }
        disableButton.addActionListener { updateSelectedHookEnabled(false) }
        clearLogButton.addActionListener { clearLogs() }

        updateButtonState()
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = javax.swing.BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(1, 5, 1, 5),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val lafWidth = button.preferredSize?.width ?: 0
        val width = maxOf(
            lafWidth,
            textWidth + insets.left + insets.right + JBUI.scale(10),
            JBUI.scale(40),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
    }

    private fun createSectionContainer(content: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_SECTION_BORDER,
                arc = JBUI.scale(12),
                top = 6,
                left = 6,
                bottom = 6,
                right = 6,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun subscribeToEvents() {
        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun refreshData() {
        refreshHooks()
        refreshLogs()
    }

    private fun refreshHooks() {
        val selectedId = hooksList.selectedValue?.id
        runBackground {
            val hooks = listHooksAction().sortedBy { it.name }
            invokeLaterSafe {
                hooksModel.clear()
                hooks.forEach(hooksModel::addElement)

                if (selectedId != null) {
                    setSelectedHookById(selectedId)
                }
                if (hooksList.selectedIndex < 0 && hooksModel.size() > 0) {
                    hooksList.selectedIndex = 0
                }

                statusLabel.text = SpecCodingBundle.message("hook.status.count", hooks.size)
                hooksList.repaint()
                updateButtonState()
            }
        }
    }

    private fun refreshLogs() {
        runBackground {
            val logs = listLogsAction(200)
            invokeLaterSafe {
                logArea.text = if (logs.isEmpty()) {
                    SpecCodingBundle.message("hook.log.empty")
                } else {
                    logs
                        .sortedBy { it.timestamp }
                        .joinToString(separator = "\n") { log ->
                            SpecCodingBundle.message(
                                "hook.log.entry",
                                dateFormatter.format(Instant.ofEpochMilli(log.timestamp)),
                                log.hookName,
                                eventDisplayName(log.event),
                                if (log.success) {
                                    SpecCodingBundle.message("hook.log.result.success")
                                } else {
                                    SpecCodingBundle.message("hook.log.result.failed")
                                },
                                log.message,
                            )
                        }
                }
                logArea.caretPosition = logArea.document.length
            }
        }
    }

    private fun clearLogs() {
        runBackground {
            clearLogsAction()
            invokeLaterSafe {
                statusLabel.text = SpecCodingBundle.message("hook.status.logs.cleared")
                refreshLogs()
            }
        }
    }

    private fun openHookConfig() {
        runBackground {
            val result = runCatching {
                ensureHookConfigFile()
            }
            invokeLaterSafe {
                result
                    .onSuccess { target ->
                        val opened = openHookConfigInEditor(target.path)
                        if (opened) {
                            statusLabel.text = if (target.created) {
                                SpecCodingBundle.message("hook.status.config.created", target.path.fileName.toString())
                            } else {
                                SpecCodingBundle.message("hook.status.config.opened", target.path.fileName.toString())
                            }
                            refreshHooks()
                        } else {
                            statusLabel.text = SpecCodingBundle.message(
                                "hook.status.config.openFailed",
                                SpecCodingBundle.message("common.unknown"),
                            )
                        }
                    }
                    .onFailure { error ->
                        statusLabel.text = SpecCodingBundle.message(
                            "hook.status.config.openFailed",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        )
                    }
            }
        }
    }

    private fun quickSetupWithAi() {
        val userIntentInput = requestAiIntentAction() ?: return
        val userIntent = userIntentInput.trim()
        if (userIntent.isBlank()) {
            statusLabel.text = SpecCodingBundle.message("hook.status.ai.inputRequired")
            return
        }

        statusLabel.text = SpecCodingBundle.message("hook.status.ai.generating")
        val task: suspend () -> Unit = {
            val result = runCatching {
                val target = ensureHookConfigFile()
                val draft = buildHookConfigDraft(userIntent)
                writeHookConfig(target.path, draft.yaml)
                HookConfigApplyResult(target = target, draft = draft)
            }
            invokeLaterSafe {
                result
                    .onSuccess { applied ->
                        val opened = openHookConfigInEditor(applied.target.path)
                        refreshData()
                        statusLabel.text = when {
                            !opened -> SpecCodingBundle.message(
                                "hook.status.config.openFailed",
                                SpecCodingBundle.message("common.unknown"),
                            )

                            applied.draft.source == HookConfigDraftSource.AI -> SpecCodingBundle.message(
                                "hook.status.ai.applied",
                                applied.target.path.fileName.toString(),
                            )

                            else -> SpecCodingBundle.message(
                                "hook.status.ai.applied.fallback",
                                applied.target.path.fileName.toString(),
                            )
                        }
                    }
                    .onFailure { error ->
                        statusLabel.text = SpecCodingBundle.message(
                            "hook.status.ai.failed",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        )
                    }
            }
        }

        if (runSynchronously) {
            runBlocking { task() }
        } else {
            scope.launch(Dispatchers.IO) { task() }
        }
    }

    private suspend fun buildHookConfigDraft(userIntent: String): HookConfigDraft {
        val aiDraft = generateHookConfigByAi(userIntent)
        if (!aiDraft.isNullOrBlank()) {
            return HookConfigDraft(
                yaml = aiDraft,
                source = HookConfigDraftSource.AI,
            )
        }

        return HookConfigDraft(
            yaml = createFallbackHookConfig(userIntent),
            source = HookConfigDraftSource.TEMPLATE,
        )
    }

    private suspend fun generateHookConfigByAi(userIntent: String): String? {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()

        val providerId = resolveQuickSetupProviderId() ?: return null
        val modelId = resolveQuickSetupModelId(providerId)
        val response = runCatching {
            llmRouter.generate(
                providerId = providerId,
                request = LlmRequest(
                    messages = listOf(
                        LlmMessage(role = LlmRole.SYSTEM, content = HOOK_AI_SYSTEM_PROMPT),
                        LlmMessage(role = LlmRole.USER, content = buildHookAiPrompt(userIntent)),
                    ),
                    model = modelId,
                    temperature = 0.2,
                    maxTokens = 1_200,
                    metadata = mapOf("hookQuickSetup" to "true"),
                    workingDirectory = project.basePath,
                ),
            )
        }.getOrNull() ?: return null

        val normalizedYaml = normalizeHookYaml(response.content)
        if (normalizedYaml.isBlank()) {
            return null
        }
        val parsedHooks = runCatching { HookYamlCodec.deserialize(normalizedYaml) }
            .getOrDefault(emptyList())
        if (parsedHooks.isEmpty()) {
            return null
        }
        return normalizedYaml
    }

    private fun resolveQuickSetupProviderId(): String? {
        val availableProviders = llmRouter.availableUiProviders()
        if (availableProviders.isEmpty()) {
            return null
        }

        val preferredProvider = settings.defaultProvider.trim()
        return availableProviders.firstOrNull { it.equals(preferredProvider, ignoreCase = true) }
            ?: availableProviders.firstOrNull()
    }

    private fun resolveQuickSetupModelId(providerId: String): String? {
        val models = modelRegistry.getModelsForProvider(providerId)
        if (models.isEmpty()) {
            return null
        }

        val preferredModel = settings.selectedCliModel.trim()
        return models.firstOrNull { it.id.equals(preferredModel, ignoreCase = true) }?.id
            ?: models.first().id
    }

    private fun buildHookAiPrompt(userIntent: String): String {
        val normalizedIntent = userIntent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        return buildString {
            appendLine("请根据下面的场景生成 hooks.yaml：")
            appendLine()
            appendLine("场景描述：")
            appendLine(normalizedIntent)
            appendLine()
            appendLine("要求：")
            appendLine("1. 只输出 YAML，不要 Markdown 代码块，不要解释。")
            appendLine("2. 必须包含根节点 version: 1 和 hooks。")
            appendLine("3. 每个 hook 必须包含 id/name/event/enabled/actions。")
            appendLine("4. event 只能是 FILE_SAVED、GIT_COMMIT、SPEC_STAGE_CHANGED。")
            appendLine("5. action.type 只能是 RUN_COMMAND 或 SHOW_NOTIFICATION。")
            appendLine("6. RUN_COMMAND 必须包含 command；SHOW_NOTIFICATION 必须包含 message。")
            appendLine("7. 可选 conditions.filePattern 或 conditions.specStage。")
            appendLine("8. 输出 1-3 个最有价值的 hook。")
        }
    }

    private fun normalizeHookYaml(raw: String): String {
        var normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        CODE_FENCE_PATTERN.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let { fenced ->
            if (fenced.isNotBlank()) {
                normalized = fenced
            }
        }

        val lines = normalized.lines()
        val startIndex = lines.indexOfFirst { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("version:") || trimmed.startsWith("hooks:")
        }
        if (startIndex > 0) {
            normalized = lines.drop(startIndex).joinToString("\n").trim()
        }

        if (normalized.isBlank()) {
            return ""
        }
        return normalized.trimEnd() + "\n"
    }

    private fun createFallbackHookConfig(userIntent: String): String {
        val event = inferEventFromIntent(userIntent)
        val idSuffix = when (event) {
            HookEvent.FILE_SAVED -> "file-saved"
            HookEvent.GIT_COMMIT -> "git-commit"
            HookEvent.SPEC_STAGE_CHANGED -> "spec-stage"
        }
        val displayName = when (event) {
            HookEvent.FILE_SAVED -> "Quick File Saved Hook"
            HookEvent.GIT_COMMIT -> "Quick Git Commit Hook"
            HookEvent.SPEC_STAGE_CHANGED -> "Quick Spec Stage Hook"
        }
        val baseMessage = when (event) {
            HookEvent.FILE_SAVED -> "Hook triggered on file save"
            HookEvent.GIT_COMMIT -> "Hook triggered on git commit"
            HookEvent.SPEC_STAGE_CHANGED -> "Hook triggered on spec stage change"
        }
        val intentSnippet = userIntent
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(FALLBACK_MESSAGE_MAX_LENGTH)
        val notificationMessage = if (intentSnippet.isBlank()) {
            baseMessage
        } else {
            "$baseMessage: $intentSnippet"
        }

        return buildString {
            appendLine("version: 1")
            appendLine("hooks:")
            appendLine("  - id: quick-$idSuffix")
            appendLine("    name: \"$displayName\"")
            appendLine("    event: ${event.name}")
            appendLine("    enabled: true")
            if (event == HookEvent.FILE_SAVED) {
                appendLine("    conditions:")
                appendLine("      filePattern: \"**/*\"")
            }
            appendLine("    actions:")
            appendLine("      - type: SHOW_NOTIFICATION")
            appendLine("        message: \"${escapeForYamlDoubleQuote(notificationMessage)}\"")
            appendLine("        level: INFO")
        }
    }

    private fun inferEventFromIntent(userIntent: String): HookEvent {
        val normalized = userIntent.trim().lowercase(Locale.ROOT)
        return when {
            normalized.contains("commit") || normalized.contains("提交") -> HookEvent.GIT_COMMIT
            normalized.contains("spec") || normalized.contains("规格") || normalized.contains("阶段") || normalized.contains("stage") ->
                HookEvent.SPEC_STAGE_CHANGED

            else -> HookEvent.FILE_SAVED
        }
    }

    private fun escapeForYamlDoubleQuote(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun writeHookConfig(path: Path, content: String) {
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun ensureHookConfigFile(): HookConfigTarget {
        val path = hookConfigPath()
        if (Files.exists(path)) {
            return HookConfigTarget(path = path, created = false)
        }

        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            path,
            DEFAULT_HOOK_CONFIG_TEMPLATE,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
        )
        return HookConfigTarget(path = path, created = true)
    }

    private fun hookConfigPath(): Path {
        val basePath = project.basePath
            ?: throw IllegalStateException(SpecCodingBundle.message("hook.error.projectBasePathUnavailable"))
        return Paths.get(basePath).resolve(".spec-coding").resolve("hooks.yaml")
    }

    private fun openHookConfigInEditor(path: Path): Boolean {
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(path.toString().replace('\\', '/'))
            ?: return false
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        return true
    }

    private fun updateSelectedHookEnabled(enabled: Boolean) {
        val selected = hooksList.selectedValue ?: return

        runBackground {
            val updated = setHookEnabledAction(selected.id, enabled)
            invokeLaterSafe {
                if (updated) {
                    val status = if (enabled) {
                        SpecCodingBundle.message("hook.enabled")
                    } else {
                        SpecCodingBundle.message("hook.disabled")
                    }
                    statusLabel.text = SpecCodingBundle.message("hook.status.updated", selected.name, status)
                    refreshHooks()
                } else {
                    statusLabel.text = SpecCodingBundle.message("hook.status.updateFailed", selected.name)
                }
            }
        }
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("hook.panel.title")
        guideLabel.text = SpecCodingBundle.message("hook.guide.quickStart")
        refreshButton.text = SpecCodingBundle.message("hook.action.refresh")
        openConfigButton.text = SpecCodingBundle.message("hook.action.openConfig")
        aiQuickConfigButton.text = SpecCodingBundle.message("hook.action.aiQuickConfig")
        enableButton.text = SpecCodingBundle.message("hook.action.enable")
        disableButton.text = SpecCodingBundle.message("hook.action.disable")
        refreshLogButton.text = SpecCodingBundle.message("hook.log.refresh")
        clearLogButton.text = SpecCodingBundle.message("hook.log.clear")
        styleActionButton(refreshButton)
        styleActionButton(openConfigButton)
        styleActionButton(aiQuickConfigButton)
        styleActionButton(enableButton)
        styleActionButton(disableButton)
        styleActionButton(refreshLogButton)
        styleActionButton(clearLogButton)
        hooksList.repaint()
        if (logArea.text == SpecCodingBundle.message("hook.log.empty")) {
            logArea.text = SpecCodingBundle.message("hook.log.empty")
        }
    }

    private fun updateButtonState() {
        val selected = hooksList.selectedValue
        val hasSelection = selected != null
        enableButton.isEnabled = hasSelection && selected?.enabled == false
        disableButton.isEnabled = hasSelection && selected?.enabled == true
    }

    private fun setSelectedHookById(hookId: String) {
        val index = (0 until hooksModel.size()).firstOrNull { hooksModel.get(it).id == hookId } ?: return
        hooksList.selectedIndex = index
    }

    private fun eventDisplayName(event: HookEvent): String {
        return when (event) {
            HookEvent.FILE_SAVED -> SpecCodingBundle.message("hook.event.fileSaved")
            HookEvent.GIT_COMMIT -> SpecCodingBundle.message("hook.event.gitCommit")
            HookEvent.SPEC_STAGE_CHANGED -> SpecCodingBundle.message("hook.event.specStageChanged")
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (runSynchronously) {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
            return
        }

        if (isDisposed) return
        invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    private fun runBackground(task: () -> Unit) {
        if (runSynchronously) {
            task()
            return
        }
        scope.launch(Dispatchers.IO) { task() }
    }

    internal fun hooksForTest(): List<HookDefinition> {
        return (0 until hooksModel.size()).map { hooksModel.get(it) }
    }

    internal fun logTextForTest(): String = logArea.text

    internal fun statusTextForTest(): String = statusLabel.text

    internal fun selectHookForTest(hookId: String?) {
        if (hookId == null) {
            hooksList.clearSelection()
            return
        }
        setSelectedHookById(hookId)
    }

    internal fun clickEnableForTest() {
        enableButton.doClick()
    }

    internal fun clickDisableForTest() {
        disableButton.doClick()
    }

    internal fun clickRefreshForTest() {
        refreshButton.doClick()
    }

    internal fun clickOpenConfigForTest() {
        openConfigButton.doClick()
    }

    internal fun clickClearLogsForTest() {
        clearLogButton.doClick()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    private inner class HookListRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
            val hook = value as? HookDefinition ?: return@also
            val enabledText = if (hook.enabled) {
                SpecCodingBundle.message("hook.enabled")
            } else {
                SpecCodingBundle.message("hook.disabled")
            }
            text = SpecCodingBundle.message(
                "hook.list.item",
                hook.name,
                eventDisplayName(hook.event),
                enabledText,
            )
        }
    }

    private data class HookConfigTarget(
        val path: Path,
        val created: Boolean,
    )

    private data class HookConfigApplyResult(
        val target: HookConfigTarget,
        val draft: HookConfigDraft,
    )

    private data class HookConfigDraft(
        val yaml: String,
        val source: HookConfigDraftSource,
    )

    private enum class HookConfigDraftSource {
        AI,
        TEMPLATE,
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val CODE_FENCE_PATTERN = Regex("```(?:yaml|yml)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private const val FALLBACK_MESSAGE_MAX_LENGTH = 80
        private const val HOOK_AI_SYSTEM_PROMPT = """
            你是 IntelliJ 插件 hooks.yaml 生成助手。
            你必须严格输出可解析的 YAML，且只输出 YAML。
            不要输出解释、注释、Markdown 代码块。
        """

        private val DEFAULT_HOOK_CONFIG_TEMPLATE = """
            version: 1
            hooks:
              - id: sample-notify
                name: Sample Notify
                event: FILE_SAVED
                enabled: true
                conditions:
                  filePattern: "**/*.md"
                actions:
                  - type: SHOW_NOTIFICATION
                    message: "Hook triggered: {{file.path}}"
                    level: INFO
        """.trimIndent() + "\n"
    }
}
