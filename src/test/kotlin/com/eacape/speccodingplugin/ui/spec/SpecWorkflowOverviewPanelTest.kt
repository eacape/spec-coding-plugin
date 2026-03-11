package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowOverviewPanelTest {

    @Test
    fun `updateOverview should render current stage gate summary and template controls`() {
        var advanceClicks = 0
        val panel = SpecWorkflowOverviewPanel(
            onAdvanceRequested = { advanceClicks += 1 },
        )

        panel.updateOverview(
            SpecWorkflowOverviewState(
                workflowId = "wf-42",
                title = "ToolWindow Demo",
                status = WorkflowStatus.IN_PROGRESS,
                template = WorkflowTemplate.FULL_SPEC,
                switchableTemplates = listOf(
                    WorkflowTemplate.QUICK_TASK,
                    WorkflowTemplate.DESIGN_REVIEW,
                    WorkflowTemplate.DIRECT_IMPLEMENT,
                ),
                latestTemplateSwitch = TemplateSwitchHistoryEntry(
                    switchId = "switch-1",
                    fromTemplate = WorkflowTemplate.FULL_SPEC,
                    toTemplate = WorkflowTemplate.QUICK_TASK,
                    occurredAt = "2026-03-11T10:00:00Z",
                    occurredAtEpochMs = 1_710_152_400_000,
                ),
                currentStage = StageId.TASKS,
                activeStages = listOf(
                    StageId.REQUIREMENTS,
                    StageId.DESIGN,
                    StageId.TASKS,
                    StageId.IMPLEMENT,
                    StageId.ARCHIVE,
                ),
                nextStage = StageId.IMPLEMENT,
                gateStatus = GateStatus.WARNING,
                gateSummary = "Gate requires warning confirmation: 1 warning(s) across 1 rule(s).",
                stageStepper = SpecWorkflowStageStepperState(
                    stages = listOf(
                        SpecWorkflowStageStepState(StageId.REQUIREMENTS, active = true, current = false, progress = StageProgress.DONE),
                        SpecWorkflowStageStepState(StageId.DESIGN, active = true, current = false, progress = StageProgress.DONE),
                        SpecWorkflowStageStepState(StageId.TASKS, active = true, current = true, progress = StageProgress.IN_PROGRESS),
                        SpecWorkflowStageStepState(StageId.IMPLEMENT, active = true, current = false, progress = StageProgress.NOT_STARTED),
                        SpecWorkflowStageStepState(StageId.VERIFY, active = false, current = false, progress = StageProgress.NOT_STARTED),
                        SpecWorkflowStageStepState(StageId.ARCHIVE, active = true, current = false, progress = StageProgress.NOT_STARTED),
                    ),
                    canAdvance = true,
                    jumpTargets = listOf(StageId.IMPLEMENT, StageId.ARCHIVE),
                    rollbackTargets = listOf(StageId.REQUIREMENTS, StageId.DESIGN),
                ),
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("ToolWindow Demo | wf-42", snapshot.getValue("workflow"))
        assertEquals(SpecCodingBundle.message("spec.workflow.status.inProgress"), snapshot.getValue("status"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.template.current", "Full Spec"),
            snapshot.getValue("template"),
        )
        assertTrue(snapshot.getValue("templateHistory").contains("Full Spec"))
        assertEquals("true", snapshot.getValue("templateSwitchEnabled"))
        assertEquals("true", snapshot.getValue("templateRollbackEnabled"))
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
        assertEquals("false", panel.snapshotForTest().getValue("templateSwitchEnabled"))
    }
}
