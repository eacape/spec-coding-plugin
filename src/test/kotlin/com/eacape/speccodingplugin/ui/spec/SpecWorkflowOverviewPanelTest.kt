package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.Violation
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
        val gateResult = GateResult.fromViolations(
            listOf(
                Violation(
                    ruleId = "tasks-warning",
                    severity = GateStatus.WARNING,
                    fileName = "tasks.md",
                    line = 8,
                    message = "Task metadata is incomplete",
                ),
            ),
        )
        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = overviewWorkflow(
                documents = mapOf(
                    SpecPhase.IMPLEMENT to document(
                        phase = SpecPhase.IMPLEMENT,
                        content = "tasks content",
                    ),
                ),
            ),
            overviewState = overviewState,
            tasks = listOf(task()),
            gateResult = gateResult,
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
                4,
                4,
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
        assertEquals("", snapshot.getValue("blockers"))
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
    fun `updateOverview should render blockers and disable primary action when stage checks are incomplete`() {
        val panel = SpecWorkflowOverviewPanel()
        val overviewState = overviewState(gateStatus = null, gateSummary = null)
        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = overviewWorkflow(),
            overviewState = overviewState,
        )

        panel.updateOverview(overviewState, workbenchState)

        val snapshot = panel.snapshotForTest()
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.progress.value",
                0,
                4,
                3,
                5,
                SpecWorkflowOverviewPresenter.progressLabel(StageProgress.IN_PROGRESS),
            ),
            snapshot.getValue("progress"),
        )
        assertTrue(snapshot.getValue("blockers").contains(SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.document")))
        assertTrue(snapshot.getValue("blockers").contains(SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.gateUnavailable")))
        assertEquals("true", snapshot.getValue("primaryActionVisible"))
        assertEquals("false", snapshot.getValue("primaryActionEnabled"))
    }

    @Test
    fun `updateOverview should keep jump and rollback inside overflow when viewing another stage`() {
        val panel = SpecWorkflowOverviewPanel()
        val overviewState = overviewState()
        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = overviewWorkflow(
                documents = mapOf(
                    SpecPhase.IMPLEMENT to document(
                        phase = SpecPhase.IMPLEMENT,
                        content = "tasks content",
                    ),
                ),
            ),
            overviewState = overviewState,
            tasks = listOf(task()),
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

    @Test
    fun `updateOverview should render task-driven implement primary action`() {
        var actionKind: SpecWorkflowWorkbenchActionKind? = null
        val panel = SpecWorkflowOverviewPanel(
            onWorkbenchActionRequested = { action -> actionKind = action.kind },
        )
        val overviewState = overviewState(
            currentStage = StageId.IMPLEMENT,
            nextStage = StageId.ARCHIVE,
            gateStatus = null,
            gateSummary = null,
        )
        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = overviewWorkflow(documents = emptyMap(), currentStage = StageId.IMPLEMENT),
            overviewState = overviewState,
            tasks = listOf(
                task(id = "T-001", status = TaskStatus.IN_PROGRESS),
                task(id = "T-002", status = TaskStatus.PENDING),
            ),
            gateResult = null,
        )

        panel.updateOverview(overviewState, workbenchState)

        val snapshot = panel.snapshotForTest()
        assertEquals("true", snapshot.getValue("primaryActionVisible"))
        assertEquals("true", snapshot.getValue("primaryActionEnabled"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.primary.implement.completeTask", "T-001"),
            snapshot.getValue("primaryActionText"),
        )
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.summary.implement.inProgress",
                "T-001",
                "Task T-001",
            ),
            snapshot.getValue("focusSummary"),
        )
        assertEquals(StageId.IMPLEMENT.name, snapshot.getValue("focusedStage"))

        panel.clickPrimaryActionForTest()
        assertEquals(SpecWorkflowWorkbenchActionKind.COMPLETE_TASK, actionKind)
    }

    private fun overviewState(
        currentStage: StageId = StageId.TASKS,
        nextStage: StageId? = StageId.IMPLEMENT,
        gateStatus: GateStatus? = GateStatus.WARNING,
        gateSummary: String? = "Gate requires warning confirmation: 1 warning(s) across 1 rule(s).",
    ): SpecWorkflowOverviewState {
        val stageOrder = listOf(
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.ARCHIVE,
        )
        val currentStageIndex = stageOrder.indexOf(currentStage).coerceAtLeast(0)
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
            currentStage = currentStage,
            activeStages = listOf(
                StageId.REQUIREMENTS,
                StageId.DESIGN,
                StageId.TASKS,
                StageId.IMPLEMENT,
                StageId.ARCHIVE,
            ),
            nextStage = nextStage,
            gateStatus = gateStatus,
            gateSummary = gateSummary,
            stageStepper = SpecWorkflowStageStepperState(
                stages = listOf(
                    SpecWorkflowStageStepState(
                        StageId.REQUIREMENTS,
                        active = true,
                        current = currentStage == StageId.REQUIREMENTS,
                        progress = progressFor(StageId.REQUIREMENTS, currentStageIndex, stageOrder),
                    ),
                    SpecWorkflowStageStepState(
                        StageId.DESIGN,
                        active = true,
                        current = currentStage == StageId.DESIGN,
                        progress = progressFor(StageId.DESIGN, currentStageIndex, stageOrder),
                    ),
                    SpecWorkflowStageStepState(
                        StageId.TASKS,
                        active = true,
                        current = currentStage == StageId.TASKS,
                        progress = progressFor(StageId.TASKS, currentStageIndex, stageOrder),
                    ),
                    SpecWorkflowStageStepState(
                        StageId.IMPLEMENT,
                        active = true,
                        current = currentStage == StageId.IMPLEMENT,
                        progress = progressFor(StageId.IMPLEMENT, currentStageIndex, stageOrder),
                    ),
                    SpecWorkflowStageStepState(StageId.VERIFY, active = false, current = false, progress = StageProgress.NOT_STARTED),
                    SpecWorkflowStageStepState(
                        StageId.ARCHIVE,
                        active = true,
                        current = currentStage == StageId.ARCHIVE,
                        progress = progressFor(StageId.ARCHIVE, currentStageIndex, stageOrder),
                    ),
                ),
                canAdvance = true,
                jumpTargets = stageOrder.filterIndexed { index, _ -> index > currentStageIndex },
                rollbackTargets = stageOrder.filterIndexed { index, _ -> index < currentStageIndex },
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
    }

    private fun overviewWorkflow(): SpecWorkflow {
        return overviewWorkflow(documents = emptyMap())
    }

    private fun overviewWorkflow(
        documents: Map<SpecPhase, SpecDocument>,
        currentStage: StageId = StageId.TASKS,
    ): SpecWorkflow {
        val orderedStages = listOf(
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.ARCHIVE,
        )
        val currentStageIndex = orderedStages.indexOf(currentStage).coerceAtLeast(0)
        return SpecWorkflow(
            id = "wf-42",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = documents,
            status = WorkflowStatus.IN_PROGRESS,
            title = "ToolWindow Demo",
            description = "Demo workflow",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = linkedMapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = progressFor(StageId.REQUIREMENTS, currentStageIndex, orderedStages)),
                StageId.DESIGN to StageState(active = true, status = progressFor(StageId.DESIGN, currentStageIndex, orderedStages)),
                StageId.TASKS to StageState(active = true, status = progressFor(StageId.TASKS, currentStageIndex, orderedStages)),
                StageId.IMPLEMENT to StageState(active = true, status = progressFor(StageId.IMPLEMENT, currentStageIndex, orderedStages)),
                StageId.VERIFY to StageState(active = false, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to StageState(active = true, status = progressFor(StageId.ARCHIVE, currentStageIndex, orderedStages)),
            ),
            currentStage = currentStage,
            verifyEnabled = false,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun document(
        phase: SpecPhase,
        content: String,
        valid: Boolean = true,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "test",
            ),
            validationResult = ValidationResult(valid = valid),
        )
    }

    private fun task(
        id: String = "T-001",
        status: TaskStatus = TaskStatus.PENDING,
    ): StructuredTask {
        return StructuredTask(
            id = id,
            title = "Task $id",
            status = status,
            priority = TaskPriority.P1,
        )
    }

    private fun progressFor(
        stageId: StageId,
        currentStageIndex: Int,
        orderedStages: List<StageId>,
    ): StageProgress {
        val stageIndex = orderedStages.indexOf(stageId)
        return when {
            stageIndex < 0 -> StageProgress.NOT_STARTED
            stageIndex < currentStageIndex -> StageProgress.DONE
            stageIndex == currentStageIndex -> StageProgress.IN_PROGRESS
            else -> StageProgress.NOT_STARTED
        }
    }
}
