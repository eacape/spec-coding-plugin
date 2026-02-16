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
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 内嵌在 Tool Window 中的设置面板
 */
class SettingsPanel : JPanel(BorderLayout()) {

    private val settings = SpecCodingSettingsState.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val defaultProviderCombo = ComboBox<String>()
    private val defaultModelCombo = ComboBox<ModelInfo>()
    private val useProxyCheckBox = JBCheckBox()
    private val proxyHostField = JBTextField()
    private val proxyPortField = JBTextField()
    private val autoSaveCheckBox = JBCheckBox()
    private val maxHistorySizeField = JBTextField()
    private val codexCliPathField = JBTextField()
    private val claudeCodeCliPathField = JBTextField()
    private val detectCliButton = JButton()
    private val claudeCliStatusLabel = JBLabel("")
    private val codexCliStatusLabel = JBLabel("")
    private val defaultModeField = JBTextField()
    private val interfaceLanguageCombo = ComboBox(InterfaceLanguage.entries.toTypedArray())
    private val teamPromptRepoUrlField = JBTextField()
    private val teamPromptRepoBranchField = JBTextField()
    private val teamSkillRepoUrlField = JBTextField()
    private val teamSkillRepoBranchField = JBTextField()
    private val applyButton = JButton(SpecCodingBundle.message("settings.apply"))

    init {
        buildUi()
        loadFromSettings()
    }

    private fun buildUi() {
        val router = LlmRouter.getInstance()
        defaultProviderCombo.removeAllItems()
        router.availableUiProviders().forEach { defaultProviderCombo.addItem(it) }
        defaultProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = when (value) {
                ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli")
                CodexCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli")
                else -> value ?: ""
            }
        }
        defaultProviderCombo.addActionListener { refreshModelCombo() }

        defaultModelCombo.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = value?.name ?: ""
        }

        claudeCodeCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.claudePath.placeholder")
        codexCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.codexPath.placeholder")

        interfaceLanguageCombo.renderer = SimpleListCellRenderer.create<InterfaceLanguage> { label, value, _ ->
            label.text = value?.let { SpecCodingBundle.messageOrDefault(it.labelKey, it.code) } ?: ""
        }

        detectCliButton.text = SpecCodingBundle.message("settings.cli.detectButton")
        detectCliButton.addActionListener { detectCliTools() }
        updateCliStatusLabels()

        val cliDetectPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        cliDetectPanel.add(detectCliButton)

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
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.skillRepoUrl"), teamSkillRepoUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.skillBranch"), teamSkillRepoBranchField)
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
            .panel.apply { border = JBUI.Borders.empty(10) }

        val scrollPane = JBScrollPane(formPanel)

        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply { isOpaque = false }
        applyButton.addActionListener { applySettings() }
        bottomPanel.add(applyButton)

        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun loadFromSettings() {
        defaultProviderCombo.selectedItem = settings.defaultProvider
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
                updateCliStatusLabels()
                if (discoveryService.claudeInfo.available && claudeCodeCliPathField.text.isBlank()) {
                    claudeCodeCliPathField.text = discoveryService.claudeInfo.path
                }
                if (discoveryService.codexInfo.available && codexCliPathField.text.isBlank()) {
                    codexCliPathField.text = discoveryService.codexInfo.path
                }
                val router = LlmRouter.getInstance()
                router.refreshProviders()
                val currentProvider = defaultProviderCombo.selectedItem as? String
                defaultProviderCombo.removeAllItems()
                router.availableUiProviders().forEach { defaultProviderCombo.addItem(it) }
                if (currentProvider != null && defaultProviderCombo.itemCount > 0) {
                    defaultProviderCombo.selectedItem = currentProvider
                }
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
            claudeCliStatusLabel.foreground = JBColor.GREEN.darker()
        } else {
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.notFound")
            claudeCliStatusLabel.foreground = JBColor.GRAY
        }
        val codexInfo = discoveryService.codexInfo
        if (codexInfo.available) {
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.found", codexInfo.version ?: "unknown")
            codexCliStatusLabel.foreground = JBColor.GREEN.darker()
        } else {
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.notFound")
            codexCliStatusLabel.foreground = JBColor.GRAY
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
}
