package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecWorkflowStageGuidance(
    val headline: String,
    val summary: String,
    val checklist: List<String>,
)

internal object SpecWorkflowStageGuidanceBuilder {
    fun build(state: SpecWorkflowOverviewState): SpecWorkflowStageGuidance {
        val activeStages = state.activeStages.ifEmpty { state.stageStepper.stages.map { it.stageId } }
        val currentStep = state.stageStepper.stages.firstOrNull { it.stageId == state.currentStage }
        val fallbackWorkbenchState = SpecWorkflowStageWorkbenchState(
            currentStage = state.currentStage,
            focusedStage = state.currentStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = activeStages.indexOf(state.currentStage).coerceAtLeast(0) + 1,
                totalSteps = activeStages.size.coerceAtLeast(1),
                stageStatus = currentStep?.progress ?: StageProgress.IN_PROGRESS,
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = state.currentStage,
                title = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage),
                fileName = state.currentStage.artifactFileName,
                documentPhase = null,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
            ),
            visibleSections = SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = state.currentStage,
                status = state.status,
            ),
        )
        return build(state, fallbackWorkbenchState)
    }

    fun build(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowStageGuidance {
        val stageLabel = SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage)
        return SpecWorkflowStageGuidance(
            headline = if (workbenchState.focusedStage == state.currentStage) {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.title.current", stageLabel)
            } else {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.title.focused", stageLabel)
            },
            summary = buildSummary(state, workbenchState),
            checklist = buildChecklist(state, workbenchState),
        )
    }

    private fun buildSummary(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): String {
        if (state.status == WorkflowStatus.COMPLETED || workbenchState.focusedStage == StageId.ARCHIVE) {
            return SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.archive")
        }
        return when (workbenchState.focusedStage) {
            StageId.REQUIREMENTS -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.requirements")
            StageId.DESIGN -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.design")
            StageId.TASKS -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.tasks")
            StageId.IMPLEMENT -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.implement")
            StageId.VERIFY -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.verify")
            StageId.ARCHIVE -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.archive")
        }
    }

    private fun buildChecklist(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): List<String> {
        val items = mutableListOf<String>()
        when (state.gateStatus.takeIf { workbenchState.focusedStage == state.currentStage }) {
            GateStatus.ERROR -> items += SpecCodingBundle.message("spec.toolwindow.overview.checklist.gate.error")
            GateStatus.WARNING -> items += SpecCodingBundle.message("spec.toolwindow.overview.checklist.gate.warning")
            else -> Unit
        }
        items += stageChecklistItems(workbenchState.focusedStage)
        items += buildNextStepLine(state, workbenchState)
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

    private fun buildNextStepLine(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): String {
        val targetStage = workbenchState.primaryAction?.targetStage
        if (targetStage != null) {
            return SpecCodingBundle.message(
                "spec.toolwindow.overview.checklist.next",
                SpecWorkflowOverviewPresenter.stageLabel(targetStage),
            )
        }
        val nextStage = state.nextStage ?: return SpecCodingBundle.message("spec.toolwindow.overview.checklist.next.none")
        return if (workbenchState.focusedStage == state.currentStage) {
            SpecCodingBundle.message(
                "spec.toolwindow.overview.checklist.next",
                SpecWorkflowOverviewPresenter.stageLabel(nextStage),
            )
        } else {
            SpecCodingBundle.message(
                "spec.toolwindow.overview.checklist.next",
                SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage),
            )
        }
    }
}
