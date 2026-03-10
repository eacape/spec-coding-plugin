package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageActivationOptions
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplates

internal data class SpecWorkflowOverviewState(
    val workflowId: String,
    val title: String,
    val status: WorkflowStatus,
    val currentStage: StageId,
    val activeStages: List<StageId>,
    val nextStage: StageId?,
    val gateStatus: GateStatus?,
    val gateSummary: String?,
    val refreshedAtMillis: Long,
)

internal object SpecWorkflowOverviewPresenter {
    fun buildState(
        workflow: SpecWorkflow,
        gatePreview: StageTransitionGatePreview?,
        refreshedAtMillis: Long,
    ): SpecWorkflowOverviewState {
        val stageOverrides = workflow.stageStates
            .mapValues { (_, state) -> state.active }
            .toMutableMap()
        if (workflow.verifyEnabled && !stageOverrides.containsKey(StageId.VERIFY)) {
            stageOverrides[StageId.VERIFY] = true
        }

        val activeStages = WorkflowTemplates.definitionOf(workflow.template)
            .buildStagePlan(StageActivationOptions(stageOverrides = stageOverrides))
            .activeStages

        return SpecWorkflowOverviewState(
            workflowId = workflow.id,
            title = workflow.title,
            status = workflow.status,
            currentStage = workflow.currentStage,
            activeStages = activeStages,
            nextStage = if (workflow.status == WorkflowStatus.COMPLETED) {
                null
            } else {
                gatePreview?.targetStage ?: activeStages.nextStageAfter(workflow.currentStage)
            },
            gateStatus = gatePreview?.gateResult?.status,
            gateSummary = gatePreview?.gateResult?.aggregation?.summary,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    fun stageLabel(stageId: StageId): String {
        return stageId.name
            .lowercase()
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar(Char::titlecase) }
    }

    private fun List<StageId>.nextStageAfter(currentStage: StageId): StageId? {
        val index = indexOf(currentStage)
        return if (index >= 0 && index + 1 < size) {
            this[index + 1]
        } else {
            null
        }
    }
}
