package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowOverviewPanelTest {

    @Test
    fun `updateOverview should render focused stage summary primary action and overflow actions`() {
        var selectedStage: StageId? = null
        var primaryActionKind: SpecWorkflowWorkbenchActionKind? = null
        val panel = SpecWorkflowOverviewPanel(
            onStageSelected = { selectedStage = it },
            onWorkbenchActionRequested = { action -> primaryActionKind = action.kind },
        )
        val overviewState = overviewState()
        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = overviewWorkflow(),
            overviewState = overviewState,
        )

        panel.updateOverview(overviewState, workbenchState)

        val snapshot = panel.snapshotForTest()
        assertEquals("ToolWindow Demo | wf-42", snapshot.getValue("workflow"))
        assertEquals(SpecCodingBundle.message("spec.workflow.status.inProgress"), snapshot.getValue("status"))
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.template.current",
                SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC),
            ),
            snapshot.getValue("template"),
        )
        assertTrue(snapshot.getValue("templateHistory").contains(SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC)))
        assertEquals("true", snapshot.getValue("templateSwitchEnabled"))
        assertEquals("", snapshot.getValue("templateSwitchText"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.template.switch"),
            snapshot.getValue("templateSwitchTooltip"),
        )
        assertEquals("true", snapshot.getValue("templateSwitchHasIcon"))
        assertEquals("true", snapshot.getValue("templateSwitchRolloverEnabled"))
        assertEquals("true", snapshot.getValue("templateRollbackEnabled"))
        assertEquals("", snapshot.getValue("templateRollbackText"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.template.rollback"),
            snapshot.getValue("templateRollbackTooltip"),
        )
        assertEquals("true", snapshot.getValue("templateRollbackHasIcon"))
        assertEquals("true", snapshot.getValue("templateRollbackRolloverEnabled"))
        assertEquals(SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS), snapshot.getValue("currentStage"))
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.progress.value",
                3,
                5,
                SpecWorkflowOverviewPresenter.progressLabel(StageProgress.IN_PROGRESS),
            ),
            snapshot.getValue("progress"),
        )
        assertTrue(snapshot.getValue("activeStages").contains(SpecWorkflowOverviewPresenter.stageLabel(StageId.REQUIREMENTS)))
        assertEquals(SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT), snapshot.getValue("nextStage"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.warning"), snapshot.getValue("gateStatus"))
        assertEquals("false", snapshot.getValue("gateStatusOpaque"))
        assertEquals("0,0,0,0", snapshot.getValue("gateStatusInsets"))
        assertEquals("0,0,0,0", snapshot.getValue("gateContainerInsets"))
        assertEquals(snapshot.getValue("gateSummaryFont"), snapshot.getValue("gateStatusFont"))
        assertEquals("true", snapshot.getValue("gateStatusBeforeSummary"))
        assertTrue(snapshot.getValue("gateSummary").contains("warning", ignoreCase = true))
        assertTrue(snapshot.getValue("stageFlow").contains(":current:focused"))
        assertEquals(StageId.TASKS.name, snapshot.getValue("focusedStage"))
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.title.current",
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
            ),
            snapshot.getValue("focusTitle"),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.tasks"),
            snapshot.getValue("focusSummary"),
        )
        assertTrue(snapshot.getValue("checklist").contains("tasks.md"))
        assertEquals("true", snapshot.getValue("primaryActionVisible"))
        assertEquals("true", snapshot.getValue("primaryActionEnabled"))
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.primary.advance",
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
            snapshot.getValue("primaryActionText"),
        )
        assertEquals("true", snapshot.getValue("overflowVisible"))
        assertEquals("true", snapshot.getValue("overflowEnabled"))
        assertTrue(snapshot.getValue("overflowActions").contains(SpecCodingBundle.message("spec.action.jump.text")))
        assertTrue(snapshot.getValue("overflowActions").contains(SpecCodingBundle.message("spec.action.rollback.text")))
        assertFalse(snapshot.getValue("refreshed").isBlank())
        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.empty"), snapshot.getValue("empty"))

        panel.clickPrimaryActionForTest()
        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, primaryActionKind)
        panel.clickStageForTest(StageId.IMPLEMENT)
        assertEquals(StageId.IMPLEMENT, selectedStage)
    }

    @Test
    fun `updateOverview should keep jump and rollback inside overflow when viewing another stage`() {
        val panel = SpecWorkflowOverviewPanel()
        val overviewState = overviewState()
        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = overviewWorkflow(),
            overviewState = overviewState,
            focusedStage = StageId.IMPLEMENT,
        )

        panel.updateOverview(overviewState, workbenchState)

        val snapshot = panel.snapshotForTest()
        assertEquals(StageId.IMPLEMENT.name, snapshot.getValue("focusedStage"))
        assertEquals("false", snapshot.getValue("primaryActionVisible"))
        assertTrue(
            snapshot.getValue("overflowActions").contains(
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.more.jumpTo",
                    SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                ),
            ),
        )
        assertTrue(
            snapshot.getValue("focusTitle").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
    }

    @Test
    fun `showLoading should expose loading message`() {
        val panel = SpecWorkflowOverviewPanel()

        panel.showLoading()

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.loading"),
            panel.snapshotForTest().getValue("empty"),
        )
        assertEquals("false", panel.snapshotForTest().getValue("primaryActionVisible"))
        assertEquals("false", panel.snapshotForTest().getValue("templateSwitchEnabled"))
    }

    private fun overviewState(): SpecWorkflowOverviewState {
        return SpecWorkflowOverviewState(
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
        )
    }

    private fun overviewWorkflow(): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-42",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "ToolWindow Demo",
            description = "Demo workflow",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = linkedMapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.DONE),
                StageId.DESIGN to StageState(active = true, status = StageProgress.DONE),
                StageId.TASKS to StageState(active = true, status = StageProgress.IN_PROGRESS),
                StageId.IMPLEMENT to StageState(active = true, status = StageProgress.NOT_STARTED),
                StageId.VERIFY to StageState(active = false, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to StageState(active = true, status = StageProgress.NOT_STARTED),
            ),
            currentStage = StageId.TASKS,
            verifyEnabled = false,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
