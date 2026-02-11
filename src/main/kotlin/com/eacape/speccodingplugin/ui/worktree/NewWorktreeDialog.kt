package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class NewWorktreeDialog(
    specTaskId: String? = null,
    specTitle: String? = null,
) : DialogWrapper(true) {

    private val specTaskIdField = JBTextField(specTaskId.orEmpty())
    private val shortNameField = JBTextField()
    private val baseBranchField = JBTextField("main")

    var resultSpecTaskId: String? = null
        private set
    var resultShortName: String? = null
        private set
    var resultBaseBranch: String? = null
        private set

    init {
        title = SpecCodingBundle.message("worktree.dialog.new.title")
        if (!specTitle.isNullOrBlank()) {
            setTitle("${SpecCodingBundle.message("worktree.dialog.new.title")} - $specTitle")
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        panel.add(labeledField(SpecCodingBundle.message("worktree.dialog.field.specTaskId"), specTaskIdField))
        panel.add(Box.createVerticalStrut(10))
        panel.add(labeledField(SpecCodingBundle.message("worktree.dialog.field.shortName"), shortNameField))
        panel.add(Box.createVerticalStrut(10))
        panel.add(labeledField(SpecCodingBundle.message("worktree.dialog.field.baseBranch"), baseBranchField))

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (specTaskIdField.text.isNullOrBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("worktree.dialog.validation.specTaskIdRequired"),
                specTaskIdField,
            )
        }
        if (shortNameField.text.isNullOrBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("worktree.dialog.validation.shortNameRequired"),
                shortNameField,
            )
        }
        if (baseBranchField.text.isNullOrBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("worktree.dialog.validation.baseBranchRequired"),
                baseBranchField,
            )
        }
        return null
    }

    override fun doOKAction() {
        resultSpecTaskId = specTaskIdField.text.trim()
        resultShortName = shortNameField.text.trim()
        resultBaseBranch = baseBranchField.text.trim()
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = shortNameField

    private fun labeledField(label: String, field: JBTextField): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
        }
        panel.add(JBLabel(label).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(Box.createVerticalStrut(4))
        panel.add(field.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        })
        return panel
    }
}

