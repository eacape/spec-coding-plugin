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
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingConstants
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
    private val skillNewDraftButton = JButton(SpecCodingBundle.message("settings.skills.editor.new"))
    private val skillAiDraftButton = JButton(SpecCodingBundle.message("settings.skills.generate.action"))
    private val skillDeleteButton = JButton(SpecCodingBundle.message("settings.skills.editor.delete"))
    private val skillSaveButton = JButton(SpecCodingBundle.message("settings.skills.editor.save"))
    private val skillSaveCurrentButton = JButton(SpecCodingBundle.message("settings.skills.editor.saveCurrent"))
    private val skillTargetScopeCombo = ComboBox(SkillScope.entries.toTypedArray())
    private val skillTargetChannelCombo = ComboBox(SkillSaveChannel.entries.toTypedArray())
    private val skillDraftProviderCombo = ComboBox<String>()
    private val skillDraftModelCombo = ComboBox<ModelInfo>()
    private val skillRequirementField = JBTextField()
    private val skillIdField = JBTextField()
    private val skillNameField = JBTextField()
    private val skillCommandField = JBTextField()
    private val skillDescriptionField = JBTextField()
    private val skillMarkdownArea = JBTextArea()
    private val skillDiscoveryStatusLabel = JBLabel(SpecCodingBundle.message("settings.skills.discovery.empty"))
    private val skillEditorStatusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val skillsListModel = DefaultListModel<Skill>()
    private val skillsList = JBList(skillsListModel)
    private var hoveredSkillIndex = -1
    private var activeSkillSourcePath: String? = null
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
        refreshSkillDraftProviderComboPreservingSelection(resolveSkillDraftPreferredProvider())
        refreshSkillDraftModelCombo()
        defaultProviderCombo.addActionListener {
            refreshModelCombo()
            scheduleAutoSave()
        }
        skillDraftProviderCombo.addActionListener {
            refreshSkillDraftModelCombo()
            scheduleAutoSave()
        }

        claudeCodeCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.claudePath.placeholder")
        codexCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.codexPath.placeholder")

        detectCliButton.addActionListener { detectCliTools() }
        skillRefreshButton.addActionListener { refreshSkillDiscovery(forceReload = true) }
        skillNewDraftButton.addActionListener { resetSkillEditorForNewDraft() }
        skillAiDraftButton.addActionListener { generateSkillDraftWithAi() }
        skillDeleteButton.addActionListener { deleteSkillFromCurrentSource() }
        skillSaveButton.addActionListener { saveSkillToSelectedTargets() }
        skillSaveCurrentButton.addActionListener { saveSkillToCurrentSource() }
        skillTargetScopeCombo.renderer = SimpleListCellRenderer.create<SkillScope> { label, value, _ ->
            label.text = when (value ?: SkillScope.PROJECT) {
                SkillScope.GLOBAL -> SpecCodingBundle.message("settings.skills.scope.global")
                SkillScope.PROJECT -> SpecCodingBundle.message("settings.skills.scope.project")
            }
        }
        skillTargetChannelCombo.renderer = SimpleListCellRenderer.create<SkillSaveChannel> { label, value, _ ->
            label.text = when (value ?: SkillSaveChannel.ALL) {
                SkillSaveChannel.CODEX -> SpecCodingBundle.message("settings.skills.channel.codex")
                SkillSaveChannel.CLUADE -> SpecCodingBundle.message("settings.skills.channel.cluade")
                SkillSaveChannel.ALL -> SpecCodingBundle.message("settings.skills.channel.all")
            }
        }
        skillDraftProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayText(value)
        }
        skillDraftModelCombo.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = lowerUiText(value?.name ?: "")
        }
        skillRequirementField.emptyText.text = SpecCodingBundle.message("settings.skills.generate.placeholder")
        skillMarkdownArea.toolTipText = SpecCodingBundle.message("settings.skills.editor.markdown.placeholder")

        styleActionButton(detectCliButton)
        styleActionButton(skillRefreshButton)
        styleActionButton(skillNewDraftButton)
        styleActionButton(skillAiDraftButton)
        styleActionButton(skillDeleteButton)
        styleActionButton(skillSaveButton, emphasized = true)
        styleActionButton(skillSaveCurrentButton)
        updateSkillComboPreferredSizes()

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
        resetSkillEditorForNewDraft()
        refreshSkillDiscovery(forceReload = true)
    }

    private fun configureComboRenderers() {
        defaultProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayText(value)
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
        sectionList.fixedCellHeight = JBUI.scale(SIDEBAR_ITEM_HEIGHT)
        sectionList.cellRenderer = SidebarSectionRenderer()
        sectionList.border = JBUI.Borders.empty(6, 6, 6, 6)
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
                arc = JBUI.scale(10),
                top = 1,
                left = 1,
                bottom = 1,
                right = 1,
            )
            add(scrollPane, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = SIDEBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SIDEBAR_BORDER,
                arc = JBUI.scale(16),
                top = 5,
                left = 5,
                bottom = 5,
                right = 5,
            )
            preferredSize = JBUI.size(SIDEBAR_WIDTH, 0)
            minimumSize = JBUI.size(SIDEBAR_WIDTH, 0)
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
        skillEditorStatusLabel.font = JBUI.Fonts.smallFont()
        skillDiscoveryStatusLabel.border = JBUI.Borders.emptyLeft(2)
        skillEditorStatusLabel.border = JBUI.Borders.emptyLeft(2)
        setSkillDiscoveryStatus(SpecCodingBundle.message("settings.skills.discovery.empty"), SkillStatusTone.NORMAL)
        setSkillEditorStatus(SpecCodingBundle.message("toolwindow.status.ready"), SkillStatusTone.NORMAL)

        skillsList.isFocusable = true
        skillsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        skillsList.visibleRowCount = 12
        skillsList.fixedCellHeight = -1
        skillsList.background = CONTENT_BG
        skillsList.selectionBackground = JBColor(Color(216, 231, 252), Color(79, 96, 121))
        skillsList.selectionForeground = SIDEBAR_ITEM_SELECTED_FG
        skillsList.cellRenderer = SkillItemRenderer()
        skillsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadSelectedSkillForEditing(skillsList.selectedValue)
            }
        }
        skillsList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: java.awt.event.MouseEvent) {
                updateHoveredSkillIndex(event.point)
            }
        })
        skillsList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: java.awt.event.MouseEvent) {
                updateHoveredSkillIndex(null)
            }
        })

        skillMarkdownArea.lineWrap = false
        skillMarkdownArea.wrapStyleWord = false
        skillMarkdownArea.rows = 14
        skillMarkdownArea.font = Font("JetBrains Mono", Font.PLAIN, 13)
        skillMarkdownArea.margin = JBUI.insets(6)
        skillMarkdownArea.tabSize = 2

        val skillsScrollPane = JBScrollPane(skillsList).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(0, JBUI.scale(260))
            viewport.background = CONTENT_BG
        }

        val discoveryActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(skillNewDraftButton)
            add(skillRefreshButton)
        }
        val discoveryStatusPanel = createSkillStatusPanel(skillDiscoveryStatusLabel)
        val discoveryHeader = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(discoveryStatusPanel, BorderLayout.CENTER)
            add(discoveryActions, BorderLayout.EAST)
        }
        val discoveryPanel = FormBuilder.createFormBuilder()
            .addComponent(discoveryHeader)
            .addVerticalGap(8)
            .addComponent(skillsScrollPane)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val targetRow = createSkillComboRow(
            firstCombo = skillTargetScopeCombo,
            secondLabelKey = "settings.skills.generate.channel",
            secondCombo = skillTargetChannelCombo,
        )
        val draftModelRow = createSkillComboRow(
            firstCombo = skillDraftProviderCombo,
            secondLabelKey = "settings.skills.generate.model",
            secondCombo = skillDraftModelCombo,
        )

        val requirementRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(skillRequirementField, BorderLayout.CENTER)
            val actionHost = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(skillAiDraftButton)
            }
            add(actionHost, BorderLayout.EAST)
        }
        val editorMetadataPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.generate.scope"), targetRow)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.generate.provider"), draftModelRow)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.generate.requirement"), requirementRow)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.editor.id"), skillIdField)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.editor.name"), skillNameField)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.editor.command"), skillCommandField)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.editor.description"), skillDescriptionField)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val markdownScrollPane = JBScrollPane(skillMarkdownArea).apply {
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SKILL_STATUS_BORDER,
                arc = JBUI.scale(10),
                top = 4,
                left = 6,
                bottom = 4,
                right = 6,
            )
            preferredSize = JBUI.size(0, JBUI.scale(280))
            viewport.background = CONTENT_BG
        }

        val editorSaveActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(skillDeleteButton)
            add(skillSaveButton)
            add(skillSaveCurrentButton)
        }
        val editorActionRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(editorSaveActions, BorderLayout.EAST)
        }

        val editorStatusPanel = createSkillStatusPanel(skillEditorStatusLabel)
        val editorPanel = FormBuilder.createFormBuilder()
            .addComponent(editorMetadataPanel)
            .addVerticalGap(8)
            .addLabeledComponent(SpecCodingBundle.message("settings.skills.editor.markdown"), markdownScrollPane)
            .addVerticalGap(8)
            .addComponent(editorActionRow)
            .addVerticalGap(4)
            .addComponent(editorStatusPanel)
            .panel.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }

        val discoveryCard = createBasicModuleCard("settings.skills.discovery.title", discoveryPanel).apply {
            minimumSize = JBUI.size(220, 0)
        }
        val editorCard = createBasicModuleCard("settings.skills.editor.title", editorPanel).apply {
            minimumSize = JBUI.size(420, 0)
        }

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            discoveryCard,
            editorCard,
        ).apply {
            resizeWeight = 0.32
            isContinuousLayout = true
            isOneTouchExpandable = false
            dividerSize = JBUI.scale(9)
            border = JBUI.Borders.empty()
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(splitPane, BorderLayout.CENTER)
        }
    }

    private fun createEmbeddedSection(component: JPanel): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2)
            add(component, BorderLayout.CENTER)
        }
    }

    private fun createSkillComboRow(
        firstCombo: ComboBox<*>,
        secondLabelKey: String,
        secondCombo: ComboBox<*>,
    ): JPanel {
        val secondLabel = JBLabel(SpecCodingBundle.message(secondLabelKey)).apply {
            preferredSize = JBUI.size(JBUI.scale(40), JBUI.scale(28))
            minimumSize = preferredSize
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(firstCombo)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(secondLabel)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(secondCombo)
        }
    }

    private fun createSkillStatusPanel(label: JBLabel): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = SKILL_STATUS_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(SKILL_STATUS_BORDER, JBUI.scale(8)),
                JBUI.Borders.empty(4, 8, 4, 8),
            )
            add(label, BorderLayout.CENTER)
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
        skillTargetChannelCombo.addActionListener { scheduleAutoSave() }
        skillDraftModelCombo.addActionListener { scheduleAutoSave() }
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

    private fun refreshSkillDraftProviderComboPreservingSelection(preferredProvider: String?) {
        withUiSync {
            val router = LlmRouter.getInstance()
            val providers = router.availableUiProviders()
            skillDraftProviderCombo.removeAllItems()
            providers.forEach { skillDraftProviderCombo.addItem(it) }
            val selected = preferredProvider?.takeIf { providers.contains(it) } ?: providers.firstOrNull()
            if (selected != null) {
                skillDraftProviderCombo.selectedItem = selected
            }
            updateSkillComboPreferredSizes()
        }
    }

    private fun resolveSkillDraftPreferredProvider(): String? {
        return settings.skillGenerationProvider.ifBlank { settings.defaultProvider }.ifBlank { null }
    }

    private fun loadFromSettings() {
        refreshProviderComboPreservingSelection(settings.defaultProvider)
        refreshModelCombo()
        refreshSkillDraftProviderComboPreservingSelection(resolveSkillDraftPreferredProvider())
        refreshSkillDraftModelCombo(
            preferredModelId = settings.skillGenerationModel.ifBlank { settings.selectedCliModel },
        )
        interfaceLanguageCombo.selectedItem = InterfaceLanguage.fromCode(settings.interfaceLanguage)
        teamPromptRepoUrlField.text = settings.teamPromptRepoUrl
        teamPromptRepoBranchField.text = settings.teamPromptRepoBranch
        skillTargetScopeCombo.selectedItem = runCatching {
            SkillScope.valueOf(settings.skillGenerationScope.uppercase(Locale.ROOT))
        }.getOrDefault(SkillScope.PROJECT)
        skillTargetChannelCombo.selectedItem = runCatching {
            SkillSaveChannel.valueOf(settings.skillGenerationChannel.uppercase(Locale.ROOT))
        }.getOrDefault(SkillSaveChannel.ALL)
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
        val nextSkillGenerationChannel =
            (skillTargetChannelCombo.selectedItem as? SkillSaveChannel ?: SkillSaveChannel.ALL).name
        val nextSkillGenerationProvider = (skillDraftProviderCombo.selectedItem as? String).orEmpty()
        val nextSkillGenerationModel = (skillDraftModelCombo.selectedItem as? ModelInfo)?.id.orEmpty()
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
                nextSkillGenerationChannel != settings.skillGenerationChannel ||
                nextSkillGenerationProvider != settings.skillGenerationProvider ||
                nextSkillGenerationModel != settings.skillGenerationModel ||
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
        settings.skillGenerationChannel = nextSkillGenerationChannel
        settings.skillGenerationProvider = nextSkillGenerationProvider
        settings.skillGenerationModel = nextSkillGenerationModel
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
                val currentSkillDraftProvider = skillDraftProviderCombo.selectedItem as? String
                val currentSkillDraftModel = (skillDraftModelCombo.selectedItem as? ModelInfo)?.id
                refreshProviderComboPreservingSelection(currentProvider ?: settings.defaultProvider)
                refreshSkillDraftProviderComboPreservingSelection(
                    currentSkillDraftProvider ?: resolveSkillDraftPreferredProvider(),
                )
                ModelRegistry.getInstance().refreshFromDiscovery()
                refreshModelCombo()
                refreshSkillDraftModelCombo(
                    preferredModelId = currentSkillDraftModel
                        ?: settings.skillGenerationModel.ifBlank { settings.selectedCliModel },
                )
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
        refreshModelCombo(
            providerCombo = defaultProviderCombo,
            modelCombo = defaultModelCombo,
            preferredModelId = settings.selectedCliModel,
        )
    }

    private fun refreshSkillDraftModelCombo(preferredModelId: String? = null) {
        refreshModelCombo(
            providerCombo = skillDraftProviderCombo,
            modelCombo = skillDraftModelCombo,
            preferredModelId = preferredModelId ?: settings.skillGenerationModel.ifBlank { settings.selectedCliModel },
        )
        updateSkillComboPreferredSizes()
    }

    private fun refreshModelCombo(
        providerCombo: ComboBox<String>,
        modelCombo: ComboBox<ModelInfo>,
        preferredModelId: String?,
    ) {
        val selectedProvider = providerCombo.selectedItem as? String
        withUiSync {
            modelCombo.removeAllItems()
            if (selectedProvider.isNullOrBlank()) {
                return@withUiSync
            }
            val models = ModelRegistry.getInstance().getModelsForProvider(selectedProvider)
            models.forEach { modelCombo.addItem(it) }
            val match = preferredModelId?.let { selectedId -> models.find { it.id == selectedId } }
            if (match != null) {
                modelCombo.selectedItem = match
            } else if (modelCombo.itemCount > 0) {
                modelCombo.selectedIndex = 0
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
                    .onSuccess { snapshot ->
                        applySkillDiscoverySnapshot(snapshot)
                        if (skillsList.selectedValue == null && skillsListModel.size() > 0) {
                            skillsList.selectedIndex = 0
                        }
                    }
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
        hoveredSkillIndex = -1
        skillsListModel.clear()
        var projectCount = 0
        var globalCount = 0
        snapshot.skills.forEach { skill ->
            skillsListModel.addElement(skill)
            when (skill.scope ?: SkillScope.GLOBAL) {
                SkillScope.PROJECT -> projectCount += 1
                SkillScope.GLOBAL -> globalCount += 1
            }
        }

        setSkillDiscoveryStatus(
            SpecCodingBundle.message(
                "settings.skills.discovery.summary",
                snapshot.skills.size,
                projectCount,
                globalCount,
            ),
            if (snapshot.skills.isEmpty()) SkillStatusTone.NORMAL else SkillStatusTone.SUCCESS,
        )
    }

    private fun formatSkillMeta(skill: Skill): String {
        val scopeText = when (skill.scope ?: SkillScope.GLOBAL) {
            SkillScope.GLOBAL -> SpecCodingBundle.message("settings.skills.scope.global")
            SkillScope.PROJECT -> SpecCodingBundle.message("settings.skills.scope.project")
        }
        val sourceText = when (skill.sourceType) {
            SkillSourceType.BUILTIN -> SpecCodingBundle.message("settings.skills.source.builtin")
            SkillSourceType.YAML -> SpecCodingBundle.message("settings.skills.source.yaml")
            SkillSourceType.MARKDOWN -> SpecCodingBundle.message("settings.skills.source.markdown")
        }
        val detectedChannel = detectStorageChannel(
            skill.sourcePath,
            skill.tags.joinToString(" "),
        )
        val channelText = when {
            skill.sourceType == SkillSourceType.BUILTIN && detectedChannel == SkillStorageChannel.UNKNOWN ->
                SpecCodingBundle.message("settings.skills.channel.all")
            else -> channelDisplayText(detectedChannel)
        }
        return "[$scopeText]   [$channelText]   [$sourceText]"
    }

    private fun formatSkillTooltip(skill: Skill): String {
        val description = skill.description.trim().ifBlank { "/" + skill.slashCommand }
        return "<html><b>/${skill.slashCommand}</b><br/>$description</html>"
    }

    private fun updateHoveredSkillIndex(point: Point?) {
        val nextIndex = if (point == null) {
            -1
        } else {
            val index = skillsList.locationToIndex(point)
            if (index >= 0 && skillsList.getCellBounds(index, index)?.contains(point) == true) {
                index
            } else {
                -1
            }
        }
        if (hoveredSkillIndex != nextIndex) {
            hoveredSkillIndex = nextIndex
            skillsList.repaint()
        }
    }

    private fun resolveSkillItemIcon(skill: Skill): Icon {
        return when (detectStorageChannel(skill.sourcePath, skill.tags.joinToString(" "))) {
            SkillStorageChannel.CODEX -> SKILL_CODEX_ICON
            SkillStorageChannel.CLAUDE,
            SkillStorageChannel.CLUADE -> SKILL_CLAUDE_ICON
            SkillStorageChannel.UNKNOWN -> {
                when (skill.scope ?: SkillScope.GLOBAL) {
                    SkillScope.PROJECT -> SKILL_PROJECT_ICON
                    SkillScope.GLOBAL -> SKILL_GLOBAL_ICON
                }
            }
        }
    }

    private fun detectStorageChannel(vararg text: String?): SkillStorageChannel {
        val merged = text.joinToString(" ") { it.orEmpty() }.lowercase(Locale.ROOT)
        return when {
            merged.contains(".codex") -> SkillStorageChannel.CODEX
            merged.contains(".cluade") -> SkillStorageChannel.CLUADE
            merged.contains(".claude") -> SkillStorageChannel.CLAUDE
            else -> SkillStorageChannel.UNKNOWN
        }
    }

    private fun channelDisplayText(channel: SkillStorageChannel): String {
        return when (channel) {
            SkillStorageChannel.CODEX -> SpecCodingBundle.message("settings.skills.channel.codex")
            SkillStorageChannel.CLAUDE -> SpecCodingBundle.message("settings.skills.channel.claude")
            SkillStorageChannel.CLUADE -> SpecCodingBundle.message("settings.skills.channel.cluade")
            SkillStorageChannel.UNKNOWN -> SpecCodingBundle.message("settings.skills.channel.unknown")
        }
    }

    private fun loadSelectedSkillForEditing(skill: Skill?) {
        if (skill == null) {
            return
        }
        setSkillEditorStatus(SpecCodingBundle.message("settings.skills.editor.loading"), SkillStatusTone.NORMAL)
        scope.launch {
            val result = runCatching { buildEditorDraftFromSkill(skill) }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                result
                    .onSuccess { draft ->
                        applyDraftToEditor(draft = draft, sourcePath = skill.sourcePath)
                    }
                    .onFailure { error ->
                        setSkillEditorStatus(
                            SpecCodingBundle.message(
                                "settings.skills.editor.loadFailed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillStatusTone.ERROR,
                        )
                    }
            }
        }
    }

    private fun buildEditorDraftFromSkill(skill: Skill): GeneratedSkillDraft {
        val sourcePath = skill.sourcePath?.trim()?.ifBlank { null }
        val fallbackId = normalizeSkillToken(
            value = sourcePath
                ?.let { path -> runCatching { Paths.get(path).parent?.fileName?.toString() }.getOrNull() }
                ?.ifBlank { null }
                ?: skill.slashCommand,
            fallback = "custom-skill",
        )
        val fallback = GeneratedSkillDraft(
            id = fallbackId,
            name = skill.name,
            description = skill.description,
            slashCommand = normalizeSkillToken(skill.slashCommand, fallback = fallbackId),
            body = skill.promptTemplate,
        )
        if (sourcePath.isNullOrBlank()) {
            return fallback
        }
        val path = runCatching { Paths.get(sourcePath) }.getOrNull() ?: return fallback
        if (!Files.isRegularFile(path)) {
            return fallback
        }
        val content = readSkillFileText(path)
        return parseSkillMarkdownDraft(
            content = content,
            fallback = fallback,
        )
    }

    private fun readSkillFileText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        decodeBytesStrict(bytes, StandardCharsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GB18030") }.getOrNull()
            ?.let { decodeBytesStrict(bytes, it) }
            ?.let { return it }
        decodeBytesStrict(bytes, Charset.defaultCharset())?.let { return it }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun decodeBytesStrict(bytes: ByteArray, charset: Charset): String? {
        return runCatching {
            val decoder = charset.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    private fun parseSkillMarkdownDraft(content: String, fallback: GeneratedSkillDraft): GeneratedSkillDraft {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        if (!normalized.startsWith("---\n")) {
            val body = normalized.trim().ifBlank { fallback.body }
            return fallback.copy(body = body)
        }

        val lines = normalized.split('\n')
        var endIndex = -1
        for (index in 1 until lines.size) {
            if (lines[index].trim() == "---") {
                endIndex = index
                break
            }
        }
        if (endIndex <= 1) {
            val body = normalized.trim().ifBlank { fallback.body }
            return fallback.copy(body = body)
        }

        val metadata = linkedMapOf<String, String>()
        for (index in 1 until endIndex) {
            val line = lines[index].trim()
            if (line.isBlank() || line.startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim().trim('"')
            if (key.isNotBlank() && value.isNotBlank()) {
                metadata[key] = value
            }
        }
        val body = lines.drop(endIndex + 1).joinToString("\n").trim().ifBlank { fallback.body }
        return fallback.copy(
            id = normalizeSkillToken(metadata["id"] ?: fallback.id, fallback = fallback.id),
            name = metadata["name"]?.trim()?.ifBlank { null } ?: fallback.name,
            description = metadata["description"]?.trim()?.ifBlank { null } ?: fallback.description,
            slashCommand = normalizeSkillToken(
                value = metadata["slash_command"] ?: metadata["command"] ?: fallback.slashCommand,
                fallback = fallback.slashCommand,
            ),
            body = body,
        )
    }

    private fun resetSkillEditorForNewDraft() {
        activeSkillSourcePath = null
        val defaultId = when (skillTargetScopeCombo.selectedItem as? SkillScope ?: SkillScope.PROJECT) {
            SkillScope.GLOBAL -> "global-skill"
            SkillScope.PROJECT -> "project-skill"
        }
        applyDraftToEditor(
            draft = GeneratedSkillDraft(
                id = defaultId,
                name = "",
                description = "",
                slashCommand = defaultId,
                body = SpecCodingBundle.message("settings.skills.generate.body.fallback"),
            ),
            sourcePath = null,
        )
        skillAiDraftButton.isEnabled = true
        skillDeleteButton.isEnabled = false
        setSkillEditorStatus(SpecCodingBundle.message("settings.skills.editor.newReady"), SkillStatusTone.NORMAL)
    }

    private fun applyDraftToEditor(draft: GeneratedSkillDraft, sourcePath: String?) {
        skillIdField.text = draft.id
        skillNameField.text = draft.name
        skillDescriptionField.text = draft.description
        skillCommandField.text = draft.slashCommand
        skillMarkdownArea.text = draft.body
        activeSkillSourcePath = sourcePath
        skillSaveCurrentButton.isEnabled = !sourcePath.isNullOrBlank()
        skillDeleteButton.isEnabled = !sourcePath.isNullOrBlank()
        skillAiDraftButton.isEnabled = true
        val message = if (sourcePath.isNullOrBlank()) {
            SpecCodingBundle.message("settings.skills.editor.ready")
        } else {
            SpecCodingBundle.message("settings.skills.editor.editing", sourcePath)
        }
        setSkillEditorStatus(message, SkillStatusTone.NORMAL)
    }

    private fun generateSkillDraftWithAi() {
        val requirement = skillRequirementField.text.trim()
        if (requirement.isBlank()) {
            setSkillEditorStatus(
                SpecCodingBundle.message("settings.skills.generate.requirement.empty"),
                SkillStatusTone.ERROR,
            )
            return
        }

        persistSettings(reason = "settings-panel-skill-generate")

        skillAiDraftButton.isEnabled = false
        setSkillEditorStatus(
            SpecCodingBundle.message("settings.skills.generate.running"),
            SkillStatusTone.NORMAL,
        )
        val selectedProvider = (skillDraftProviderCombo.selectedItem as? String)?.trim().orEmpty()
        val selectedModelId = (skillDraftModelCombo.selectedItem as? ModelInfo)?.id.orEmpty()

        scope.launch {
            val result = runCatching {
                generateSkillDraft(
                    requirement = requirement,
                    preferredProvider = selectedProvider,
                    preferredModelId = selectedModelId,
                )
            }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                skillAiDraftButton.isEnabled = true
                result
                    .onSuccess { generated ->
                        applyDraftToEditor(draft = generated.draft, sourcePath = null)
                        if (generated.usedFallback) {
                            setSkillEditorStatus(
                                SpecCodingBundle.message(
                                    "settings.skills.generate.draftReady.fallback",
                                    generated.reason ?: SpecCodingBundle.message("common.unknown"),
                                ),
                                SkillStatusTone.NORMAL,
                            )
                        } else {
                            setSkillEditorStatus(
                                SpecCodingBundle.message("settings.skills.generate.draftReady"),
                                SkillStatusTone.SUCCESS,
                            )
                        }
                    }
                    .onFailure { error ->
                        setSkillEditorStatus(
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

    private fun saveSkillToSelectedTargets() {
        val draft = runCatching { collectEditorDraftOrThrow() }
            .onFailure { error ->
                setSkillEditorStatus(
                    error.message ?: SpecCodingBundle.message("settings.skills.editor.invalid"),
                    SkillStatusTone.ERROR,
                )
            }
            .getOrNull() ?: return
        val targetScope = (skillTargetScopeCombo.selectedItem as? SkillScope) ?: SkillScope.PROJECT
        val targetChannel = (skillTargetChannelCombo.selectedItem as? SkillSaveChannel) ?: SkillSaveChannel.ALL
        persistSettings(reason = "settings-panel-skill-save")

        skillSaveButton.isEnabled = false
        skillSaveCurrentButton.isEnabled = false
        skillDeleteButton.isEnabled = false
        setSkillEditorStatus(SpecCodingBundle.message("settings.skills.editor.saving"), SkillStatusTone.NORMAL)
        scope.launch {
            val result = runCatching { saveDraftToSelectedTargets(draft, targetScope, targetChannel) }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                skillSaveButton.isEnabled = true
                skillSaveCurrentButton.isEnabled = !activeSkillSourcePath.isNullOrBlank()
                skillDeleteButton.isEnabled = !activeSkillSourcePath.isNullOrBlank()
                result
                    .onSuccess { paths ->
                        setSkillEditorStatus(
                            SpecCodingBundle.message(
                                "settings.skills.editor.saveSuccess",
                                paths.size,
                                paths.firstOrNull()?.toString().orEmpty(),
                            ),
                            SkillStatusTone.SUCCESS,
                        )
                        refreshSkillDiscovery(forceReload = true)
                    }
                    .onFailure { error ->
                        setSkillEditorStatus(
                            SpecCodingBundle.message(
                                "settings.skills.editor.saveFailed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillStatusTone.ERROR,
                        )
                    }
            }
        }
    }

    private fun saveSkillToCurrentSource() {
        val sourcePath = activeSkillSourcePath?.trim().orEmpty()
        if (sourcePath.isBlank()) {
            setSkillEditorStatus(
                SpecCodingBundle.message("settings.skills.editor.saveCurrent.unavailable"),
                SkillStatusTone.ERROR,
            )
            return
        }
        val draft = runCatching { collectEditorDraftOrThrow() }
            .onFailure { error ->
                setSkillEditorStatus(
                    error.message ?: SpecCodingBundle.message("settings.skills.editor.invalid"),
                    SkillStatusTone.ERROR,
                )
            }
            .getOrNull() ?: return

        skillSaveButton.isEnabled = false
        skillSaveCurrentButton.isEnabled = false
        skillDeleteButton.isEnabled = false
        setSkillEditorStatus(
            SpecCodingBundle.message("settings.skills.editor.saveCurrent.running"),
            SkillStatusTone.NORMAL,
        )
        scope.launch {
            val result = runCatching {
                val path = Paths.get(sourcePath)
                Files.createDirectories(path.parent)
                Files.writeString(path, buildGeneratedSkillMarkdown(draft), StandardCharsets.UTF_8)
                path
            }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                skillSaveButton.isEnabled = true
                skillSaveCurrentButton.isEnabled = true
                skillDeleteButton.isEnabled = true
                result
                    .onSuccess { path ->
                        setSkillEditorStatus(
                            SpecCodingBundle.message("settings.skills.editor.saveCurrent.success", path.toString()),
                            SkillStatusTone.SUCCESS,
                        )
                        refreshSkillDiscovery(forceReload = true)
                    }
                    .onFailure { error ->
                        setSkillEditorStatus(
                            SpecCodingBundle.message(
                                "settings.skills.editor.saveCurrent.failed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillStatusTone.ERROR,
                        )
                    }
            }
        }
    }

    private fun deleteSkillFromCurrentSource() {
        val sourcePath = activeSkillSourcePath?.trim().orEmpty()
        if (sourcePath.isBlank()) {
            setSkillEditorStatus(
                SpecCodingBundle.message("settings.skills.editor.delete.unavailable"),
                SkillStatusTone.ERROR,
            )
            return
        }

        skillDeleteButton.isEnabled = false
        skillSaveButton.isEnabled = false
        skillSaveCurrentButton.isEnabled = false
        setSkillEditorStatus(
            SpecCodingBundle.message("settings.skills.editor.delete.running"),
            SkillStatusTone.NORMAL,
        )
        scope.launch {
            val result = runCatching {
                val path = Paths.get(sourcePath)
                if (!Files.exists(path)) {
                    throw IllegalStateException(SpecCodingBundle.message("settings.skills.editor.delete.unavailable"))
                }
                Files.delete(path)
                val parent = path.parent
                if (parent != null && Files.isDirectory(parent)) {
                    Files.list(parent).use { stream ->
                        if (!stream.findAny().isPresent) {
                            Files.delete(parent)
                        }
                    }
                }
                path
            }
            SwingUtilities.invokeLater {
                if (isDisposed || project.isDisposed) {
                    return@invokeLater
                }
                result
                    .onSuccess { deletedPath ->
                        activeSkillSourcePath = null
                        skillDeleteButton.isEnabled = false
                        skillSaveButton.isEnabled = true
                        skillSaveCurrentButton.isEnabled = false
                        setSkillEditorStatus(
                            SpecCodingBundle.message("settings.skills.editor.delete.success", deletedPath.toString()),
                            SkillStatusTone.SUCCESS,
                        )
                        refreshSkillDiscovery(forceReload = true)
                    }
                    .onFailure { error ->
                        skillDeleteButton.isEnabled = !activeSkillSourcePath.isNullOrBlank()
                        skillSaveButton.isEnabled = true
                        skillSaveCurrentButton.isEnabled = !activeSkillSourcePath.isNullOrBlank()
                        setSkillEditorStatus(
                            SpecCodingBundle.message(
                                "settings.skills.editor.delete.failed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ),
                            SkillStatusTone.ERROR,
                        )
                    }
            }
        }
    }

    private fun collectEditorDraftOrThrow(): GeneratedSkillDraft {
        val id = normalizeSkillToken(skillIdField.text, fallback = "")
        if (id.isBlank()) {
            throw IllegalArgumentException(SpecCodingBundle.message("settings.skills.editor.idRequired"))
        }
        val name = skillNameField.text.trim()
        if (name.isBlank()) {
            throw IllegalArgumentException(SpecCodingBundle.message("settings.skills.editor.nameRequired"))
        }
        val description = skillDescriptionField.text.trim()
        if (description.isBlank()) {
            throw IllegalArgumentException(SpecCodingBundle.message("settings.skills.editor.descriptionRequired"))
        }
        val slashCommand = normalizeSkillToken(skillCommandField.text.removePrefix("/"), fallback = "")
        if (slashCommand.isBlank()) {
            throw IllegalArgumentException(SpecCodingBundle.message("settings.skills.editor.commandRequired"))
        }
        val body = skillMarkdownArea.text
            ?.trim()
            ?.ifBlank { null }
            ?: throw IllegalArgumentException(SpecCodingBundle.message("settings.skills.editor.markdownRequired"))
        return GeneratedSkillDraft(
            id = id,
            name = name,
            description = description,
            slashCommand = slashCommand,
            body = body,
        )
    }

    private fun saveDraftToSelectedTargets(
        draft: GeneratedSkillDraft,
        scope: SkillScope,
        channel: SkillSaveChannel,
    ): List<Path> {
        val roots = resolveSkillTargetRoots(scope, channel)
        val savedPaths = mutableListOf<Path>()
        roots.forEach { root ->
            Files.createDirectories(root)
            val filePath = root.resolve(draft.id).resolve(SKILL_MARKDOWN_FILE_NAME)
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, buildGeneratedSkillMarkdown(draft), StandardCharsets.UTF_8)
            savedPaths.add(filePath)
        }
        return savedPaths
    }

    private fun resolveSkillTargetRoots(scope: SkillScope, channel: SkillSaveChannel): List<Path> {
        val base = resolveScopeBasePath(scope)
        val candidates = when (channel) {
            SkillSaveChannel.CODEX -> listOf(base.resolve(".codex").resolve("skills"))
            SkillSaveChannel.CLUADE -> listOf(
                base.resolve(".cluade").resolve("skills"),
                base.resolve(".claude").resolve("skills"),
            )

            SkillSaveChannel.ALL -> listOf(
                base.resolve(".codex").resolve("skills"),
                base.resolve(".cluade").resolve("skills"),
                base.resolve(".claude").resolve("skills"),
            )
        }
        val dedup = linkedMapOf<String, Path>()
        candidates.forEach { path ->
            val key = runCatching { path.toAbsolutePath().normalize().toString() }.getOrDefault(path.toString())
            dedup[key] = path
        }
        return dedup.values.toList()
    }

    private fun resolveScopeBasePath(scope: SkillScope): Path {
        return when (scope) {
            SkillScope.GLOBAL -> {
                val home = System.getProperty("user.home")
                    ?.trim()
                    ?.ifBlank { null }
                    ?: throw IllegalStateException(SpecCodingBundle.message("settings.skills.scope.global.unavailable"))
                Paths.get(home)
            }

            SkillScope.PROJECT -> {
                val basePath = project.basePath
                    ?.trim()
                    ?.ifBlank { null }
                    ?: throw IllegalStateException(SpecCodingBundle.message("settings.skills.scope.project.unavailable"))
                Paths.get(basePath)
            }
        }
    }

    private suspend fun generateSkillDraft(
        requirement: String,
        preferredProvider: String,
        preferredModelId: String,
    ): SkillDraftGenerationResult {
        val fallbackDraft = buildFallbackSkillDraft(requirement)
        val providers = LlmRouter.getInstance().availableUiProviders()
        if (providers.isEmpty()) {
            return SkillDraftGenerationResult(
                draft = fallbackDraft,
                usedFallback = true,
                reason = SpecCodingBundle.message("settings.skills.generate.fallback.reason.providerUnavailable"),
            )
        }
        val selectedDraftProvider = preferredProvider
            .ifBlank { settings.skillGenerationProvider.ifBlank { settings.defaultProvider } }
        val providerId = selectedDraftProvider
            .takeIf { providers.contains(it) }
            ?: providers.firstOrNull()
            ?: return SkillDraftGenerationResult(
                draft = fallbackDraft,
                usedFallback = true,
                reason = SpecCodingBundle.message("settings.skills.generate.fallback.reason.providerUnavailable"),
            )
        val modelId = preferredModelId
            .ifBlank { settings.skillGenerationModel.ifBlank { settings.selectedCliModel } }
            .ifBlank { null }
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
        val chatResult = withTimeoutOrNull(SKILL_DRAFT_TIMEOUT_MILLIS) {
            runCatching {
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
            }
        } ?: run {
            val timeoutRaw = responseText.toString().trim()
            if (timeoutRaw.isNotBlank()) {
                val timeoutRoot = runCatching { parseSkillJson(timeoutRaw) }.getOrNull()
                if (timeoutRoot != null) {
                    return buildSkillDraftFromJson(timeoutRoot, requirement)
                }
            }
            return SkillDraftGenerationResult(
                draft = fallbackDraft,
                usedFallback = true,
                reason = SpecCodingBundle.message("settings.skills.generate.fallback.reason.timeout"),
            )
        }
        if (chatResult.isFailure) {
            return SkillDraftGenerationResult(
                draft = fallbackDraft,
                usedFallback = true,
                reason = chatResult.exceptionOrNull()?.message
                    ?: SpecCodingBundle.message("common.unknown"),
            )
        }

        val rawResponse = responseText.toString().trim()
        if (rawResponse.isBlank()) {
            return SkillDraftGenerationResult(
                draft = fallbackDraft,
                usedFallback = true,
                reason = SpecCodingBundle.message("settings.skills.generate.fallback.reason.emptyResponse"),
            )
        }
        val root = runCatching { parseSkillJson(rawResponse) }.getOrElse {
            return SkillDraftGenerationResult(
                draft = fallbackDraft,
                usedFallback = true,
                reason = SpecCodingBundle.message("settings.skills.generate.invalidJson"),
            )
        }
        return buildSkillDraftFromJson(root, requirement)
    }

    private fun buildSkillDraftFromJson(root: JsonObject, requirement: String): SkillDraftGenerationResult {
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
        return SkillDraftGenerationResult(
            draft = GeneratedSkillDraft(
                id = normalizedId,
                name = rawName.ifBlank { normalizedId },
                description = rawDescription.ifBlank { requirement },
                slashCommand = normalizedCommand,
                body = body,
            ),
            usedFallback = false,
            reason = null,
        )
    }

    private fun buildFallbackSkillDraft(requirement: String): GeneratedSkillDraft {
        val normalizedId = normalizeSkillToken(
            value = requirement.replace("\\s+".toRegex(), "-"),
            fallback = "custom-skill",
        )
        return GeneratedSkillDraft(
            id = normalizedId,
            name = requirement.ifBlank { normalizedId }.take(60),
            description = requirement.ifBlank { SpecCodingBundle.message("settings.skills.generate.placeholder") },
            slashCommand = normalizedId,
            body = buildString {
                appendLine("## Objective")
                appendLine("- $requirement")
                appendLine()
                appendLine("## Workflow")
                appendLine("1. Clarify context and constraints.")
                appendLine("2. Implement only the required changes.")
                appendLine("3. Add/update tests if needed.")
                appendLine("4. Verify and summarize outcomes.")
            },
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

    private fun buildGeneratedSkillMarkdown(draft: GeneratedSkillDraft): String {
        val body = draft.body.trim().ifBlank {
            SpecCodingBundle.message("settings.skills.generate.body.fallback")
        }
        return buildString {
            appendLine("---")
            appendLine("id: ${draft.id}")
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

    private fun setSkillEditorStatus(text: String, tone: SkillStatusTone) {
        skillEditorStatusLabel.text = text
        skillEditorStatusLabel.foreground = when (tone) {
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

    private data class SkillDraftGenerationResult(
        val draft: GeneratedSkillDraft,
        val usedFallback: Boolean,
        val reason: String?,
    )

    private enum class SkillStatusTone {
        NORMAL,
        SUCCESS,
        ERROR,
    }

    private enum class SkillSaveChannel {
        CODEX,
        CLUADE,
        ALL,
    }

    private enum class SkillStorageChannel {
        CODEX,
        CLAUDE,
        CLUADE,
        UNKNOWN,
    }

    private inner class SkillItemRenderer : JPanel(), javax.swing.ListCellRenderer<Skill> {
        private val iconLabel = JBLabel()
        private val commandLabel = JBLabel()
        private val metaLabel = JBLabel()
        private val textPanel = JPanel()

        init {
            layout = BorderLayout(JBUI.scale(8), 0)
            isOpaque = true
            iconLabel.preferredSize = JBUI.size(16, 16)
            iconLabel.minimumSize = iconLabel.preferredSize
            iconLabel.maximumSize = iconLabel.preferredSize
            iconLabel.horizontalAlignment = SwingConstants.CENTER
            iconLabel.verticalAlignment = SwingConstants.TOP
            iconLabel.border = JBUI.Borders.emptyTop(1)
            commandLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            commandLabel.border = JBUI.Borders.emptyBottom(1)
            metaLabel.font = JBUI.Fonts.miniFont()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.isOpaque = false
            textPanel.add(commandLabel)
            textPanel.add(metaLabel)
            add(iconLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out Skill>?,
            value: Skill?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val skill = value
            if (skill == null) {
                iconLabel.icon = null
                commandLabel.text = ""
                metaLabel.text = ""
                return this
            }
            iconLabel.icon = resolveSkillItemIcon(skill)
            commandLabel.text = "/${skill.slashCommand}"
            metaLabel.text = formatSkillMeta(skill)
            toolTipText = formatSkillTooltip(skill)

            val selected = isSelected
            val hovered = !selected && index == hoveredSkillIndex
            background = when {
                selected -> SKILL_ITEM_SELECTED_BG
                hovered -> SKILL_ITEM_HOVER_BG
                else -> SKILL_ITEM_BG
            }
            commandLabel.foreground = when {
                selected -> SKILL_ITEM_SELECTED_TITLE_FG
                hovered -> SKILL_ITEM_HOVER_TITLE_FG
                else -> SKILL_ITEM_TITLE_FG
            }
            metaLabel.foreground = when {
                selected -> SKILL_ITEM_SELECTED_META_FG
                hovered -> SKILL_ITEM_HOVER_META_FG
                else -> SKILL_ITEM_META_FG
            }
            val dividerColor = when {
                selected -> SKILL_ITEM_SELECTED_BORDER
                hovered -> SKILL_ITEM_HOVER_BORDER
                else -> SKILL_ITEM_BORDER
            }
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, if (index < skillsListModel.size() - 1) 1 else 0, 0, dividerColor),
                JBUI.Borders.empty(8, 8, 8, 8),
            )
            return this
        }
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
            label.iconTextGap = JBUI.scale(6)
            label.border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(1, 2, 1, 2),
                BorderFactory.createCompoundBorder(
                    SidebarItemBorder(isSelected),
                    JBUI.Borders.empty(6, 7, 6, 8),
                ),
            )
            label.background = if (isSelected) SIDEBAR_ITEM_SELECTED_BG else SIDEBAR_ITEM_BG
            label.foreground = if (isSelected) SIDEBAR_ITEM_SELECTED_FG else SIDEBAR_ITEM_FG
            label.font = label.font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 11.5f)
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

                val leftInset = JBUI.scale(6).toFloat()
                val arc = JBUI.scale(10).toFloat()
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
                    val glowOutline = RoundRectangle2D.Float(
                        x + leftInset + 1f,
                        y + 1.5f,
                        width - leftInset - 3f,
                        height - 3f,
                        JBUI.scale(8).toFloat(),
                        JBUI.scale(8).toFloat(),
                    )
                    g2.color = SIDEBAR_ITEM_SELECTED_GLOW
                    g2.draw(glowOutline)

                    val barWidth = JBUI.scale(2).toFloat()
                    val barHeight = (height - JBUI.scale(14)).coerceAtLeast(JBUI.scale(12)).toFloat()
                    val barArc = JBUI.scale(3).toFloat()
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
            JBUI.scale(1),
            JBUI.scale(6),
            JBUI.scale(1),
            JBUI.scale(2),
        )

        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
            insets.set(
                JBUI.scale(1),
                JBUI.scale(6),
                JBUI.scale(1),
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

    private fun providerDisplayText(providerId: String?): String {
        val raw = when (providerId) {
            ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("settings.skills.channel.claude")
            CodexCliLlmProvider.ID -> SpecCodingBundle.message("settings.skills.channel.codex")
            else -> providerId.orEmpty()
        }
        return lowerUiText(raw)
    }

    private fun updateSkillComboPreferredSizes() {
        val scopeWidth = measureComboWidth(
            combo = skillTargetScopeCombo,
            minWidth = JBUI.scale(86),
            maxWidth = JBUI.scale(110),
        ) { scope ->
            when (scope) {
                SkillScope.GLOBAL -> SpecCodingBundle.message("settings.skills.scope.global")
                SkillScope.PROJECT -> SpecCodingBundle.message("settings.skills.scope.project")
            }
        }
        val providerWidth = measureComboWidth(
            combo = skillDraftProviderCombo,
            minWidth = JBUI.scale(86),
            maxWidth = JBUI.scale(110),
        ) { provider -> providerDisplayText(provider) }
        val primaryWidth = maxOf(scopeWidth, providerWidth)
        applyComboWidth(skillTargetScopeCombo, primaryWidth)
        applyComboWidth(skillDraftProviderCombo, primaryWidth)

        val channelWidth = measureComboWidth(
            combo = skillTargetChannelCombo,
            minWidth = JBUI.scale(94),
            maxWidth = JBUI.scale(132),
        ) { channel ->
            when (channel) {
                SkillSaveChannel.CODEX -> SpecCodingBundle.message("settings.skills.channel.codex")
                SkillSaveChannel.CLUADE -> SpecCodingBundle.message("settings.skills.channel.cluade")
                SkillSaveChannel.ALL -> SpecCodingBundle.message("settings.skills.channel.all")
            }
        }
        val modelWidth = measureComboWidth(
            combo = skillDraftModelCombo,
            minWidth = JBUI.scale(94),
            maxWidth = JBUI.scale(132),
        ) { model -> lowerUiText(model.name) }
        val secondaryWidth = maxOf(channelWidth, modelWidth)
        applyComboWidth(skillTargetChannelCombo, secondaryWidth)
        applyComboWidth(skillDraftModelCombo, secondaryWidth)
    }

    private fun <T> measureComboWidth(
        combo: ComboBox<T>,
        minWidth: Int,
        maxWidth: Int,
        textProvider: (T) -> String,
    ): Int {
        val fontMetrics = combo.getFontMetrics(combo.font)
        var targetWidth = minWidth
        for (index in 0 until combo.itemCount) {
            val item = combo.getItemAt(index) ?: continue
            val textWidth = fontMetrics.stringWidth(textProvider(item))
            targetWidth = maxOf(targetWidth, textWidth + JBUI.scale(44))
        }
        return targetWidth.coerceIn(minWidth, maxWidth)
    }

    private fun applyComboWidth(combo: ComboBox<*>, width: Int) {
        combo.preferredSize = JBUI.size(width, JBUI.scale(28))
        combo.minimumSize = combo.preferredSize
    }

    private fun lowerUiText(text: String): String = text.lowercase(Locale.ROOT)

    private fun normalizeOperationMode(input: String): String {
        return input
            .trim()
            .ifBlank { "default" }
            .uppercase(Locale.ROOT)
    }

    companion object {
        private const val SIDEBAR_WIDTH = 128
        private const val SIDEBAR_ITEM_HEIGHT = 40
        private val SIDEBAR_BG = JBColor(Color(237, 245, 255), Color(49, 57, 68))
        private val SIDEBAR_BORDER = JBColor(Color(182, 204, 233), Color(81, 94, 113))
        private val SIDEBAR_SURFACE_BG = JBColor(Color(245, 250, 255), Color(56, 65, 77))
        private val SIDEBAR_SURFACE_BORDER = JBColor(Color(199, 217, 242), Color(90, 104, 125))
        private val SIDEBAR_SELECTION_BG = JBColor(Color(214, 231, 255), Color(74, 93, 122))
        private val SIDEBAR_SELECTION_FG = JBColor(Color(39, 64, 106), Color(224, 234, 248))
        private val SIDEBAR_ITEM_BG = JBColor(Color(245, 250, 255), Color(56, 65, 77))
        private val SIDEBAR_ITEM_BORDER = JBColor(Color(216, 229, 248), Color(97, 113, 136))
        private val SIDEBAR_ITEM_FG = JBColor(Color(64, 84, 117), Color(194, 207, 227))
        private val SIDEBAR_ITEM_SELECTED_BG = JBColor(Color(223, 237, 255), Color(78, 98, 129))
        private val SIDEBAR_ITEM_SELECTED_BORDER = JBColor(Color(156, 184, 222), Color(114, 136, 169))
        private val SIDEBAR_ITEM_SELECTED_GLOW = JBColor(Color(235, 244, 255), Color(96, 118, 150))
        private val SIDEBAR_ITEM_SELECTED_FG = JBColor(Color(31, 55, 92), Color(233, 240, 251))
        private val SIDEBAR_ITEM_ACCENT = JBColor(Color(73, 118, 191), Color(159, 186, 227))
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
        private val SKILL_ITEM_BG = JBColor(Color(250, 253, 255), Color(58, 66, 77))
        private val SKILL_ITEM_BORDER = JBColor(Color(216, 228, 245), Color(93, 106, 125))
        private val SKILL_ITEM_TITLE_FG = JBColor(Color(27, 47, 82), Color(223, 232, 246))
        private val SKILL_ITEM_META_FG = JBColor(Color(84, 102, 129), Color(180, 194, 214))
        private val SKILL_ITEM_HOVER_BG = JBColor(Color(239, 247, 255), Color(69, 80, 97))
        private val SKILL_ITEM_HOVER_BORDER = JBColor(Color(186, 206, 234), Color(112, 130, 158))
        private val SKILL_ITEM_HOVER_TITLE_FG = JBColor(Color(25, 46, 80), Color(227, 236, 248))
        private val SKILL_ITEM_HOVER_META_FG = JBColor(Color(75, 96, 125), Color(190, 205, 227))
        private val SKILL_ITEM_SELECTED_BG = JBColor(Color(225, 238, 255), Color(78, 96, 123))
        private val SKILL_ITEM_SELECTED_BORDER = JBColor(Color(158, 186, 223), Color(119, 139, 170))
        private val SKILL_ITEM_SELECTED_TITLE_FG = JBColor(Color(24, 44, 78), Color(235, 241, 250))
        private val SKILL_ITEM_SELECTED_META_FG = JBColor(Color(63, 85, 116), Color(198, 212, 232))
        private val SKILL_CODEX_ICON = IconLoader.getIcon("/icons/skill-codex.svg", SettingsPanel::class.java)
        private val SKILL_CLAUDE_ICON = AllIcons.Vcs.History
        private val SKILL_PROJECT_ICON = AllIcons.Vcs.Branch
        private val SKILL_GLOBAL_ICON = IconLoader.getIcon("/icons/skill-global.svg", SettingsPanel::class.java)
        private val STATUS_NORMAL_FG = JBColor(Color(92, 106, 127), Color(177, 188, 204))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val STATUS_ERROR_FG = JBColor(Color(171, 55, 69), Color(226, 144, 154))
        private val SIDEBAR_MCP_ICON = IconLoader.getIcon("/icons/settings-mcp.svg", SettingsPanel::class.java)
        private val SIDEBAR_HOOKS_ICON = IconLoader.getIcon("/icons/settings-hooks.svg", SettingsPanel::class.java)
        private const val SKILL_MARKDOWN_FILE_NAME = "SKILL.md"
        private val SKILL_TOKEN_INVALID_REGEX = Regex("[^a-z0-9_-]+")
        private const val AUTO_SAVE_DEBOUNCE_MILLIS = 650
        private const val SKILL_DRAFT_TIMEOUT_MILLIS = 120_000L
    }
}
