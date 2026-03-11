package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowOverviewPresenterTest {

    @Test
    fun `buildState should expose active stages and previewed advance gate`() {
        val workflow = workflow()
        val gatePreview = StageTransitionGatePreview(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.TASKS,
            targetStage = StageId.IMPLEMENT,
            evaluatedStages = listOf(StageId.TASKS),
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "tasks-metadata",
                        severity = GateStatus.WARNING,
                        fileName = "tasks.md",
                        line = 12,
                        message = "Task metadata is incomplete",
                    ),
                ),
            ),
        )

        val state = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = gatePreview,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertEquals(listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS, StageId.IMPLEMENT, StageId.ARCHIVE), state.activeStages)
        assertEquals(StageId.IMPLEMENT, state.nextStage)
        assertEquals(GateStatus.WARNING, state.gateStatus)
        assertTrue(state.gateSummary.orEmpty().contains("warning", ignoreCase = true))
        assertTrue(state.stageStepper.canAdvance)
        assertEquals(listOf(StageId.IMPLEMENT, StageId.ARCHIVE), state.stageStepper.jumpTargets)
        assertEquals(listOf(StageId.REQUIREMENTS, StageId.DESIGN), state.stageStepper.rollbackTargets)
    }

    @Test
    fun `buildState should drop next stage for completed workflows`() {
        val workflow = workflow(status = WorkflowStatus.COMPLETED)

        val state = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertNull(state.nextStage)
        assertNull(state.gateStatus)
        assertNull(state.gateSummary)
    }

    @Test
    fun `buildState should include optional verify stage when enabled`() {
        val workflow = workflow(verifyEnabled = true)

        val state = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(state.activeStages.contains(StageId.VERIFY))
        assertTrue(state.stageStepper.stages.first { it.stageId == StageId.VERIFY }.active)
    }

    @Test
    fun `buildState should keep inactive optional stages in stepper`() {
        val workflow = workflow(verifyEnabled = false)

        val state = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertEquals(
            listOf(
                StageId.REQUIREMENTS,
                StageId.DESIGN,
                StageId.TASKS,
                StageId.IMPLEMENT,
                StageId.VERIFY,
                StageId.ARCHIVE,
            ),
            state.stageStepper.stages.map { it.stageId },
        )
        assertTrue(state.stageStepper.stages.first { it.stageId == StageId.VERIFY }.active.not())
    }

    @Test
    fun `buildState should expose switchable templates and latest template switch`() {
        val workflow = workflow()
        val latestSwitch = TemplateSwitchHistoryEntry(
            switchId = "switch-1",
            fromTemplate = WorkflowTemplate.FULL_SPEC,
            toTemplate = WorkflowTemplate.QUICK_TASK,
            occurredAt = "2026-03-11T10:00:00Z",
            occurredAtEpochMs = 1_710_152_400_000,
            rolledBack = false,
        )

        val state = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = latestSwitch,
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertEquals(WorkflowTemplate.FULL_SPEC, state.template)
        assertEquals(
            listOf(
                WorkflowTemplate.QUICK_TASK,
                WorkflowTemplate.DESIGN_REVIEW,
                WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
            state.switchableTemplates,
        )
        assertEquals(latestSwitch, state.latestTemplateSwitch)
    }

    private fun workflow(
        status: WorkflowStatus = WorkflowStatus.IN_PROGRESS,
        verifyEnabled: Boolean = false,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-toolwindow",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = status,
            title = "ToolWindow Demo",
            description = "Demo workflow",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = linkedMapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.DONE),
                StageId.DESIGN to StageState(active = true, status = StageProgress.DONE),
                StageId.TASKS to StageState(active = true, status = StageProgress.IN_PROGRESS),
                StageId.IMPLEMENT to StageState(active = true, status = StageProgress.NOT_STARTED),
                StageId.VERIFY to StageState(active = verifyEnabled, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to StageState(active = true, status = StageProgress.NOT_STARTED),
            ),
            currentStage = StageId.TASKS,
            verifyEnabled = verifyEnabled,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
