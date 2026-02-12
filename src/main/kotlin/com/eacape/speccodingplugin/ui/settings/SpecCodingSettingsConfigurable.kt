package com.eacape.speccodingplugin.ui.settings

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.InterfaceLanguage
import com.eacape.speccodingplugin.i18n.LocaleManager
import com.eacape.speccodingplugin.llm.AnthropicProvider
import com.eacape.speccodingplugin.llm.OpenAiProvider
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings Configurable
 * Spec Coding 插件的设置页面
 */
class SpecCodingSettingsConfigurable : Configurable {

    private val settings = SpecCodingSettingsState.getInstance()
    private val globalConfigSyncService = GlobalConfigSyncService.getInstance()
    private val localeManager = LocaleManager.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // OpenAI 配置
    private val openaiKeyField = JBPasswordField()
    private val openaiBaseUrlField = JBTextField()
    private val openaiModelField = JBTextField()
    private val openaiTestButton = JButton("Test Connection")
    private val openaiTestResult = JBLabel("")

    // Anthropic 配置
    private val anthropicKeyField = JBPasswordField()
    private val anthropicBaseUrlField = JBTextField()
    private val anthropicModelField = JBTextField()
    private val anthropicTestButton = JButton("Test Connection")
    private val anthropicTestResult = JBLabel("")

    // 默认提供者
    private val defaultProviderField = JBTextField()

    // 代理设置
    private val useProxyCheckBox = JBCheckBox("Use Proxy")
    private val proxyHostField = JBTextField()
    private val proxyPortField = JBTextField()

    // 其他设置
    private val autoSaveCheckBox = JBCheckBox("Auto-save conversations")
    private val maxHistorySizeField = JBTextField()

    // 引擎路径
    private val codexCliPathField = JBTextField()
    private val claudeCodeCliPathField = JBTextField()

    // 操作模式
    private val defaultModeField = JBTextField()

    // 语言设置
    private val interfaceLanguageCombo = ComboBox(InterfaceLanguage.entries.toTypedArray())

    override fun getDisplayName(): String = SpecCodingBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        // 加载当前配置
        reset()

        interfaceLanguageCombo.renderer = InterfaceLanguageCellRenderer.create()
        openaiTestButton.text = SpecCodingBundle.message("settings.test.button")
        anthropicTestButton.text = SpecCodingBundle.message("settings.test.button")

        // 设置验证连接按钮事件
        openaiTestButton.addActionListener { testOpenAiConnection() }
        anthropicTestButton.addActionListener { testAnthropicConnection() }

        // OpenAI 测试连接行
        val openaiTestPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        openaiTestPanel.isOpaque = false
        openaiTestPanel.add(openaiTestButton)
        openaiTestPanel.add(openaiTestResult)

        // Anthropic 测试连接行
        val anthropicTestPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        anthropicTestPanel.isOpaque = false
        anthropicTestPanel.add(anthropicTestButton)
        anthropicTestPanel.add(anthropicTestResult)

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.openai")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.openai.apiKey"), openaiKeyField)
            .addLabeledComponent(SpecCodingBundle.message("settings.openai.baseUrl"), openaiBaseUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.openai.defaultModel"), openaiModelField)
            .addComponent(openaiTestPanel)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.anthropic")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.anthropic.apiKey"), anthropicKeyField)
            .addLabeledComponent(SpecCodingBundle.message("settings.anthropic.baseUrl"), anthropicBaseUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.anthropic.defaultModel"), anthropicModelField)
            .addComponent(anthropicTestPanel)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.general")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultProvider"), defaultProviderField)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.interfaceLanguage"), interfaceLanguageCombo)
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
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.engine")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.codexPath"), codexCliPathField)
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.claudePath"), claudeCodeCliPathField)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.operationMode")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.operationMode.defaultMode"), defaultModeField)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(10)
            }
    }

    override fun isModified(): Boolean {
        // 检查 OpenAI 配置
        if (openaiBaseUrlField.text != settings.openaiBaseUrl) return true
        if (openaiModelField.text != settings.openaiModel) return true

        // 检查 Anthropic 配置
        if (anthropicBaseUrlField.text != settings.anthropicBaseUrl) return true
        if (anthropicModelField.text != settings.anthropicModel) return true

        // 检查默认提供者
        if (defaultProviderField.text != settings.defaultProvider) return true
        if ((interfaceLanguageCombo.selectedItem as? InterfaceLanguage)?.code != settings.interfaceLanguage) return true

        // 检查代理设置
        if (useProxyCheckBox.isSelected != settings.useProxy) return true
        if (proxyHostField.text != settings.proxyHost) return true
        if (proxyPortField.text != settings.proxyPort.toString()) return true

        // 检查其他设置
        if (autoSaveCheckBox.isSelected != settings.autoSaveConversation) return true
        if (maxHistorySizeField.text != settings.maxHistorySize.toString()) return true

        // 检查引擎路径
        if (codexCliPathField.text != settings.codexCliPath) return true
        if (claudeCodeCliPathField.text != settings.claudeCodeCliPath) return true

        // 检查操作模式
        if (defaultModeField.text != settings.defaultOperationMode) return true

        // 检查 API Keys
        val currentOpenAiKey = ApiKeyManager.getOpenAiKey() ?: ""
        val currentAnthropicKey = ApiKeyManager.getAnthropicKey() ?: ""
        if (String(openaiKeyField.password) != currentOpenAiKey) return true
        if (String(anthropicKeyField.password) != currentAnthropicKey) return true

        return false
    }

    override fun apply() {
        // 保存 OpenAI 配置
        settings.openaiBaseUrl = openaiBaseUrlField.text
        settings.openaiModel = openaiModelField.text
        val openaiKey = String(openaiKeyField.password)
        if (openaiKey.isNotBlank()) {
            ApiKeyManager.saveOpenAiKey(openaiKey)
        }

        // 保存 Anthropic 配置
        settings.anthropicBaseUrl = anthropicBaseUrlField.text
        settings.anthropicModel = anthropicModelField.text
        val anthropicKey = String(anthropicKeyField.password)
        if (anthropicKey.isNotBlank()) {
            ApiKeyManager.saveAnthropicKey(anthropicKey)
        }

        // 保存默认提供者
        settings.defaultProvider = defaultProviderField.text
        val selectedLanguage = (interfaceLanguageCombo.selectedItem as? InterfaceLanguage) ?: InterfaceLanguage.AUTO
        val localeChanged = localeManager.setLanguage(selectedLanguage, reason = "settings-configurable-apply")

        // 保存代理设置
        settings.useProxy = useProxyCheckBox.isSelected
        settings.proxyHost = proxyHostField.text
        settings.proxyPort = proxyPortField.text.toIntOrNull() ?: 8080

        // 保存其他设置
        settings.autoSaveConversation = autoSaveCheckBox.isSelected
        settings.maxHistorySize = maxHistorySizeField.text.toIntOrNull() ?: 100

        // 保存引擎路径
        settings.codexCliPath = codexCliPathField.text
        settings.claudeCodeCliPath = claudeCodeCliPathField.text

        // 保存操作模式
        settings.defaultOperationMode = defaultModeField.text

        globalConfigSyncService.notifyGlobalConfigChanged(
            sourceProject = null,
            reason = if (localeChanged != null) "settings-configurable-apply-with-locale" else "settings-configurable-apply",
        )
    }

    override fun reset() {
        // 加载 OpenAI 配置
        openaiBaseUrlField.text = settings.openaiBaseUrl
        openaiModelField.text = settings.openaiModel
        val openaiKey = ApiKeyManager.getOpenAiKey()
        if (openaiKey != null) {
            openaiKeyField.text = openaiKey
        }

        // 加载 Anthropic 配置
        anthropicBaseUrlField.text = settings.anthropicBaseUrl
        anthropicModelField.text = settings.anthropicModel
        val anthropicKey = ApiKeyManager.getAnthropicKey()
        if (anthropicKey != null) {
            anthropicKeyField.text = anthropicKey
        }

        // 加载默认提供者
        defaultProviderField.text = settings.defaultProvider
        interfaceLanguageCombo.selectedItem = InterfaceLanguage.fromCode(settings.interfaceLanguage)

        // 加载代理设置
        useProxyCheckBox.isSelected = settings.useProxy
        proxyHostField.text = settings.proxyHost
        proxyPortField.text = settings.proxyPort.toString()

        // 加载其他设置
        autoSaveCheckBox.isSelected = settings.autoSaveConversation
        maxHistorySizeField.text = settings.maxHistorySize.toString()

        // 加载引擎路径
        codexCliPathField.text = settings.codexCliPath
        claudeCodeCliPathField.text = settings.claudeCodeCliPath

        // 加载操作模式
        defaultModeField.text = settings.defaultOperationMode
    }

    override fun disposeUIResources() {
        scope.cancel()
    }

    private fun testOpenAiConnection() {
        val apiKey = String(openaiKeyField.password).trim()
        if (apiKey.isBlank()) {
            openaiTestResult.text = SpecCodingBundle.message("settings.test.enterApiKey")
            openaiTestResult.foreground = JBColor.RED
            return
        }

        openaiTestButton.isEnabled = false
        openaiTestResult.text = SpecCodingBundle.message("settings.test.testing")
        openaiTestResult.foreground = JBColor.GRAY

        val baseUrl = openaiBaseUrlField.text.trim().ifBlank { "https://api.openai.com/v1" }
        val provider = OpenAiProvider(apiKey = apiKey, baseUrl = baseUrl)

        scope.launch {
            val status = provider.healthCheck()
            javax.swing.SwingUtilities.invokeLater {
                if (status.healthy) {
                    openaiTestResult.text = SpecCodingBundle.message("settings.test.connected")
                    openaiTestResult.foreground = JBColor.GREEN.darker()
                } else {
                    openaiTestResult.text = status.message ?: SpecCodingBundle.message("settings.test.connectionFailed")
                    openaiTestResult.foreground = JBColor.RED
                }
                openaiTestButton.isEnabled = true
                provider.close()
            }
        }
    }

    private fun testAnthropicConnection() {
        val apiKey = String(anthropicKeyField.password).trim()
        if (apiKey.isBlank()) {
            anthropicTestResult.text = SpecCodingBundle.message("settings.test.enterApiKey")
            anthropicTestResult.foreground = JBColor.RED
            return
        }

        anthropicTestButton.isEnabled = false
        anthropicTestResult.text = SpecCodingBundle.message("settings.test.testing")
        anthropicTestResult.foreground = JBColor.GRAY

        val baseUrl = anthropicBaseUrlField.text.trim().ifBlank { "https://api.anthropic.com/v1" }
        val model = anthropicModelField.text.trim().ifBlank { "claude-opus-4-20250514" }
        val provider = AnthropicProvider(apiKey = apiKey, baseUrl = baseUrl, defaultModel = model)

        scope.launch {
            val status = provider.healthCheck()
            javax.swing.SwingUtilities.invokeLater {
                if (status.healthy) {
                    anthropicTestResult.text = SpecCodingBundle.message("settings.test.connected")
                    anthropicTestResult.foreground = JBColor.GREEN.darker()
                } else {
                    anthropicTestResult.text = status.message ?: SpecCodingBundle.message("settings.test.connectionFailed")
                    anthropicTestResult.foreground = JBColor.RED
                }
                anthropicTestButton.isEnabled = true
                provider.close()
            }
        }
    }

    private object InterfaceLanguageCellRenderer {
        fun create(): SimpleListCellRenderer<InterfaceLanguage> {
            return SimpleListCellRenderer.create<InterfaceLanguage> { label, value, _ ->
                val text = value?.let { SpecCodingBundle.messageOrDefault(it.labelKey, it.code) } ?: ""
                label.text = text
            }
        }
    }
}
