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
        var advanceClicks = 0
        val panel = SpecWorkflowOverviewPanel(
            onAdvanceRequested = { advanceClicks += 1 },
        )

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
                stageStepper = SpecWorkflowStageStepperState(
                    stages = listOf(
                        SpecWorkflowStageStepState(StageId.REQUIREMENTS, active = true, current = false, progress = com.eacape.speccodingplugin.spec.StageProgress.DONE),
                        SpecWorkflowStageStepState(StageId.DESIGN, active = true, current = false, progress = com.eacape.speccodingplugin.spec.StageProgress.DONE),
                        SpecWorkflowStageStepState(StageId.TASKS, active = true, current = true, progress = com.eacape.speccodingplugin.spec.StageProgress.IN_PROGRESS),
                        SpecWorkflowStageStepState(StageId.IMPLEMENT, active = true, current = false, progress = com.eacape.speccodingplugin.spec.StageProgress.NOT_STARTED),
                        SpecWorkflowStageStepState(StageId.VERIFY, active = false, current = false, progress = com.eacape.speccodingplugin.spec.StageProgress.NOT_STARTED),
                        SpecWorkflowStageStepState(StageId.ARCHIVE, active = true, current = false, progress = com.eacape.speccodingplugin.spec.StageProgress.NOT_STARTED),
                    ),
                    canAdvance = true,
                    jumpTargets = listOf(StageId.IMPLEMENT, StageId.ARCHIVE),
                    rollbackTargets = listOf(StageId.REQUIREMENTS, StageId.DESIGN),
                ),
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
        assertTrue(
            snapshot.getValue("stageFlow").contains(
                "Verify:${SpecCodingBundle.message("spec.toolwindow.stage.state.inactive")}",
            ),
        )
        assertEquals("true", snapshot.getValue("advanceEnabled"))
        assertEquals("true", snapshot.getValue("jumpEnabled"))
        assertEquals("true", snapshot.getValue("rollbackEnabled"))
        assertFalse(snapshot.getValue("refreshed").isBlank())
        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.empty"), snapshot.getValue("empty"))

        panel.clickAdvanceForTest()
        assertEquals(1, advanceClicks)
    }

    @Test
    fun `showLoading should expose loading message`() {
        val panel = SpecWorkflowOverviewPanel()

        panel.showLoading()

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.loading"),
            panel.snapshotForTest().getValue("empty"),
        )
        assertEquals("false", panel.snapshotForTest().getValue("advanceEnabled"))
    }
}
