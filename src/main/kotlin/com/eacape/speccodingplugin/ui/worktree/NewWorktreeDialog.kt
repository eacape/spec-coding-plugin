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
    private val baseBranchField = JBTextField(DEFAULT_BASE_BRANCH)

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
        return when (validateInput(readInput())) {
            NewWorktreeValidationError.SPEC_TASK_ID_REQUIRED -> {
                ValidationInfo(
                    SpecCodingBundle.message("worktree.dialog.validation.specTaskIdRequired"),
                    specTaskIdField,
                )
            }

            NewWorktreeValidationError.SHORT_NAME_REQUIRED -> {
                ValidationInfo(
                    SpecCodingBundle.message("worktree.dialog.validation.shortNameRequired"),
                    shortNameField,
                )
            }

            NewWorktreeValidationError.BASE_BRANCH_REQUIRED -> {
                ValidationInfo(
                    SpecCodingBundle.message("worktree.dialog.validation.baseBranchRequired"),
                    baseBranchField,
                )
            }

            null -> null
        }
    }

    override fun doOKAction() {
        val normalized = normalizeInput(readInput())
        resultSpecTaskId = normalized.specTaskId
        resultShortName = normalized.shortName
        resultBaseBranch = normalized.baseBranch
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

    private fun readInput(): NewWorktreeInput {
        return NewWorktreeInput(
            specTaskId = specTaskIdField.text.orEmpty(),
            shortName = shortNameField.text.orEmpty(),
            baseBranch = baseBranchField.text.orEmpty(),
        )
    }

    companion object {
        internal const val DEFAULT_BASE_BRANCH: String = "main"

        internal fun validateInput(input: NewWorktreeInput): NewWorktreeValidationError? {
            if (input.specTaskId.isBlank()) {
                return NewWorktreeValidationError.SPEC_TASK_ID_REQUIRED
            }
            if (input.shortName.isBlank()) {
                return NewWorktreeValidationError.SHORT_NAME_REQUIRED
            }
            if (input.baseBranch.isBlank()) {
                return NewWorktreeValidationError.BASE_BRANCH_REQUIRED
            }
            return null
        }

        internal fun normalizeInput(input: NewWorktreeInput): NewWorktreeInput {
            return NewWorktreeInput(
                specTaskId = input.specTaskId.trim(),
                shortName = input.shortName.trim(),
                baseBranch = input.baseBranch.trim(),
            )
        }
    }
}

internal data class NewWorktreeInput(
    val specTaskId: String,
    val shortName: String,
    val baseBranch: String,
)

internal enum class NewWorktreeValidationError {
    SPEC_TASK_ID_REQUIRED,
    SHORT_NAME_REQUIRED,
    BASE_BRANCH_REQUIRED,
}
