package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * 操作模式选择器面板
 * 显示当前操作模式并允许用户切换
 */
class OperationModeSelector(private val project: Project) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {

    private val modeManager = OperationModeManager.getInstance(project)
    private val windowStateStore = WindowStateStore.getInstance(project)
    private val label = JBLabel(SpecCodingBundle.message("operation.mode.label"))
    private val comboBox = ComboBox(OperationMode.values())

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        label.text = label.text.trim().trimEnd(':', '：')
        label.font = JBUI.Fonts.smallFont()
        comboBox.font = JBUI.Fonts.smallFont()
        comboBox.minimumSize = JBUI.size(82, 18)
        comboBox.preferredSize = JBUI.size(96, 18)
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = JBColor(Color(248, 250, 252), Color(46, 50, 56))
        comboBox.border = JBUI.Borders.empty(0)
        comboBox.isOpaque = false
        add(label)
        add(comboBox)

        // 设置当前模式
        val persistedMode = windowStateStore.snapshot().operationMode
            ?.let { runCatching { OperationMode.valueOf(it) }.getOrNull() }
        val initialMode = persistedMode ?: modeManager.getCurrentMode()
        modeManager.switchMode(initialMode)
        comboBox.selectedItem = initialMode

        // 自定义渲染器显示模式名称和描述
        comboBox.renderer = OperationModeRenderer()
    }

    private fun setupListeners() {
        comboBox.addActionListener {
            val selectedMode = comboBox.selectedItem as? OperationMode ?: return@addActionListener
            if (selectedMode != modeManager.getCurrentMode()) {
                modeManager.switchMode(selectedMode)
                windowStateStore.updateOperationMode(selectedMode.name)
                onModeChanged(selectedMode)
            }
        }
    }

    /**
     * 模式变更回调
     */
    private fun onModeChanged(mode: OperationMode) {
        // 可以在这里添加通知或其他 UI 更新
        // 例如显示模式切换的提示信息
    }

    /**
     * 获取当前选中的模式
     */
    fun getSelectedMode(): OperationMode {
        return comboBox.selectedItem as? OperationMode ?: OperationMode.DEFAULT
    }

    /**
     * 设置选中的模式
     */
    fun setSelectedMode(mode: OperationMode) {
        comboBox.selectedItem = mode
        windowStateStore.updateOperationMode(mode.name)
    }

    /**
     * 刷新显示
     */
    fun refresh() {
        comboBox.selectedItem = modeManager.getCurrentMode()
    }
}

/**
 * 操作模式渲染器
 */
private class OperationModeRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        if (value is OperationMode) {
            icon = getModeIcon(value)
            text = value.displayName.lowercase()
            iconTextGap = JBUI.scale(6)
            border = JBUI.Borders.empty(1, 4)
            toolTipText = value.description.lowercase()
        }

        return component
    }

    private fun getModeIcon(mode: OperationMode): Icon {
        return when (mode) {
            OperationMode.DEFAULT -> MODE_DEFAULT_ICON
            OperationMode.PLAN -> MODE_PLAN_ICON
            OperationMode.AGENT -> MODE_AGENT_ICON
            OperationMode.AUTO -> MODE_AUTO_ICON
        }
    }

    companion object {
        private val MODE_DEFAULT_ICON = IconLoader.getIcon("/icons/mode-default.svg", OperationModeSelector::class.java)
        private val MODE_PLAN_ICON = IconLoader.getIcon("/icons/mode-plan.svg", OperationModeSelector::class.java)
        private val MODE_AGENT_ICON = IconLoader.getIcon("/icons/mode-agent.svg", OperationModeSelector::class.java)
        private val MODE_AUTO_ICON = IconLoader.getIcon("/icons/mode-auto.svg", OperationModeSelector::class.java)
    }
}
