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
    var defaultProvider: String = "claude-cli"

    // 当前选中的 CLI 模型 ID
    var selectedCliModel: String = ""

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

    // 界面语言偏好（AUTO / ENGLISH / ZH_CN）
    var interfaceLanguage: String = "AUTO"

    // 团队 Prompt 同步配置
    var teamPromptRepoUrl: String = ""
    var teamPromptRepoBranch: String = "main"

    // 团队 Skill 同步配置
    var teamSkillRepoUrl: String = ""
    var teamSkillRepoBranch: String = "main"

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
