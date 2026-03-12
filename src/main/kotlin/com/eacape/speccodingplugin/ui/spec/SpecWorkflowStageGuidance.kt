package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecWorkflowStageGuidance(
    val headline: String,
    val summary: String,
    val checklist: List<String>,
)

internal object SpecWorkflowStageGuidanceBuilder {
    fun build(state: SpecWorkflowOverviewState): SpecWorkflowStageGuidance {
        val stageLabel = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage)
        return SpecWorkflowStageGuidance(
            headline = SpecCodingBundle.message("spec.toolwindow.overview.focus.title", stageLabel),
            summary = buildSummary(state),
            checklist = buildChecklist(state),
        )
    }

    private fun buildSummary(state: SpecWorkflowOverviewState): String {
        if (state.status == WorkflowStatus.COMPLETED || state.currentStage == StageId.ARCHIVE) {
            return SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.archive")
        }
        return when (state.currentStage) {
            StageId.REQUIREMENTS -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.requirements")
            StageId.DESIGN -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.design")
            StageId.TASKS -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.tasks")
            StageId.IMPLEMENT -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.implement")
            StageId.VERIFY -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.verify")
            StageId.ARCHIVE -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.archive")
        }
    }

    private fun buildChecklist(state: SpecWorkflowOverviewState): List<String> {
        val items = mutableListOf<String>()
        when (state.gateStatus) {
            GateStatus.ERROR -> items += SpecCodingBundle.message("spec.toolwindow.overview.checklist.gate.error")
            GateStatus.WARNING -> items += SpecCodingBundle.message("spec.toolwindow.overview.checklist.gate.warning")
            else -> Unit
        }
        items += stageChecklistItems(state.currentStage)
        items += buildNextStepLine(state)
        return items.filter { it.isNotBlank() }
    }

    private fun stageChecklistItems(stage: StageId): List<String> {
        return when (stage) {
            StageId.REQUIREMENTS -> listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.requirements.1"),
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.requirements.2"),
            )

            StageId.DESIGN -> listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.design.1"),
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.design.2"),
            )

            StageId.TASKS -> listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.tasks.1"),
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.tasks.2"),
            )

            StageId.IMPLEMENT -> listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.implement.1"),
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.implement.2"),
            )

            StageId.VERIFY -> listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.verify.1"),
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.verify.2"),
            )

            StageId.ARCHIVE -> listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.archive.1"),
                SpecCodingBundle.message("spec.toolwindow.overview.checklist.archive.2"),
            )
        }
    }

    private fun buildNextStepLine(state: SpecWorkflowOverviewState): String {
        val nextStage = state.nextStage ?: return SpecCodingBundle.message("spec.toolwindow.overview.checklist.next.none")
        return SpecCodingBundle.message(
            "spec.toolwindow.overview.checklist.next",
            SpecWorkflowOverviewPresenter.stageLabel(nextStage),
        )
    }
}
