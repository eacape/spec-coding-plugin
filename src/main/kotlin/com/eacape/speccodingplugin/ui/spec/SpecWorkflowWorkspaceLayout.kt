package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal enum class SpecWorkflowWorkspaceSectionId {
    OVERVIEW,
    TASKS,
    GATE,
    VERIFY,
    DOCUMENTS,
}

internal object SpecWorkflowWorkspaceLayout {
    fun defaultExpandedSections(
        currentStage: StageId,
        status: WorkflowStatus,
    ): Set<SpecWorkflowWorkspaceSectionId> {
        if (status == WorkflowStatus.COMPLETED || currentStage == StageId.ARCHIVE) {
            return linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            )
        }

        return when (currentStage) {
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            )

            StageId.TASKS,
            StageId.IMPLEMENT,
            -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            )

            StageId.VERIFY -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            )

            StageId.ARCHIVE -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            )
        }
    }
}
