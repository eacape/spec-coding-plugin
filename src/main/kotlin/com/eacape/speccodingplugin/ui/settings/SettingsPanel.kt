package com.eacape.speccodingplugin.ui.settings

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.InterfaceLanguage
import com.eacape.speccodingplugin.i18n.LocaleManager
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.skill.TeamSkillSyncResult
import com.eacape.speccodingplugin.skill.TeamSkillSyncService
import com.eacape.speccodingplugin.ui.hook.HookPanel
import com.eacape.speccodingplugin.ui.mcp.McpPanel
import com.eacape.speccodingplugin.ui.prompt.PromptManagerPanel
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * 内嵌在 Tool Window 中的设置面板
 */
class SettingsPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val settings = SpecCodingSettingsState.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val defaultProviderCombo = ComboBox<String>()
    private val defaultModelCombo = ComboBox<ModelInfo>()
    private val useProxyCheckBox = JBCheckBox()
    private val proxyHostField = JBTextField()
    private val proxyPortField = JBTextField()
    private val autoSaveCheckBox = JBCheckBox()
    private val maxHistorySizeField = JBTextField()
    private val codexCliPathField = JBTextField()
    private val claudeCodeCliPathField = JBTextField()
    private val detectCliButton = JButton(SpecCodingBundle.message("settings.cli.detectButton"))
    private val claudeCliStatusLabel = JBLabel("")
    private val codexCliStatusLabel = JBLabel("")
    private val defaultModeField = JBTextField()
    private val interfaceLanguageCombo = ComboBox(InterfaceLanguage.entries.toTypedArray())
    private val teamPromptRepoUrlField = JBTextField()
    private val teamPromptRepoBranchField = JBTextField()
    private val teamSkillRepoUrlField = JBTextField()
    private val teamSkillRepoBranchField = JBTextField()

    private val pullTeamSkillsButton = JButton(SpecCodingBundle.message("action.team.skills.pull.text"))
    private val pushTeamSkillsButton = JButton(SpecCodingBundle.message("action.team.skills.push.text"))
    private val skillSyncStatusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val applyButton = JButton(SpecCodingBundle.message("settings.apply"))

    private val sectionListModel = DefaultListModel<SidebarSection>()
    private val sectionList = JBList(sectionListModel)
    private val sectionCardLayout = CardLayout()
    private val sectionCardPanel = JPanel(sectionCardLayout)
    private val sectionTitleLabel = JBLabel("")

    private val promptPanel = PromptManagerPanel(project)
    private val mcpPanel = McpPanel(project)
    private val hookPanel = HookPanel(project)

    init {
        Disposer.register(this, promptPanel)
        Disposer.register(this, mcpPanel)
        Disposer.register(this, hookPanel)

        buildUi()
        loadFromSettings()
        sectionList.selectedIndex = 0
    }

    private fun buildUi() {
        configureComboRenderers()
        refreshProviderComboPreservingSelection(settings.defaultProvider)
        defaultProviderCombo.addActionListener { refreshModelCombo() }

        claudeCodeCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.claudePath.placeholder")
        codexCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.codexPath.placeholder")

        detectCliButton.addActionListener { detectCliTools() }
        pullTeamSkillsButton.addActionListener { runTeamSkillSync(push = false) }
        pushTeamSkillsButton.addActionListener { runTeamSkillSync(push = true) }
        applyButton.addActionListener { applySettings() }

        styleActionButton(detectCliButton)
        styleActionButton(pullTeamSkillsButton)
        styleActionButton(pushTeamSkillsButton)
        styleActionButton(applyButton, emphasized = true)

        buildSectionCards()

        val sidebarCard = createSidebarCard()
        val contentCard = createContentCard()

        val centerPanel = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10)
            add(sidebarCard, BorderLayout.WEST)
            add(contentCard, BorderLayout.CENTER)
        }

        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 10, 8, 10)
            add(applyButton)
        }

        add(centerPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
        updateCliStatusLabels()
    }

    private fun configureComboRenderers() {
        defaultProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = when (value) {
                ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli")
                CodexCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli")
                else -> value ?: ""
            }
        }

        defaultModelCombo.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = value?.name ?: ""
        }

        interfaceLanguageCombo.renderer = SimpleListCellRenderer.create<InterfaceLanguage> { label, value, _ ->
            label.text = value?.let { SpecCodingBundle.messageOrDefault(it.labelKey, it.code) } ?: ""
        }
    }

    private fun buildSectionCards() {
        SidebarSection.entries.forEach { sectionListModel.addElement(it) }

        sectionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sectionList.visibleRowCount = SidebarSection.entries.size
        sectionList.fixedCellHeight = JBUI.scale(36)
        sectionList.cellRenderer = SidebarSectionRenderer()
        sectionList.border = JBUI.Borders.empty(6, 6, 6, 6)
        sectionList.background = SIDEBAR_BG
        sectionList.selectionBackground = SIDEBAR_SELECTION_BG
        sectionList.selectionForeground = SIDEBAR_SELECTION_FG
        sectionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showSelectedSection()
            }
        }

        sectionCardPanel.isOpaque = false
        sectionCardPanel.add(createBasicSection(), SidebarSection.BASIC.cardId)
        sectionCardPanel.add(createEmbeddedSection(promptPanel), SidebarSection.PROMPTS.cardId)
        sectionCardPanel.add(createEmbeddedSection(mcpPanel), SidebarSection.MCP.cardId)
        sectionCardPanel.add(createSkillsSection(), SidebarSection.SKILLS.cardId)
        sectionCardPanel.add(createEmbeddedSection(hookPanel), SidebarSection.HOOKS.cardId)
    }

    private fun createSidebarCard(): JPanel {
        val scrollPane = JBScrollPane(sectionList).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = SIDEBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SIDEBAR_BORDER,
                arc = JBUI.scale(14),
                top = 6,
                left = 6,
                bottom = 6,
                right = 6,
            )
            preferredSize = JBUI.size(170, 0)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun createContentCard(): JPanel {
        sectionTitleLabel.font = sectionTitleLabel.font.deriveFont(Font.BOLD, 15f)
        sectionTitleLabel.foreground = CONTENT_TITLE_FG
        sectionTitleLabel.border = JBUI.Borders.empty(0, 0, 8, 0)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, CONTENT_HEADER_BORDER)
            add(sectionTitleLabel, BorderLayout.WEST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CONTENT_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = CONTENT_BORDER,
                arc = JBUI.scale(14),
                top = 10,
                left = 12,
                bottom = 10,
                right = 12,
            )
            add(header, BorderLayout.NORTH)
            add(sectionCardPanel, BorderLayout.CENTER)
        }
    }

    private fun createBasicSection(): JPanel {
        val cliDetectPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(detectCliButton)
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.cliTools")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.claudePath"), claudeCodeCliPathField)
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.codexPath"), codexCliPathField)
            .addComponent(cliDetectPanel)
            .addLabeledComponent("Claude CLI:", claudeCliStatusLabel)
            .addLabeledComponent("Codex CLI:", codexCliStatusLabel)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.general")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultProvider"), defaultProviderCombo)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultModel"), defaultModelCombo)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.interfaceLanguage"), interfaceLanguageCombo)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.teamSync")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.promptRepoUrl"), teamPromptRepoUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.promptBranch"), teamPromptRepoBranchField)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.proxy")}</b></html>"))
            .addComponent(useProxyCheckBox.apply { text = SpecCodingBundle.message("settings.proxy.use") })
            .addLabeledComponent(SpecCodingBundle.message("settings.proxy.host"), proxyHostField)
            .addLabeledComponent(SpecCodingBundle.message("settings.proxy.port"), proxyPortField)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.other")}</b></html>"))
            .addComponent(autoSaveCheckBox.apply { text = SpecCodingBundle.message("settings.other.autoSave") })
            .addLabeledComponent(SpecCodingBundle.message("settings.other.maxHistorySize"), maxHistorySizeField)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.operationMode")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.operationMode.defaultMode"), defaultModeField)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty(10, 8, 10, 8)
            }

        val scrollPane = JBScrollPane(formPanel).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun createSkillsSection(): JPanel {
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(pullTeamSkillsButton)
            add(pushTeamSkillsButton)
        }

        skillSyncStatusLabel.font = JBUI.Fonts.smallFont()
        setSkillSyncStatus(
            text = SpecCodingBundle.message("toolwindow.status.ready"),
            tone = SkillSyncTone.NORMAL,
        )

        val statusChip = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = SKILL_STATUS_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SKILL_STATUS_BORDER,
                arc = JBUI.scale(12),
                top = 4,
                left = 10,
                bottom = 4,
                right = 10,
            )
            add(skillSyncStatusLabel, BorderLayout.CENTER)
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.skills.sync.title")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.skillRepoUrl"), teamSkillRepoUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.skillBranch"), teamSkillRepoBranchField)
            .addVerticalGap(8)
            .addComponent(actionRow)
            .addVerticalGap(8)
            .addComponent(statusChip)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty(10, 8, 10, 8)
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(formPanel, BorderLayout.NORTH)
        }
    }

    private fun createEmbeddedSection(component: JPanel): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2)
            add(component, BorderLayout.CENTER)
        }
    }

    private fun showSelectedSection() {
        val selected = sectionList.selectedValue ?: SidebarSection.BASIC
        sectionTitleLabel.text = SpecCodingBundle.message(selected.titleKey)
        sectionCardLayout.show(sectionCardPanel, selected.cardId)
    }

    private fun styleActionButton(
        button: JButton,
        emphasized: Boolean = false,
    ) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.emptyInsets()
        button.foreground = if (emphasized) ACTION_PRIMARY_FG else ACTION_BUTTON_FG
        button.background = if (emphasized) ACTION_PRIMARY_BG else ACTION_BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(
                if (emphasized) ACTION_PRIMARY_BORDER else ACTION_BUTTON_BORDER,
                JBUI.scale(10),
            ),
            JBUI.Borders.empty(4, 10, 4, 10),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val width = maxOf(
            textWidth + insets.left + insets.right + JBUI.scale(14),
            JBUI.scale(64),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
    }

    private fun refreshProviderComboPreservingSelection(preferredProvider: String?) {
        val router = LlmRouter.getInstance()
        val providers = router.availableUiProviders()
        defaultProviderCombo.removeAllItems()
        providers.forEach { defaultProviderCombo.addItem(it) }
        val selected = preferredProvider?.takeIf { providers.contains(it) } ?: providers.firstOrNull()
        if (selected != null) {
            defaultProviderCombo.selectedItem = selected
        }
    }

    private fun loadFromSettings() {
        refreshProviderComboPreservingSelection(settings.defaultProvider)
        refreshModelCombo()
        interfaceLanguageCombo.selectedItem = InterfaceLanguage.fromCode(settings.interfaceLanguage)
        teamPromptRepoUrlField.text = settings.teamPromptRepoUrl
        teamPromptRepoBranchField.text = settings.teamPromptRepoBranch
        teamSkillRepoUrlField.text = settings.teamSkillRepoUrl
        teamSkillRepoBranchField.text = settings.teamSkillRepoBranch
        useProxyCheckBox.isSelected = settings.useProxy
        proxyHostField.text = settings.proxyHost
        proxyPortField.text = settings.proxyPort.toString()
        autoSaveCheckBox.isSelected = settings.autoSaveConversation
        maxHistorySizeField.text = settings.maxHistorySize.toString()
        codexCliPathField.text = settings.codexCliPath
        claudeCodeCliPathField.text = settings.claudeCodeCliPath
        defaultModeField.text = settings.defaultOperationMode
    }

    private fun applySettings() {
        settings.defaultProvider = (defaultProviderCombo.selectedItem as? String) ?: settings.defaultProvider
        settings.selectedCliModel = (defaultModelCombo.selectedItem as? ModelInfo)?.id ?: settings.selectedCliModel
        val selectedLanguage = (interfaceLanguageCombo.selectedItem as? InterfaceLanguage) ?: InterfaceLanguage.AUTO
        val localeChanged = LocaleManager.getInstance().setLanguage(selectedLanguage, reason = "settings-panel-apply")
        settings.teamPromptRepoUrl = teamPromptRepoUrlField.text.trim()
        settings.teamPromptRepoBranch = teamPromptRepoBranchField.text.trim().ifBlank { "main" }
        settings.teamSkillRepoUrl = teamSkillRepoUrlField.text.trim()
        settings.teamSkillRepoBranch = teamSkillRepoBranchField.text.trim().ifBlank { "main" }
        settings.useProxy = useProxyCheckBox.isSelected
        settings.proxyHost = proxyHostField.text
        settings.proxyPort = proxyPortField.text.toIntOrNull() ?: 8080
        settings.autoSaveConversation = autoSaveCheckBox.isSelected
        settings.maxHistorySize = maxHistorySizeField.text.toIntOrNull() ?: 100
        settings.codexCliPath = codexCliPathField.text
        settings.claudeCodeCliPath = claudeCodeCliPathField.text
        settings.defaultOperationMode = defaultModeField.text

        GlobalConfigSyncService.getInstance().notifyGlobalConfigChanged(
            sourceProject = null,
            reason = if (localeChanged != null) "settings-panel-apply-with-locale" else "settings-panel-apply",
        )
    }

    private fun detectCliTools() {
        detectCliButton.isEnabled = false
        detectCliButton.text = SpecCodingBundle.message("settings.cli.detecting")
        val discoveryService = CliDiscoveryService.getInstance()
        scope.launch {
            discoveryService.discoverAll()
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                updateCliStatusLabels()
                if (discoveryService.claudeInfo.available && claudeCodeCliPathField.text.isBlank()) {
                    claudeCodeCliPathField.text = discoveryService.claudeInfo.path
                }
                if (discoveryService.codexInfo.available && codexCliPathField.text.isBlank()) {
                    codexCliPathField.text = discoveryService.codexInfo.path
                }
                LlmRouter.getInstance().refreshProviders()
                val currentProvider = defaultProviderCombo.selectedItem as? String
                refreshProviderComboPreservingSelection(currentProvider ?: settings.defaultProvider)
                ModelRegistry.getInstance().refreshFromDiscovery()
                refreshModelCombo()
                detectCliButton.isEnabled = true
                detectCliButton.text = SpecCodingBundle.message("settings.cli.detectButton")
            }
        }
    }

    private fun updateCliStatusLabels() {
        val discoveryService = CliDiscoveryService.getInstance()
        val claudeInfo = discoveryService.claudeInfo
        if (claudeInfo.available) {
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.found", claudeInfo.version ?: "unknown")
            claudeCliStatusLabel.foreground = STATUS_SUCCESS_FG
        } else {
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.notFound")
            claudeCliStatusLabel.foreground = STATUS_NORMAL_FG
        }
        val codexInfo = discoveryService.codexInfo
        if (codexInfo.available) {
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.found", codexInfo.version ?: "unknown")
            codexCliStatusLabel.foreground = STATUS_SUCCESS_FG
        } else {
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.notFound")
            codexCliStatusLabel.foreground = STATUS_NORMAL_FG
        }
    }

    private fun refreshModelCombo() {
        val selectedProvider = defaultProviderCombo.selectedItem as? String ?: return
        val models = ModelRegistry.getInstance().getModelsForProvider(selectedProvider)
        defaultModelCombo.removeAllItems()
        models.forEach { defaultModelCombo.addItem(it) }
        val match = models.find { it.id == settings.selectedCliModel }
        if (match != null) {
            defaultModelCombo.selectedItem = match
        } else if (defaultModelCombo.itemCount > 0) {
            defaultModelCombo.selectedIndex = 0
        }
    }

    private fun runTeamSkillSync(push: Boolean) {
        settings.teamSkillRepoUrl = teamSkillRepoUrlField.text.trim()
        settings.teamSkillRepoBranch = teamSkillRepoBranchField.text.trim().ifBlank { "main" }

        val taskTitle = SpecCodingBundle.message(
            if (push) "team.skillSync.task.push" else "team.skillSync.task.pull",
        )
        pullTeamSkillsButton.isEnabled = false
        pushTeamSkillsButton.isEnabled = false
        setSkillSyncStatus(taskTitle, SkillSyncTone.NORMAL)

        val service = TeamSkillSyncService.getInstance(project)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, taskTitle, false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = taskTitle
                    val result = if (push) service.pushToTeamRepo() else service.pullFromTeamRepo()
                    SwingUtilities.invokeLater {
                        if (isDisposed || project.isDisposed) {
                            return@invokeLater
                        }
                        applySkillSyncResult(result, push)
                        pullTeamSkillsButton.isEnabled = true
                        pushTeamSkillsButton.isEnabled = true
                    }
                }
            },
        )
    }

    private fun applySkillSyncResult(
        result: Result<TeamSkillSyncResult>,
        push: Boolean,
    ) {
        result
            .onSuccess { payload ->
                if (push) {
                    if (payload.noChanges) {
                        setSkillSyncStatus(
                            SpecCodingBundle.message("team.skillSync.push.noChanges", payload.branch),
                            SkillSyncTone.NORMAL,
                        )
                    } else {
                        setSkillSyncStatus(
                            SpecCodingBundle.message(
                                "team.skillSync.push.success",
                                payload.syncedFiles,
                                payload.branch,
                                payload.commitId ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillSyncTone.SUCCESS,
                        )
                    }
                } else {
                    setSkillSyncStatus(
                        SpecCodingBundle.message("team.skillSync.pull.success", payload.syncedFiles, payload.branch),
                        SkillSyncTone.SUCCESS,
                    )
                }
            }
            .onFailure { error ->
                val messageKey = if (push) "team.skillSync.push.failed" else "team.skillSync.pull.failed"
                setSkillSyncStatus(
                    SpecCodingBundle.message(
                        messageKey,
                        error.message ?: SpecCodingBundle.message("team.skillSync.error.generic"),
                    ),
                    SkillSyncTone.ERROR,
                )
            }
    }

    private fun setSkillSyncStatus(
        text: String,
        tone: SkillSyncTone,
    ) {
        skillSyncStatusLabel.text = text
        skillSyncStatusLabel.foreground = when (tone) {
            SkillSyncTone.NORMAL -> STATUS_NORMAL_FG
            SkillSyncTone.SUCCESS -> STATUS_SUCCESS_FG
            SkillSyncTone.ERROR -> STATUS_ERROR_FG
        }
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    private enum class SidebarSection(
        val cardId: String,
        val titleKey: String,
    ) {
        BASIC("basic", "settings.sidebar.basic"),
        PROMPTS("prompts", "settings.sidebar.prompts"),
        MCP("mcp", "settings.sidebar.mcp"),
        SKILLS("skills", "settings.sidebar.skills"),
        HOOKS("hooks", "settings.sidebar.hooks"),
    }

    private enum class SkillSyncTone {
        NORMAL,
        SUCCESS,
        ERROR,
    }

    private class SidebarSectionRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val label = component as? JLabel ?: return component
            val section = value as? SidebarSection ?: return component
            label.text = SpecCodingBundle.message(section.titleKey)
            label.border = JBUI.Borders.empty(8, 10)
            label.font = label.font.deriveFont(Font.BOLD, 12f)
            return label
        }
    }

    companion object {
        private val SIDEBAR_BG = JBColor(Color(245, 249, 255), Color(54, 60, 70))
        private val SIDEBAR_BORDER = JBColor(Color(204, 218, 238), Color(84, 94, 109))
        private val SIDEBAR_SELECTION_BG = JBColor(Color(221, 235, 255), Color(76, 92, 117))
        private val SIDEBAR_SELECTION_FG = JBColor(Color(45, 69, 107), Color(217, 228, 245))
        private val CONTENT_BG = JBColor(Color(250, 252, 255), Color(50, 56, 64))
        private val CONTENT_BORDER = JBColor(Color(204, 216, 234), Color(83, 91, 103))
        private val CONTENT_HEADER_BORDER = JBColor(Color(216, 226, 242), Color(86, 95, 107))
        private val CONTENT_TITLE_FG = JBColor(Color(46, 65, 94), Color(205, 217, 235))
        private val ACTION_BUTTON_BG = JBColor(Color(238, 246, 255), Color(65, 73, 86))
        private val ACTION_BUTTON_BORDER = JBColor(Color(178, 198, 226), Color(103, 117, 138))
        private val ACTION_BUTTON_FG = JBColor(Color(45, 67, 103), Color(204, 216, 235))
        private val ACTION_PRIMARY_BG = JBColor(Color(213, 228, 250), Color(77, 98, 128))
        private val ACTION_PRIMARY_BORDER = JBColor(Color(154, 180, 219), Color(116, 137, 169))
        private val ACTION_PRIMARY_FG = JBColor(Color(37, 57, 89), Color(223, 232, 246))
        private val SKILL_STATUS_BG = JBColor(Color(238, 245, 255), Color(65, 76, 93))
        private val SKILL_STATUS_BORDER = JBColor(Color(182, 200, 226), Color(101, 118, 142))
        private val STATUS_NORMAL_FG = JBColor(Color(92, 106, 127), Color(177, 188, 204))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val STATUS_ERROR_FG = JBColor(Color(171, 55, 69), Color(226, 144, 154))
    }
}
