package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class NewSpecWorkflowDialog(
    workflowOptions: List<WorkflowOption> = emptyList(),
) : DialogWrapper(true) {

    data class WorkflowOption(
        val workflowId: String,
        val title: String,
        val description: String = "",
    ) {
        override fun toString(): String {
            return SpecCodingBundle.message("spec.delta.workflow.option", title, workflowId)
        }
    }

    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea(4, 40)
    private val fullIntentRadio = JBRadioButton(SpecCodingBundle.message("spec.dialog.intent.full"), true)
    private val incrementalIntentRadio = JBRadioButton(SpecCodingBundle.message("spec.dialog.intent.incremental"), false)
    private val baselineLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.baseline"))
    private val baselineCombo = JComboBox(
        CollectionComboBoxModel(
            buildBaselineOptions(workflowOptions),
        ),
    )

    var resultTitle: String? = null
        private set
    var resultDescription: String? = null
        private set
    var resultChangeIntent: SpecChangeIntent = SpecChangeIntent.FULL
        private set
    var resultBaselineWorkflowId: String? = null
        private set

    init {
        ButtonGroup().apply {
            add(fullIntentRadio)
            add(incrementalIntentRadio)
        }
        fullIntentRadio.addActionListener { updateIntentUI() }
        incrementalIntentRadio.addActionListener { updateIntentUI() }
        init()
        title = SpecCodingBundle.message("spec.dialog.newWorkflow.title")
        updateIntentUI()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        val titleLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.title"))
        titleLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        titleField.alignmentX = JComponent.LEFT_ALIGNMENT
        titleField.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        panel.add(titleField)
        panel.add(javax.swing.Box.createVerticalStrut(12))

        val descLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.description"))
        descLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(descLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        val scrollPane = JScrollPane(descriptionArea)
        scrollPane.alignmentX = JComponent.LEFT_ALIGNMENT
        scrollPane.preferredSize = Dimension(400, 100)
        panel.add(scrollPane)
        panel.add(javax.swing.Box.createVerticalStrut(12))

        val intentLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.intent"))
        intentLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(intentLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        fullIntentRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        incrementalIntentRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(fullIntentRadio)
        panel.add(javax.swing.Box.createVerticalStrut(2))
        panel.add(incrementalIntentRadio)
        panel.add(javax.swing.Box.createVerticalStrut(8))

        baselineLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(baselineLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))
        baselineCombo.alignmentX = JComponent.LEFT_ALIGNMENT
        baselineCombo.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        panel.add(baselineCombo)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isNullOrBlank()) {
            return ValidationInfo(SpecCodingBundle.message("spec.dialog.validation.titleRequired"), titleField)
        }
        if (incrementalIntentRadio.isSelected) {
            if (descriptionArea.text.isNullOrBlank()) {
                return ValidationInfo(
                    SpecCodingBundle.message("spec.dialog.validation.changeSummaryRequired"),
                    descriptionArea,
                )
            }
        }
        return null
    }

    override fun doOKAction() {
        resultTitle = titleField.text.trim()
        resultDescription = descriptionArea.text.trim()
        resultChangeIntent = if (incrementalIntentRadio.isSelected) {
            SpecChangeIntent.INCREMENTAL
        } else {
            SpecChangeIntent.FULL
        }
        resultBaselineWorkflowId = if (resultChangeIntent == SpecChangeIntent.INCREMENTAL) {
            (baselineCombo.selectedItem as? WorkflowOption)?.workflowId
        } else {
            null
        }
        super.doOKAction()
    }

    private fun updateIntentUI() {
        val incremental = incrementalIntentRadio.isSelected
        baselineLabel.isVisible = incremental
        baselineCombo.isVisible = incremental
        baselineCombo.isEnabled = incremental
    }

    private fun buildBaselineOptions(workflowOptions: List<WorkflowOption>): List<Any> {
        val candidates = workflowOptions.filter { it.workflowId.isNotBlank() }
        val noneOption = SpecCodingBundle.message("spec.dialog.baseline.none")
        return listOf(noneOption) + candidates
    }

    override fun getPreferredFocusedComponent() = titleField
}
