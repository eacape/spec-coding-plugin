package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle

internal object SpecWorkflowResponseBuilder {

    fun buildPhaseTransitionResponse(
        workflowId: String,
        phaseDisplayName: String,
        template: String,
        advanced: Boolean,
        templateInserted: Boolean,
    ): String {
        val transitionMessage = SpecCodingBundle.message(
            if (advanced) "toolwindow.spec.command.phaseAdvanced" else "toolwindow.spec.command.phaseBack",
            workflowId,
            phaseDisplayName,
        )

        return buildString {
            appendLine("## ${SpecCodingBundle.message("chat.workflow.section.plan")}")
            appendLine(transitionMessage)
            appendLine()
            appendLine("## ${SpecCodingBundle.message("chat.workflow.section.execute")}")
            appendLine(SpecCodingBundle.message("toolwindow.spec.command.phaseTemplate.title", phaseDisplayName))
            appendLine("```markdown")
            appendLine(template)
            appendLine("```")
            appendLine("- `/spec generate <input>`")
            if (templateInserted) {
                appendLine(SpecCodingBundle.message("toolwindow.spec.command.phaseTemplate.inserted"))
            }
            appendLine()
            appendLine("## ${SpecCodingBundle.message("chat.workflow.section.verify")}")
            appendLine("- `/spec status`")
        }.trimEnd()
    }
}
