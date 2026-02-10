package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * 操作模式选择器面板
 * 显示当前操作模式并允许用户切换
 */
class OperationModeSelector(private val project: Project) : JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)) {

    private val modeManager = OperationModeManager.getInstance(project)
    private val label = JBLabel("Mode:")
    private val comboBox = ComboBox(OperationMode.values())

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        add(label)
        add(comboBox)

        // 设置当前模式
        comboBox.selectedItem = modeManager.getCurrentMode()

        // 自定义渲染器显示模式名称和描述
        comboBox.renderer = OperationModeRenderer()
    }

    private fun setupListeners() {
        comboBox.addActionListener {
            val selectedMode = comboBox.selectedItem as? OperationMode ?: return@addActionListener
            if (selectedMode != modeManager.getCurrentMode()) {
                modeManager.switchMode(selectedMode)
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
            text = "${getModeIcon(value)} ${value.displayName}"
            toolTipText = value.description
        }

        return component
    }

    private fun getModeIcon(mode: OperationMode): String {
        return when (mode) {
            OperationMode.DEFAULT -> "🔒"
            OperationMode.PLAN -> "📋"
            OperationMode.AGENT -> "🤖"
            OperationMode.AUTO -> "⚡"
        }
    }
}
