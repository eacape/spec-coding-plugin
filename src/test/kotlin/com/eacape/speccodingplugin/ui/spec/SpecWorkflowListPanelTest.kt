package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowListPanelTest {

    @Test
    fun `updateWorkflows should replace list items and selection can be set`() {
        val panel = SpecWorkflowListPanel(
            onWorkflowSelected = {},
            onCreateWorkflow = {},
            onEditWorkflow = {},
            onDeleteWorkflow = {},
        )

        panel.updateWorkflows(
            listOf(
                item(id = "wf-1", title = "Workflow 1", phase = SpecPhase.SPECIFY),
                item(id = "wf-2", title = "Workflow 2", phase = SpecPhase.DESIGN),
            )
        )

        assertEquals(2, panel.itemsForTest().size)
        assertEquals("Workflow 1", panel.itemsForTest()[0].title)
        assertEquals("Workflow 2", panel.itemsForTest()[1].title)

        panel.setSelectedWorkflow("wf-2")
        assertEquals("wf-2", panel.selectedWorkflowIdForTest())

        panel.setSelectedWorkflow(null)
        assertNull(panel.selectedWorkflowIdForTest())
    }

    @Test
    fun `toolbar buttons should trigger callbacks`() {
        var createCalls = 0
        val editedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()

        val panel = SpecWorkflowListPanel(
            onWorkflowSelected = {},
            onCreateWorkflow = { createCalls += 1 },
            onEditWorkflow = { editedIds += it },
            onDeleteWorkflow = { deletedIds += it },
        )

        panel.updateWorkflows(listOf(item(id = "wf-del", title = "To Delete", phase = SpecPhase.SPECIFY)))

        panel.clickNewForTest()
        assertEquals(1, createCalls)

        panel.setSelectedWorkflow("wf-del")
        panel.clickDeleteForTest()
        assertEquals(listOf("wf-del"), deletedIds)

        panel.clickEditForTest()
        assertEquals(listOf("wf-del"), editedIds)
    }

    private fun item(id: String, title: String, phase: SpecPhase): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = id,
            title = title,
            description = "desc",
            currentPhase = phase,
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
        )
    }
}
