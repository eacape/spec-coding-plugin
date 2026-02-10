package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * 状态栏模型选择器 Widget
 * 显示当前选中的模型，点击可弹出模型列表进行切换
 */
class ModelSelectorWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.MultipleTextValuesPresentation {

    private val modelRegistry = ModelRegistry.getInstance()
    private val settings = SpecCodingSettingsState.getInstance()

    override fun ID(): String = "SpecCoding.ModelSelector"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getTooltipText(): String {
        val currentModel = getCurrentModel()
        return if (currentModel != null) {
            "Current Model: ${currentModel.name} (${currentModel.provider})\nContext: ${currentModel.contextWindow} tokens\nClick to change"
        } else {
            "No model selected. Click to select a model."
        }
    }

    override fun getSelectedValue(): String {
        val currentModel = getCurrentModel()
        return if (currentModel != null) {
            "🤖 ${currentModel.name}"
        } else {
            "🤖 No Model"
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { showModelSelectionPopup() }
    }

    /**
     * 显示模型选择弹窗
     */
    private fun showModelSelectionPopup() {
        val popup = createModelSelectionPopup()
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
        popup.showInCenterOf(statusBar.component ?: return)
    }

    /**
     * 创建模型选择弹窗
     */
    private fun createModelSelectionPopup(): ListPopup {
        val modelsByProvider = modelRegistry.getModelsByProvider()
        val currentProvider = settings.defaultProvider

        // 创建分组步骤
        val providerStep = object : BaseListPopupStep<String>("Select Provider", modelsByProvider.keys.toList()) {
            override fun getTextFor(value: String): String {
                return when (value) {
                    "openai" -> "OpenAI"
                    "anthropic" -> "Anthropic"
                    else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            }

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    return null
                }
                // 返回模型选择步骤
                return createModelSelectionStep(selectedValue, modelsByProvider[selectedValue] ?: emptyList())
            }

            override fun hasSubstep(selectedValue: String): Boolean = true
        }

        return JBPopupFactory.getInstance().createListPopup(providerStep)
    }

    /**
     * 创建模型选择步骤
     */
    private fun createModelSelectionStep(provider: String, models: List<ModelInfo>): PopupStep<ModelInfo> {
        return object : BaseListPopupStep<ModelInfo>("Select Model", models) {
            override fun getTextFor(value: ModelInfo): String {
                val current = if (isCurrentModel(value)) " ✓" else ""
                return "${value.name}$current"
            }

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: ModelInfo, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    selectModel(provider, selectedValue)
                }
                return PopupStep.FINAL_CHOICE
            }
        }
    }

    /**
     * 选择模型
     */
    private fun selectModel(provider: String, model: ModelInfo) {
        // 更新设置
        settings.defaultProvider = provider
        when (provider) {
            "openai" -> settings.openaiModel = model.id
            "anthropic" -> settings.anthropicModel = model.id
        }

        // 刷新状态栏显示
        myStatusBar?.updateWidget(ID())
    }

    /**
     * 获取当前模型
     */
    private fun getCurrentModel(): ModelInfo? {
        val provider = settings.defaultProvider
        val modelId = when (provider) {
            "openai" -> settings.openaiModel
            "anthropic" -> settings.anthropicModel
            else -> null
        }
        return modelId?.let { modelRegistry.getModel(it) }
    }

    /**
     * 检查是否是当前模型
     */
    private fun isCurrentModel(model: ModelInfo): Boolean {
        val current = getCurrentModel()
        return current?.id == model.id
    }

    companion object {
        const val ID = "SpecCoding.ModelSelector"
    }
}
