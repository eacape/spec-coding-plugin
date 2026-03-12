package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal enum class SpecWorkflowWorkbenchActionKind {
    ADVANCE,
    JUMP,
    ROLLBACK,
}

internal enum class SpecWorkflowWorkbenchDocumentMode {
    PREVIEW,
    READ_ONLY,
}

internal data class SpecWorkflowWorkbenchAction(
    val kind: SpecWorkflowWorkbenchActionKind,
    val label: String,
    val enabled: Boolean,
    val targetStage: StageId? = null,
)

internal data class SpecWorkflowStageProgressView(
    val stepIndex: Int,
    val totalSteps: Int,
    val stageStatus: StageProgress,
)

internal data class SpecWorkflowStageArtifactBinding(
    val stageId: StageId,
    val title: String,
    val fileName: String?,
    val documentPhase: SpecPhase?,
    val mode: SpecWorkflowWorkbenchDocumentMode,
    val fallbackEditable: Boolean,
)

internal data class SpecWorkflowStageWorkbenchState(
    val currentStage: StageId,
    val focusedStage: StageId,
    val progress: SpecWorkflowStageProgressView,
    val primaryAction: SpecWorkflowWorkbenchAction?,
    val overflowActions: List<SpecWorkflowWorkbenchAction>,
    val blockers: List<String>,
    val artifactBinding: SpecWorkflowStageArtifactBinding,
    val visibleSections: Set<SpecWorkflowWorkspaceSectionId>,
)

internal object SpecWorkflowStageWorkbenchBuilder {
    fun build(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId? = null,
    ): SpecWorkflowStageWorkbenchState {
        val orderedStages = overviewState.stageStepper.stages.map { it.stageId }
        val resolvedFocusedStage = focusedStage
            ?.takeIf { requestedStage -> requestedStage in orderedStages }
            ?: overviewState.currentStage
        val focusedStep = overviewState.stageStepper.stages
            .firstOrNull { it.stageId == resolvedFocusedStage }
            ?: overviewState.stageStepper.stages.firstOrNull()
            ?: SpecWorkflowStageStepState(
                stageId = overviewState.currentStage,
                active = true,
                current = true,
                progress = StageProgress.IN_PROGRESS,
            )
        val activeStages = overviewState.activeStages.ifEmpty { orderedStages }
        val resolvedStepIndex = activeStages.indexOf(resolvedFocusedStage)
            .takeIf { it >= 0 }
            ?: orderedStages.indexOf(resolvedFocusedStage).coerceAtLeast(0)

        return SpecWorkflowStageWorkbenchState(
            currentStage = overviewState.currentStage,
            focusedStage = resolvedFocusedStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = resolvedStepIndex + 1,
                totalSteps = activeStages.size.coerceAtLeast(1),
                stageStatus = focusedStep.progress,
            ),
            primaryAction = buildPrimaryAction(overviewState, resolvedFocusedStage),
            overflowActions = buildOverflowActions(overviewState),
            blockers = buildBlockers(overviewState, resolvedFocusedStage),
            artifactBinding = buildArtifactBinding(workflow, resolvedFocusedStage),
            visibleSections = SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = resolvedFocusedStage,
                status = overviewState.status,
            ),
        )
    }

    private fun buildPrimaryAction(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
    ): SpecWorkflowWorkbenchAction? {
        if (overviewState.status == WorkflowStatus.COMPLETED && focusedStage == StageId.ARCHIVE) {
            return null
        }
        return when {
            focusedStage == overviewState.currentStage -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
                label = SpecCodingBundle.message("spec.action.advance.text"),
                enabled = overviewState.stageStepper.canAdvance,
                targetStage = overviewState.nextStage,
            )

            focusedStage in overviewState.stageStepper.jumpTargets -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.JUMP,
                label = SpecCodingBundle.message("spec.action.jump.text"),
                enabled = true,
                targetStage = focusedStage,
            )

            focusedStage in overviewState.stageStepper.rollbackTargets -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.ROLLBACK,
                label = SpecCodingBundle.message("spec.action.rollback.text"),
                enabled = true,
                targetStage = focusedStage,
            )

            else -> null
        }
    }

    private fun buildOverflowActions(
        overviewState: SpecWorkflowOverviewState,
    ): List<SpecWorkflowWorkbenchAction> {
        return buildList {
            if (overviewState.stageStepper.jumpTargets.isNotEmpty()) {
                add(
                    SpecWorkflowWorkbenchAction(
                        kind = SpecWorkflowWorkbenchActionKind.JUMP,
                        label = SpecCodingBundle.message("spec.action.jump.text"),
                        enabled = true,
                    ),
                )
            }
            if (overviewState.stageStepper.rollbackTargets.isNotEmpty()) {
                add(
                    SpecWorkflowWorkbenchAction(
                        kind = SpecWorkflowWorkbenchActionKind.ROLLBACK,
                        label = SpecCodingBundle.message("spec.action.rollback.text"),
                        enabled = true,
                    ),
                )
            }
        }
    }

    private fun buildBlockers(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
    ): List<String> {
        if (focusedStage != overviewState.currentStage) {
            return emptyList()
        }
        return listOfNotNull(overviewState.gateSummary?.takeIf { it.isNotBlank() })
    }

    private fun buildArtifactBinding(
        workflow: SpecWorkflow,
        focusedStage: StageId,
    ): SpecWorkflowStageArtifactBinding {
        val documentPhase = when (focusedStage) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            -> SpecPhase.IMPLEMENT

            StageId.VERIFY,
            StageId.ARCHIVE,
            -> null
        }
        val fileName = when (focusedStage) {
            StageId.IMPLEMENT -> StageId.TASKS.artifactFileName
            StageId.ARCHIVE -> StageId.VERIFY.artifactFileName
            else -> focusedStage.artifactFileName
        }
        val mode = if (documentPhase != null) {
            SpecWorkflowWorkbenchDocumentMode.PREVIEW
        } else {
            SpecWorkflowWorkbenchDocumentMode.READ_ONLY
        }
        val title = when (focusedStage) {
            StageId.IMPLEMENT -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
            StageId.ARCHIVE -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
            else -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
        }

        return SpecWorkflowStageArtifactBinding(
            stageId = focusedStage,
            title = title,
            fileName = fileName,
            documentPhase = documentPhase,
            mode = mode,
            fallbackEditable = documentPhase != null && workflow.documents.containsKey(documentPhase),
        )
    }
}
