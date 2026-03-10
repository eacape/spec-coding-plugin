package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowOverviewPanelTest {

    @Test
    fun `updateOverview should render current stage and gate summary`() {
        val panel = SpecWorkflowOverviewPanel()

        panel.updateOverview(
            SpecWorkflowOverviewState(
                workflowId = "wf-42",
                title = "ToolWindow Demo",
                status = com.eacape.speccodingplugin.spec.WorkflowStatus.IN_PROGRESS,
                currentStage = StageId.TASKS,
                activeStages = listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS, StageId.IMPLEMENT, StageId.ARCHIVE),
                nextStage = StageId.IMPLEMENT,
                gateStatus = GateStatus.WARNING,
                gateSummary = "Gate requires warning confirmation: 1 warning(s) across 1 rule(s).",
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("ToolWindow Demo · wf-42", snapshot.getValue("workflow"))
        assertEquals(SpecCodingBundle.message("spec.workflow.status.inProgress"), snapshot.getValue("status"))
        assertEquals("Tasks", snapshot.getValue("currentStage"))
        assertTrue(snapshot.getValue("activeStages").contains("Requirements"))
        assertEquals("Implement", snapshot.getValue("nextStage"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.warning"), snapshot.getValue("gateStatus"))
        assertTrue(snapshot.getValue("gateSummary").contains("warning", ignoreCase = true))
        assertFalse(snapshot.getValue("refreshed").isBlank())
        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.empty"), snapshot.getValue("empty"))
    }

    @Test
    fun `showLoading should expose loading message`() {
        val panel = SpecWorkflowOverviewPanel()

        panel.showLoading()

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.loading"),
            panel.snapshotForTest().getValue("empty"),
        )
    }
}
