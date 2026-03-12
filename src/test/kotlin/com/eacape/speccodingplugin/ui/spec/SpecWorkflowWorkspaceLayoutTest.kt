package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowWorkspaceLayoutTest {

    @Test
    fun `requirements stage should focus overview and documents`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.REQUIREMENTS,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `tasks stage should focus tasks and documents`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.TASKS,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `verify stage should focus gate and verify modules`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.VERIFY,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `completed workflow should keep only overview and verify expanded`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.ARCHIVE,
                status = WorkflowStatus.COMPLETED,
            ),
        )
    }
}
