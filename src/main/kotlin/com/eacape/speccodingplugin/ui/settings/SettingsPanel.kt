package com.eacape.speccodingplugin.ui.settings

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.InterfaceLanguage
import com.eacape.speccodingplugin.i18n.LocaleManager
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.skill.Skill
import com.eacape.speccodingplugin.skill.SkillDiscoverySnapshot
import com.eacape.speccodingplugin.skill.SkillRegistry
import com.eacape.speccodingplugin.skill.SkillScope
import com.eacape.speccodingplugin.skill.SkillSourceType
import com.eacape.speccodingplugin.ui.hook.HookPanel
import com.eacape.speccodingplugin.ui.mcp.McpPanel
import com.eacape.speccodingplugin.ui.prompt.PromptManagerPanel
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.IconLoader
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.AbstractBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 内嵌在 Tool Window 中的设置面板
 */
class SettingsPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val settings = SpecCodingSettingsState.getInstance()
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val skillRegistry: SkillRegistry = SkillRegistry.getInstance(project)
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

    private val skillRefreshButton = JButton(SpecCodingBundle.message("settings.skills.discover.refresh"))
    private val skillGenerateButton = JButton(SpecCodingBundle.message("settings.skills.generate.action"))
    private val skillTargetScopeCombo = ComboBox(SkillScope.entries.toTypedArray())
    private val skillGenerateRequirementField = JBTextField()
    private val skillDiscoveryStatusLabel = JBLabel(SpecCodingBundle.message("settings.skills.discovery.empty"))
    private val skillGenerationStatusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val skillRootsListModel = DefaultListModel<String>()
    private val skillRootsList = JBList(skillRootsListModel)
    private val skillsListModel = DefaultListModel<String>()
    private val skillsList = JBList(skillsListModel)
    private val skillJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val sectionListModel = DefaultListModel<SidebarSection>()
    private val sectionList = JBList(sectionListModel)
    private val sectionCardLayout = CardLayout()
    private val sectionCardPanel = JPanel(sectionCardLayout)
    private var syncingUi = false
    private val autoSaveTimer = Timer(AUTO_SAVE_DEBOUNCE_MILLIS) {
        persistSettings(reason = "settings-panel-auto-save")
    }.apply {
        isRepeats = false
    }

    private val promptPanel = PromptManagerPanel(project)
    private val mcpPanel = McpPanel(project)
    private val hookPanel = HookPanel(project)

    init {
        Disposer.register(this, promptPanel)
        Disposer.register(this, mcpPanel)
        Disposer.register(this, hookPanel)

        withUiSync {
            buildUi()
            loadFromSettings()
        }
        installAutoSaveBindings()
        sectionList.selectedIndex = 0
    }

    private fun buildUi() {
        configureComboRenderers()
        refreshProviderComboPreservingSelection(settings.defaultProvider)
        defaultProviderCombo.addActionListener {
            refreshModelCombo()
            scheduleAutoSave()
        }

        claudeCodeCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.claudePath.placeholder")
        codexCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.codexPath.placeholder")

        detectCliButton.addActionListener { detectCliTools() }
        skillRefreshButton.addActionListener { refreshSkillDiscovery(forceReload = true) }
        skillGenerateButton.addActionListener { generateSkillWithAi() }
        skillTargetScopeCombo.renderer = SimpleListCellRenderer.create<SkillScope> { label, value, _ ->
            label.text = when (value ?: SkillScope.PROJECT) {
                SkillScope.GLOBAL -> SpecCodingBundle.message("settings.skills.scope.global")
                SkillScope.PROJECT -> SpecCodingBundle.message("settings.skills.scope.project")
            }
        }
        skillGenerateRequirementField.emptyText.text = SpecCodingBundle.message("settings.skills.generate.placeholder")

        styleActionButton(detectCliButton)
        styleActionButton(skillRefreshButton)
        styleActionButton(skillGenerateButton, emphasized = true)

        buildSectionCards()

        val sidebarCard = createSidebarCard()
        val contentCard = createContentCard()

        val centerPanel = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10)
            add(sidebarCard, BorderLayout.WEST)
            add(contentCard, BorderLayout.CENTER)
        }

        add(centerPanel, BorderLayout.CENTER)
        updateCliStatusLabels()
        refreshSkillDiscovery(forceReload = true)
    }

    private fun configureComboRenderers() {
        defaultProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = when (value) {
                ClaudeCliLlmProvider.ID -> lowerUiText(SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli"))
                CodexCliLlmProvider.ID -> lowerUiText(SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli"))
                else -> lowerUiText(value ?: "")
            }
        }

        defaultModelCombo.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = lowerUiText(value?.name ?: "")
        }

        interfaceLanguageCombo.renderer = SimpleListCellRenderer.create<InterfaceLanguage> { label, value, _ ->
            label.text = lowerUiText(value?.let { SpecCodingBundle.messageOrDefault(it.labelKey, it.code) } ?: "")
        }
    }

    private fun buildSectionCards() {
        SidebarSection.entries.forEach { sectionListModel.addElement(it) }

        sectionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sectionList.visibleRowCount = SidebarSection.entries.size
        sectionList.fixedCellHeight = JBUI.scale(44)
        sectionList.cellRenderer = SidebarSectionRenderer()
        sectionList.border = JBUI.Borders.empty(8, 8, 8, 8)
        sectionList.background = SIDEBAR_SURFACE_BG
        sectionList.foreground = SIDEBAR_ITEM_FG
        sectionList.selectionBackground = SIDEBAR_SELECTION_BG
        sectionList.selectionForeground = SIDEBAR_SELECTION_FG
        sectionList.putClientProperty("List.paintFocusBorder", false)
        sectionList.putClientProperty("JList.isFileList", false)
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

        val navSurface = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = SIDEBAR_SURFACE_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SIDEBAR_SURFACE_BORDER,
                arc = JBUI.scale(12),
                top = 2,
                left = 2,
                bottom = 2,
                right = 2,
            )
            add(scrollPane, BorderLayout.CENTER)
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
            preferredSize = JBUI.size(148, 0)
            add(navSurface, BorderLayout.CENTER)
        }
    }

    private fun createContentCard(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CONTENT_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = CONTENT_BORDER,
                arc = JBUI.scale(14),
                top = 12,
                left = 12,
                bottom = 12,
                right = 12,
            )
            add(sectionCardPanel, BorderLayout.CENTER)
        }
    }

    private fun createBasicSection(): JPanel {
        val cliDetectPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(detectCliButton)
        }

        val cliToolsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.claudePath"), claudeCodeCliPathField)
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.codexPath"), codexCliPathField)
            .addLabeledComponent(" ", cliDetectPanel)
            .addLabeledComponent("claude cli:", claudeCliStatusLabel)
            .addLabeledComponent("codex cli:", codexCliStatusLabel)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val generalPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultProvider"), defaultProviderCombo)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultModel"), defaultModelCombo)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.interfaceLanguage"), interfaceLanguageCombo)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val teamSyncPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.promptRepoUrl"), teamPromptRepoUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.promptBranch"), teamPromptRepoBranchField)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val proxyPanel = FormBuilder.createFormBuilder()
            .addComponent(useProxyCheckBox.apply { text = SpecCodingBundle.message("settings.proxy.use") })
            .addLabeledComponent(SpecCodingBundle.message("settings.proxy.host"), proxyHostField)
            .addLabeledComponent(SpecCodingBundle.message("settings.proxy.port"), proxyPortField)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val otherPanel = FormBuilder.createFormBuilder()
            .addComponent(autoSaveCheckBox.apply { text = SpecCodingBundle.message("settings.other.autoSave") })
            .addLabeledComponent(SpecCodingBundle.message("settings.other.maxHistorySize"), maxHistorySizeField)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val operationModePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(SpecCodingBundle.message("settings.operationMode.defaultMode"), defaultModeField)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val stackPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(createBasicModuleCard("settings.section.cliTools", cliToolsPanel))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createBasicModuleCard("settings.section.general", generalPanel))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createBasicModuleCard("settings.section.teamSync", teamSyncPanel))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createBasicModuleCard("settings.section.proxy", proxyPanel))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createBasicModuleCard("settings.section.other", otherPanel))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createBasicModuleCard("settings.section.operationMode", operationModePanel))
            add(Box.createVerticalGlue())
        }

        val scrollPane = JBScrollPane(stackPanel).apply {
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
        skillDiscoveryStatusLabel.font = JBUI.Fonts.smallFont()
        skillGenerationStatusLabel.font = JBUI.Fonts.smallFont()
        setSkillDiscoveryStatus(SpecCodingBundle.message("settings.skills.discovery.empty"), SkillStatusTone.NORMAL)
        setSkillGenerationStatus(SpecCodingBundle.message("toolwindow.status.ready"), SkillStatusTone.NORMAL)

        skillRootsList.isFocusable = false
        skillRootsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        skillRootsList.visibleRowCount = 4
        skillRootsList.background = CONTENT_BG

        skillsList.isFocusable = false
        skillsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        skillsList.visibleRowCount = 8
        skillsList.background = CONTENT_BG

        val rootsScrollPane = JBScrollPane(skillRootsList).apply {
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SKILL_STATUS_BORDER,
                arc = JBUI.scale(10),
                top = 4,
                left = 6,
                bottom = 4,
                right = 6,
            )
            preferredSize = JBUI.size(0, JBUI.scale(92))
        }
        val skillsScrollPane = JBScrollPane(skillsList).apply {
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SKILL_STATUS_BORDER,
                arc = JBUI.scale(10),
                top = 4,
                left = 6,
                bottom = 4,
                right = 6,
            )
            preferredSize = JBUI.size(0, JBUI.scale(180))
        }

        val discoveryHeader = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(skillDiscoveryStatusLabel, BorderLayout.CENTER)
            add(skillRefreshButton, BorderLayout.EAST)
        }
        val discoveryPanel = FormBuilder.createFormBuilder()
            .addComponent(discoveryHeader)
            .addVerticalGap(8)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.discovery.roots"), rootsScrollPane)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.discovery.skills"), skillsScrollPane)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val generateActionRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(skillGenerateButton)
        }
        val generationPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.generate.scope"), skillTargetScopeCombo)
            .addLabeledComponent(
                SpecCodingBundle.message("settings.skills.generate.requirement"),
                skillGenerateRequirementField,
            )
            .addVerticalGap(8)
            .addComponent(generateActionRow)
            .addVerticalGap(4)
            .addComponent(skillGenerationStatusLabel)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val stackPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(createBasicModuleCard("settings.skills.discovery.title", discoveryPanel))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createBasicModuleCard("settings.skills.generate.title", generationPanel))
            add(Box.createVerticalGlue())
        }
        val scrollPane = JBScrollPane(stackPanel).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun createEmbeddedSection(component: JPanel): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2)
            add(component, BorderLayout.CENTER)
        }
    }

    private fun createBasicModuleCard(
        titleKey: String,
        content: JPanel,
    ): JPanel {
        val tone = moduleTone(titleKey)
        val titleLabel = JBLabel(SpecCodingBundle.message(titleKey)).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = tone.titleColor
            border = JBUI.Borders.empty(0, 2, 0, 2)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = tone.headerBg
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, tone.headerBorder)
            add(titleLabel, BorderLayout.WEST)
        }
        val contentHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(content, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = tone.bodyBg
            border = SpecUiStyle.roundedCardBorder(
                lineColor = tone.borderColor,
                arc = JBUI.scale(12),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(header, BorderLayout.NORTH)
            add(contentHost, BorderLayout.CENTER)
        }
    }

    private fun showSelectedSection() {
        val selected = sectionList.selectedValue ?: SidebarSection.BASIC
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

    private fun installAutoSaveBindings() {
        defaultModelCombo.addActionListener { scheduleAutoSave() }
        interfaceLanguageCombo.addActionListener { scheduleAutoSave() }
        skillTargetScopeCombo.addActionListener { scheduleAutoSave() }
        useProxyCheckBox.addActionListener { scheduleAutoSave() }
        autoSaveCheckBox.addActionListener { scheduleAutoSave() }

        listOf(
            proxyHostField,
            proxyPortField,
            maxHistorySizeField,
            codexCliPathField,
            claudeCodeCliPathField,
            defaultModeField,
            teamPromptRepoUrlField,
            teamPromptRepoBranchField,
        ).forEach { field ->
            field.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = scheduleAutoSave()
                    override fun removeUpdate(e: DocumentEvent?) = scheduleAutoSave()
                    override fun changedUpdate(e: DocumentEvent?) = scheduleAutoSave()
                },
            )
        }
    }

    private fun scheduleAutoSave() {
        if (syncingUi || isDisposed || project.isDisposed) {
            return
        }
        autoSaveTimer.restart()
    }

    private inline fun withUiSync(block: () -> Unit) {
        val previous = syncingUi
        syncingUi = true
        try {
            block()
        } finally {
            syncingUi = previous
        }
    }

    private fun refreshProviderComboPreservingSelection(preferredProvider: String?) {
        withUiSync {
            val router = LlmRouter.getInstance()
            val providers = router.availableUiProviders()
            defaultProviderCombo.removeAllItems()
            providers.forEach { defaultProviderCombo.addItem(it) }
            val selected = preferredProvider?.takeIf { providers.contains(it) } ?: providers.firstOrNull()
            if (selected != null) {
                defaultProviderCombo.selectedItem = selected
            }
        }
    }

    private fun loadFromSettings() {
        refreshProviderComboPreservingSelection(settings.defaultProvider)
        refreshModelCombo()
        interfaceLanguageCombo.selectedItem = InterfaceLanguage.fromCode(settings.interfaceLanguage)
        teamPromptRepoUrlField.text = settings.teamPromptRepoUrl
        teamPromptRepoBranchField.text = settings.teamPromptRepoBranch
        skillTargetScopeCombo.selectedItem = runCatching {
            SkillScope.valueOf(settings.skillGenerationScope.uppercase(Locale.ROOT))
        }.getOrDefault(SkillScope.PROJECT)
        useProxyCheckBox.isSelected = settings.useProxy
        proxyHostField.text = settings.proxyHost
        proxyPortField.text = settings.proxyPort.toString()
        autoSaveCheckBox.isSelected = settings.autoSaveConversation
        maxHistorySizeField.text = settings.maxHistorySize.toString()
        codexCliPathField.text = settings.codexCliPath
        claudeCodeCliPathField.text = settings.claudeCodeCliPath
        defaultModeField.text = settings.defaultOperationMode.lowercase(Locale.ROOT)
    }

    private fun persistSettings(reason: String) {
        val nextDefaultProvider = (defaultProviderCombo.selectedItem as? String) ?: settings.defaultProvider
        val nextSelectedModel = (defaultModelCombo.selectedItem as? ModelInfo)?.id ?: settings.selectedCliModel
        val selectedLanguage = (interfaceLanguageCombo.selectedItem as? InterfaceLanguage) ?: InterfaceLanguage.AUTO
        val nextPromptRepoUrl = teamPromptRepoUrlField.text.trim()
        val nextPromptBranch = teamPromptRepoBranchField.text.trim().ifBlank { "main" }
        val nextSkillGenerationScope = (skillTargetScopeCombo.selectedItem as? SkillScope ?: SkillScope.PROJECT).name
        val nextUseProxy = useProxyCheckBox.isSelected
        val nextProxyHost = proxyHostField.text
        val nextProxyPort = proxyPortField.text.toIntOrNull() ?: settings.proxyPort
        val nextAutoSave = autoSaveCheckBox.isSelected
        val nextMaxHistory = maxHistorySizeField.text.toIntOrNull() ?: settings.maxHistorySize
        val nextCodexPath = codexCliPathField.text
        val nextClaudePath = claudeCodeCliPathField.text
        val nextDefaultMode = normalizeOperationMode(defaultModeField.text)

        val hasSettingsChange =
            nextDefaultProvider != settings.defaultProvider ||
                nextSelectedModel != settings.selectedCliModel ||
                selectedLanguage.code != settings.interfaceLanguage ||
                nextPromptRepoUrl != settings.teamPromptRepoUrl ||
                nextPromptBranch != settings.teamPromptRepoBranch ||
                nextSkillGenerationScope != settings.skillGenerationScope ||
                nextUseProxy != settings.useProxy ||
                nextProxyHost != settings.proxyHost ||
                nextProxyPort != settings.proxyPort ||
                nextAutoSave != settings.autoSaveConversation ||
                nextMaxHistory != settings.maxHistorySize ||
                nextCodexPath != settings.codexCliPath ||
                nextClaudePath != settings.claudeCodeCliPath ||
                nextDefaultMode != settings.defaultOperationMode

        if (!hasSettingsChange) {
            return
        }

        settings.defaultProvider = nextDefaultProvider
        settings.selectedCliModel = nextSelectedModel
        settings.teamPromptRepoUrl = nextPromptRepoUrl
        settings.teamPromptRepoBranch = nextPromptBranch
        settings.skillGenerationScope = nextSkillGenerationScope
        settings.useProxy = nextUseProxy
        settings.proxyHost = nextProxyHost
        settings.proxyPort = nextProxyPort
        settings.autoSaveConversation = nextAutoSave
        settings.maxHistorySize = nextMaxHistory
        settings.codexCliPath = nextCodexPath
        settings.claudeCodeCliPath = nextClaudePath
        settings.defaultOperationMode = nextDefaultMode

        val localeChanged = LocaleManager.getInstance().setLanguage(selectedLanguage, reason = reason)
        GlobalConfigSyncService.getInstance().notifyGlobalConfigChanged(
            sourceProject = null,
            reason = if (localeChanged != null) "$reason-with-locale" else reason,
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
            val version = lowerUiText(claudeInfo.version ?: "unknown")
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.found", version)
            claudeCliStatusLabel.foreground = STATUS_SUCCESS_FG
        } else {
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.notFound")
            claudeCliStatusLabel.foreground = STATUS_NORMAL_FG
        }
        val codexInfo = discoveryService.codexInfo
        if (codexInfo.available) {
            val version = lowerUiText(codexInfo.version ?: "unknown")
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.found", version)
            codexCliStatusLabel.foreground = STATUS_SUCCESS_FG
        } else {
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.notFound")
            codexCliStatusLabel.foreground = STATUS_NORMAL_FG
        }
    }

    private fun refreshModelCombo() {
        val selectedProvider = defaultProviderCombo.selectedItem as? String ?: return
        withUiSync {
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
    }

    private fun refreshSkillDiscovery(forceReload: Boolean) {
        skillRefreshButton.isEnabled = false
        setSkillDiscoveryStatus(
            SpecCodingBundle.message("settings.skills.discovery.scanning"),
            SkillStatusTone.NORMAL,
        )
        scope.launch {
            val result = runCatching {
                skillRegistry.discoverySnapshot(forceReload = forceReload)
            }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                skillRefreshButton.isEnabled = true
                result
                    .onSuccess(::applySkillDiscoverySnapshot)
                    .onFailure { error ->
                        setSkillDiscoveryStatus(
                            SpecCodingBundle.message(
                                "settings.skills.discovery.failed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillStatusTone.ERROR,
                        )
                    }
            }
        }
    }

    private fun applySkillDiscoverySnapshot(snapshot: SkillDiscoverySnapshot) {
        skillRootsListModel.clear()
        snapshot.roots.forEach { root ->
            val scopeText = when (root.scope) {
                SkillScope.GLOBAL -> SpecCodingBundle.message("settings.skills.scope.global")
                SkillScope.PROJECT -> SpecCodingBundle.message("settings.skills.scope.project")
            }
            val existsText = if (root.exists) {
                SpecCodingBundle.message("settings.skills.discovery.root.exists")
            } else {
                SpecCodingBundle.message("settings.skills.discovery.root.missing")
            }
            skillRootsListModel.addElement(
                SpecCodingBundle.message(
                    "settings.skills.discovery.root.item",
                    scopeText,
                    root.label,
                    existsText,
                    root.path,
                ),
            )
        }

        skillsListModel.clear()
        snapshot.skills.forEach { skill ->
            skillsListModel.addElement(formatSkillListEntry(skill))
        }

        setSkillDiscoveryStatus(
            SpecCodingBundle.message(
                "settings.skills.discovery.summary",
                snapshot.skills.size,
                snapshot.roots.count { it.exists },
                snapshot.roots.size,
            ),
            SkillStatusTone.SUCCESS,
        )
    }

    private fun formatSkillListEntry(skill: Skill): String {
        val sourceText = when (skill.sourceType) {
            SkillSourceType.BUILTIN -> SpecCodingBundle.message("settings.skills.source.builtin")
            SkillSourceType.YAML -> SpecCodingBundle.message("settings.skills.source.yaml")
            SkillSourceType.MARKDOWN -> SpecCodingBundle.message("settings.skills.source.markdown")
        }
        val scopeText = skill.scope?.let {
            when (it) {
                SkillScope.GLOBAL -> SpecCodingBundle.message("settings.skills.scope.global")
                SkillScope.PROJECT -> SpecCodingBundle.message("settings.skills.scope.project")
            }
        } ?: SpecCodingBundle.message("settings.skills.scope.none")
        return SpecCodingBundle.message(
            "settings.skills.discovery.skill.item",
            skill.slashCommand,
            sourceText,
            scopeText,
            skill.description,
        )
    }

    private fun generateSkillWithAi() {
        val requirement = skillGenerateRequirementField.text.trim()
        if (requirement.isBlank()) {
            setSkillGenerationStatus(
                SpecCodingBundle.message("settings.skills.generate.requirement.empty"),
                SkillStatusTone.ERROR,
            )
            return
        }

        val targetScope = (skillTargetScopeCombo.selectedItem as? SkillScope) ?: SkillScope.PROJECT
        persistSettings(reason = "settings-panel-skill-generate")

        skillGenerateButton.isEnabled = false
        setSkillGenerationStatus(
            SpecCodingBundle.message("settings.skills.generate.running"),
            SkillStatusTone.NORMAL,
        )

        scope.launch {
            val result = runCatching {
                val draft = generateSkillDraft(requirement)
                writeGeneratedSkill(targetScope, draft)
            }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                skillGenerateButton.isEnabled = true
                result
                    .onSuccess { path ->
                        setSkillGenerationStatus(
                            SpecCodingBundle.message(
                                "settings.skills.generate.success",
                                path.parent?.fileName?.toString().orEmpty(),
                                path.toString(),
                            ),
                            SkillStatusTone.SUCCESS,
                        )
                        skillGenerateRequirementField.text = ""
                        refreshSkillDiscovery(forceReload = true)
                    }
                    .onFailure { error ->
                        setSkillGenerationStatus(
                            SpecCodingBundle.message(
                                "settings.skills.generate.failed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillStatusTone.ERROR,
                        )
                    }
            }
        }
    }

    private suspend fun generateSkillDraft(requirement: String): GeneratedSkillDraft {
        val providerId = settings.defaultProvider.ifBlank { null }
        val modelId = settings.selectedCliModel.ifBlank { null }
        val prompt = buildString {
            appendLine("You are generating a local SKILL.md for an IntelliJ plugin user.")
            appendLine("Return ONLY strict JSON, no markdown fences, no explanation.")
            appendLine("Required JSON keys: id, name, description, slash_command, body.")
            appendLine("Rules:")
            appendLine("1) id: lowercase, letters/numbers/hyphen/underscore only.")
            appendLine("2) slash_command: lowercase command token without leading slash.")
            appendLine("3) body: markdown instructions for agent execution.")
            appendLine("4) Keep body concise but actionable.")
            appendLine()
            appendLine("User requirement:")
            appendLine(requirement)
        }

        val responseText = StringBuilder()
        projectService.chat(
            providerId = providerId,
            modelId = modelId,
            userInput = prompt,
            planExecuteVerifySections = false,
        ) { chunk ->
            if (chunk.delta.isNotBlank()) {
                responseText.append(chunk.delta)
            }
        }

        val root = parseSkillJson(responseText.toString())
        val rawName = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val rawDescription = root["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val rawCommand = root["slash_command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val normalizedCommand = normalizeSkillToken(rawCommand.removePrefix("/"), fallback = "custom-skill")
        val normalizedId = normalizeSkillToken(
            value = root["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            fallback = normalizedCommand,
        )
        val body = root["body"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
            ?: buildString {
                appendLine("## Objective")
                appendLine("- $requirement")
                appendLine()
                appendLine("## Workflow")
                appendLine("1. Clarify constraints from current context.")
                appendLine("2. Implement minimal viable change.")
                appendLine("3. Add or update tests.")
                appendLine("4. Verify and summarize tradeoffs.")
            }
        return GeneratedSkillDraft(
            id = normalizedId,
            name = rawName.ifBlank { normalizedId },
            description = rawDescription.ifBlank { requirement },
            slashCommand = normalizedCommand,
            body = body,
        )
    }

    private fun parseSkillJson(raw: String): JsonObject {
        val trimmed = raw.trim()
        val direct = runCatching { skillJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        if (direct != null) {
            return direct
        }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = trimmed.substring(firstBrace, lastBrace + 1)
            val extracted = runCatching { skillJson.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            if (extracted != null) {
                return extracted
            }
        }
        throw IllegalArgumentException(SpecCodingBundle.message("settings.skills.generate.invalidJson"))
    }

    private fun writeGeneratedSkill(scope: SkillScope, draft: GeneratedSkillDraft): Path {
        val root = resolveSkillRoot(scope)
        Files.createDirectories(root)

        val skillDir = findAvailableSkillDirectory(root, draft.id)
        Files.createDirectories(skillDir)
        val filePath = skillDir.resolve(SKILL_MARKDOWN_FILE_NAME)
        val content = buildGeneratedSkillMarkdown(draft)
        Files.writeString(filePath, content, StandardCharsets.UTF_8)
        return filePath
    }

    private fun resolveSkillRoot(scope: SkillScope): Path {
        return when (scope) {
            SkillScope.GLOBAL -> {
                val home = System.getProperty("user.home")
                    ?.trim()
                    ?.ifBlank { null }
                    ?: throw IllegalStateException(SpecCodingBundle.message("settings.skills.scope.global.unavailable"))
                Paths.get(home).resolve(".codex").resolve("skills")
            }

            SkillScope.PROJECT -> {
                val basePath = project.basePath
                    ?.trim()
                    ?.ifBlank { null }
                    ?: throw IllegalStateException(SpecCodingBundle.message("settings.skills.scope.project.unavailable"))
                Paths.get(basePath).resolve(".codex").resolve("skills")
            }
        }
    }

    private fun findAvailableSkillDirectory(root: Path, preferredId: String): Path {
        var candidate = root.resolve(preferredId)
        if (!Files.exists(candidate)) {
            return candidate
        }
        var suffix = 2
        while (true) {
            candidate = root.resolve("$preferredId-$suffix")
            if (!Files.exists(candidate)) {
                return candidate
            }
            suffix += 1
        }
    }

    private fun buildGeneratedSkillMarkdown(draft: GeneratedSkillDraft): String {
        val body = draft.body.trim().ifBlank {
            SpecCodingBundle.message("settings.skills.generate.body.fallback")
        }
        return buildString {
            appendLine("---")
            appendLine("name: ${draft.name}")
            appendLine("description: ${draft.description}")
            appendLine("slash_command: ${draft.slashCommand}")
            appendLine("---")
            appendLine()
            appendLine(body)
        }
    }

    private fun normalizeSkillToken(value: String, fallback: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(SKILL_TOKEN_INVALID_REGEX, "-")
            .trim('-')
            .ifBlank { fallback }
    }

    private fun setSkillDiscoveryStatus(text: String, tone: SkillStatusTone) {
        skillDiscoveryStatusLabel.text = text
        skillDiscoveryStatusLabel.foreground = when (tone) {
            SkillStatusTone.NORMAL -> STATUS_NORMAL_FG
            SkillStatusTone.SUCCESS -> STATUS_SUCCESS_FG
            SkillStatusTone.ERROR -> STATUS_ERROR_FG
        }
    }

    private fun setSkillGenerationStatus(text: String, tone: SkillStatusTone) {
        skillGenerationStatusLabel.text = text
        skillGenerationStatusLabel.foreground = when (tone) {
            SkillStatusTone.NORMAL -> STATUS_NORMAL_FG
            SkillStatusTone.SUCCESS -> STATUS_SUCCESS_FG
            SkillStatusTone.ERROR -> STATUS_ERROR_FG
        }
    }

    override fun dispose() {
        isDisposed = true
        if (autoSaveTimer.isRunning) {
            autoSaveTimer.stop()
            persistSettings(reason = "settings-panel-dispose-auto-save")
        }
        scope.cancel()
    }

    private enum class SidebarSection(
        val cardId: String,
        val titleKey: String,
        val icon: Icon,
    ) {
        BASIC("basic", "settings.sidebar.basic", AllIcons.General.GearPlain),
        PROMPTS("prompts", "settings.sidebar.prompts", AllIcons.FileTypes.Text),
        MCP("mcp", "settings.sidebar.mcp", SIDEBAR_MCP_ICON),
        SKILLS("skills", "settings.sidebar.skills", AllIcons.Actions.EditSource),
        HOOKS("hooks", "settings.sidebar.hooks", SIDEBAR_HOOKS_ICON),
    }

    private data class GeneratedSkillDraft(
        val id: String,
        val name: String,
        val description: String,
        val slashCommand: String,
        val body: String,
    )

    private enum class SkillStatusTone {
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
            label.icon = section.icon
            label.iconTextGap = JBUI.scale(7)
            label.border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(2, 4, 2, 4),
                BorderFactory.createCompoundBorder(
                    SidebarItemBorder(isSelected),
                    JBUI.Borders.empty(7, 8, 7, 10),
                ),
            )
            label.background = if (isSelected) SIDEBAR_ITEM_SELECTED_BG else SIDEBAR_ITEM_BG
            label.foreground = if (isSelected) SIDEBAR_ITEM_SELECTED_FG else SIDEBAR_ITEM_FG
            label.font = label.font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 12f)
            label.isOpaque = true
            return label
        }
    }

    private class SidebarItemBorder(
        private val selected: Boolean,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val baseGraphics = g as? Graphics2D ?: return
            val g2 = baseGraphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val leftInset = JBUI.scale(8).toFloat()
                val arc = JBUI.scale(12).toFloat()
                val outline = RoundRectangle2D.Float(
                    x + leftInset,
                    y + 0.5f,
                    width - leftInset - 1f,
                    height - 1f,
                    arc,
                    arc,
                )
                g2.color = if (selected) SIDEBAR_ITEM_SELECTED_BORDER else SIDEBAR_ITEM_BORDER
                g2.draw(outline)

                if (selected) {
                    val barWidth = JBUI.scale(3).toFloat()
                    val barHeight = (height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(14)).toFloat()
                    val barArc = JBUI.scale(4).toFloat()
                    val barX = x + JBUI.scale(2).toFloat()
                    val barY = y + (height - barHeight) / 2f
                    g2.color = SIDEBAR_ITEM_ACCENT
                    g2.fill(
                        RoundRectangle2D.Float(
                            barX,
                            barY,
                            barWidth,
                            barHeight,
                            barArc,
                            barArc,
                        ),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: Component?): Insets = Insets(
            JBUI.scale(2),
            JBUI.scale(8),
            JBUI.scale(2),
            JBUI.scale(2),
        )

        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
            insets.set(
                JBUI.scale(2),
                JBUI.scale(8),
                JBUI.scale(2),
                JBUI.scale(2),
            )
            return insets
        }
    }

    private data class ModuleTone(
        val bodyBg: Color,
        val borderColor: Color,
        val headerBg: Color,
        val headerBorder: Color,
        val titleColor: Color,
    )

    private fun moduleTone(titleKey: String): ModuleTone {
        return when (titleKey) {
            "settings.section.cliTools" -> ModuleTone(
                bodyBg = MODULE_CARD_BG_PRIMARY,
                borderColor = MODULE_CARD_BORDER_PRIMARY,
                headerBg = MODULE_HEADER_BG_PRIMARY,
                headerBorder = MODULE_HEADER_BORDER_PRIMARY,
                titleColor = MODULE_TITLE_FG_PRIMARY,
            )
            "settings.section.proxy" -> ModuleTone(
                bodyBg = MODULE_CARD_BG_MUTED,
                borderColor = MODULE_CARD_BORDER_MUTED,
                headerBg = MODULE_HEADER_BG_MUTED,
                headerBorder = MODULE_HEADER_BORDER_MUTED,
                titleColor = MODULE_TITLE_FG_MUTED,
            )
            else -> ModuleTone(
                bodyBg = MODULE_CARD_BG,
                borderColor = MODULE_CARD_BORDER,
                headerBg = MODULE_HEADER_BG,
                headerBorder = MODULE_HEADER_BORDER,
                titleColor = MODULE_TITLE_FG,
            )
        }
    }

    private fun lowerUiText(text: String): String = text.lowercase(Locale.ROOT)

    private fun normalizeOperationMode(input: String): String {
        return input
            .trim()
            .ifBlank { "default" }
            .uppercase(Locale.ROOT)
    }

    companion object {
        private val SIDEBAR_BG = JBColor(Color(242, 248, 255), Color(51, 58, 68))
        private val SIDEBAR_BORDER = JBColor(Color(196, 212, 236), Color(82, 93, 109))
        private val SIDEBAR_SURFACE_BG = JBColor(Color(248, 251, 255), Color(58, 65, 76))
        private val SIDEBAR_SURFACE_BORDER = JBColor(Color(208, 221, 241), Color(88, 99, 116))
        private val SIDEBAR_SELECTION_BG = JBColor(Color(221, 235, 255), Color(76, 92, 117))
        private val SIDEBAR_SELECTION_FG = JBColor(Color(45, 69, 107), Color(217, 228, 245))
        private val SIDEBAR_ITEM_BG = JBColor(Color(248, 251, 255), Color(58, 65, 76))
        private val SIDEBAR_ITEM_BORDER = JBColor(Color(224, 233, 247), Color(92, 103, 121))
        private val SIDEBAR_ITEM_FG = JBColor(Color(68, 87, 118), Color(194, 206, 224))
        private val SIDEBAR_ITEM_SELECTED_BG = JBColor(Color(228, 239, 255), Color(79, 95, 119))
        private val SIDEBAR_ITEM_SELECTED_BORDER = JBColor(Color(177, 198, 230), Color(112, 130, 158))
        private val SIDEBAR_ITEM_SELECTED_FG = JBColor(Color(37, 60, 99), Color(228, 237, 248))
        private val SIDEBAR_ITEM_ACCENT = JBColor(Color(97, 134, 197), Color(154, 178, 216))
        private val CONTENT_BG = JBColor(Color(250, 252, 255), Color(50, 56, 64))
        private val CONTENT_BORDER = JBColor(Color(204, 216, 234), Color(83, 91, 103))
        private val MODULE_CARD_BG = JBColor(Color(245, 250, 255), Color(58, 65, 76))
        private val MODULE_CARD_BORDER = JBColor(Color(205, 219, 239), Color(90, 101, 118))
        private val MODULE_TITLE_FG = JBColor(Color(49, 72, 108), Color(211, 222, 241))
        private val MODULE_HEADER_BG = JBColor(Color(237, 245, 255), Color(66, 74, 87))
        private val MODULE_HEADER_BORDER = JBColor(Color(199, 214, 236), Color(96, 108, 126))
        private val MODULE_CARD_BG_PRIMARY = JBColor(Color(241, 248, 255), Color(56, 64, 75))
        private val MODULE_CARD_BORDER_PRIMARY = JBColor(Color(192, 210, 235), Color(90, 103, 121))
        private val MODULE_HEADER_BG_PRIMARY = JBColor(Color(227, 239, 255), Color(70, 83, 100))
        private val MODULE_HEADER_BORDER_PRIMARY = JBColor(Color(177, 198, 229), Color(101, 116, 136))
        private val MODULE_TITLE_FG_PRIMARY = JBColor(Color(35, 61, 100), Color(220, 230, 246))
        private val MODULE_CARD_BG_MUTED = JBColor(Color(247, 251, 255), Color(60, 67, 78))
        private val MODULE_CARD_BORDER_MUTED = JBColor(Color(208, 221, 239), Color(92, 104, 121))
        private val MODULE_HEADER_BG_MUTED = JBColor(Color(241, 247, 255), Color(68, 76, 89))
        private val MODULE_HEADER_BORDER_MUTED = JBColor(Color(203, 217, 236), Color(98, 109, 127))
        private val MODULE_TITLE_FG_MUTED = JBColor(Color(56, 78, 112), Color(205, 218, 236))
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
        private val SIDEBAR_MCP_ICON = IconLoader.getIcon("/icons/settings-mcp.svg", SettingsPanel::class.java)
        private val SIDEBAR_HOOKS_ICON = IconLoader.getIcon("/icons/settings-hooks.svg", SettingsPanel::class.java)
        private const val SKILL_MARKDOWN_FILE_NAME = "SKILL.md"
        private val SKILL_TOKEN_INVALID_REGEX = Regex("[^a-z0-9_-]+")
        private const val AUTO_SAVE_DEBOUNCE_MILLIS = 650
    }
}
