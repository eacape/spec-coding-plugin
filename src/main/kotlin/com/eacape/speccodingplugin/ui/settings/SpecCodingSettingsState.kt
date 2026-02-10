package com.eacape.speccodingplugin.ui.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Settings State
 * 存储插件配置（不包含敏感信息）
 */
@Service(Service.Level.APP)
@State(
    name = "SpecCodingSettings",
    storages = [Storage("specCodingSettings.xml")]
)
class SpecCodingSettingsState : PersistentStateComponent<SpecCodingSettingsState> {

    // 默认 LLM 提供者
    var defaultProvider: String = "mock"

    // 默认模型
    var defaultModel: String = "gpt-4o"

    // OpenAI 配置
    var openaiBaseUrl: String = "https://api.openai.com/v1"
    var openaiModel: String = "gpt-4o"

    // Anthropic 配置
    var anthropicBaseUrl: String = "https://api.anthropic.com/v1"
    var anthropicModel: String = "claude-opus-4-20250514"

    // 代理设置
    var useProxy: Boolean = false
    var proxyHost: String = ""
    var proxyPort: Int = 8080

    // 其他设置
    var autoSaveConversation: Boolean = true
    var maxHistorySize: Int = 100

    // 引擎路径配置
    var codexCliPath: String = ""
    var claudeCodeCliPath: String = ""

    // 默认操作模式
    var defaultOperationMode: String = "DEFAULT"

    override fun getState(): SpecCodingSettingsState = this

    override fun loadState(state: SpecCodingSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): SpecCodingSettingsState {
            return com.intellij.openapi.components.service()
        }
    }
}
