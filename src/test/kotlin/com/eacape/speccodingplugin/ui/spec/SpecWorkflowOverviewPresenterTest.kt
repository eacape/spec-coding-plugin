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
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `buildState should expose clone targets and locked template summary`() {
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
        assertEquals(state.switchableTemplates, state.templateCloneTargets)
        assertEquals(latestSwitch, state.latestTemplateSwitch)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.template.locked"),
            state.templateLockedSummary,
        )
    }

    @Test
    fun `workbench builder should default focus to current stage and expose current-stage action`() {
        val workflow = workflow(
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                ),
            ),
        )
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )
        val tasks = listOf(task())

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "tasks-warning",
                        severity = GateStatus.WARNING,
                        fileName = "tasks.md",
                        line = 12,
                        message = "Task metadata needs confirmation",
                    ),
                ),
            ),
        )

        assertEquals(StageId.TASKS, workbenchState.focusedStage)
        assertEquals(3, workbenchState.progress.stepIndex)
        assertEquals(5, workbenchState.progress.totalSteps)
        assertEquals(4, workbenchState.progress.completedCheckCount)
        assertEquals(4, workbenchState.progress.totalCheckCount)
        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, workbenchState.primaryAction?.kind)
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.primary.advance",
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
            workbenchState.primaryAction?.label,
        )
        assertTrue(workbenchState.primaryAction?.enabled == true)
        assertEquals(StageId.IMPLEMENT, workbenchState.primaryAction?.targetStage)
        assertEquals(2, workbenchState.overflowActions.size)
        assertTrue(workbenchState.blockers.isEmpty())
        assertEquals("tasks.md", workbenchState.artifactBinding.fileName)
        assertEquals("IMPLEMENT", workbenchState.artifactBinding.documentPhase?.name)
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            workbenchState.visibleSections,
        )
    }

    @Test
    fun `workbench builder should derive blockers from incomplete current-stage checks`() {
        val workflow = workflow()
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
        )

        assertEquals(StageId.TASKS, workbenchState.focusedStage)
        assertEquals(0, workbenchState.progress.completedCheckCount)
        assertEquals(4, workbenchState.progress.totalCheckCount)
        assertFalse(workbenchState.primaryAction?.enabled ?: true)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.document"),
            workbenchState.primaryAction?.disabledReason,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.document"),
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.gateUnavailable"),
            ),
            workbenchState.blockers,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.tasks.source"),
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.document.missing", "tasks.md"),
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.tasks.workspace"),
            ),
            workbenchState.focusDetails,
        )
    }

    @Test
    fun `workbench builder should surface gate violations as blockers when preview fails`() {
        val workflow = workflow(
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                ),
            ),
        )
        val gateResult = GateResult.fromViolations(
            listOf(
                Violation(
                    ruleId = "tasks-structure",
                    severity = GateStatus.ERROR,
                    fileName = "tasks.md",
                    line = 18,
                    message = "A task dependency points to a missing task.",
                ),
            ),
        )
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = StageTransitionGatePreview(
                workflowId = workflow.id,
                transitionType = StageTransitionType.ADVANCE,
                fromStage = StageId.TASKS,
                targetStage = StageId.IMPLEMENT,
                evaluatedStages = listOf(StageId.TASKS),
                gateResult = gateResult,
            ),
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            tasks = listOf(task(dependsOn = listOf("T-099"))),
            gateResult = gateResult,
        )

        assertEquals(2, workbenchState.progress.completedCheckCount)
        assertEquals(4, workbenchState.progress.totalCheckCount)
        assertFalse(workbenchState.primaryAction?.enabled ?: true)
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.blockers.gate.withLocation",
                    "tasks-structure",
                    "A task dependency points to a missing task.",
                    "tasks.md",
                    18,
                ),
            ),
            workbenchState.blockers,
        )
    }

    @Test
    fun `workbench builder should support focused future stages without changing workflow current stage`() {
        val workflow = workflow(
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                ),
            ),
        )
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            tasks = listOf(task()),
            focusedStage = StageId.IMPLEMENT,
        )

        assertEquals(StageId.TASKS, workbenchState.currentStage)
        assertEquals(StageId.IMPLEMENT, workbenchState.focusedStage)
        assertNull(workbenchState.primaryAction)
        assertEquals(SpecWorkflowWorkbenchActionKind.JUMP, workbenchState.overflowActions.first().kind)
        assertEquals(StageId.IMPLEMENT, workbenchState.overflowActions.first().targetStage)
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.more.jumpTo",
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
            workbenchState.overflowActions.first().label,
        )
        assertEquals("tasks.md", workbenchState.artifactBinding.fileName)
        assertEquals("IMPLEMENT", workbenchState.artifactBinding.documentPhase?.name)
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            workbenchState.visibleSections,
        )
    }

    @Test
    fun `workbench builder should surface start-task action during implement stage when no task is in progress`() {
        val workflow = workflow(currentStage = StageId.IMPLEMENT)
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            tasks = listOf(
                task(id = "T-001", status = TaskStatus.COMPLETED),
                task(id = "T-002", status = TaskStatus.PENDING, dependsOn = listOf("T-001")),
            ),
        )

        assertEquals(StageId.IMPLEMENT, workbenchState.currentStage)
        assertEquals(StageId.IMPLEMENT, workbenchState.focusedStage)
        assertEquals(SpecWorkflowWorkbenchActionKind.START_TASK, workbenchState.primaryAction?.kind)
        assertEquals("T-002", workbenchState.primaryAction?.taskId)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.primary.implement.startTask", "T-002"),
            workbenchState.primaryAction?.label,
        )
        assertEquals("T-002", workbenchState.implementationFocus?.taskId)
        assertEquals(TaskStatus.PENDING, workbenchState.implementationFocus?.status)
        assertTrue(workbenchState.primaryAction?.enabled == true)
    }

    @Test
    fun `workbench builder should surface complete-task action during implement stage when a task is in progress`() {
        val workflow = workflow(currentStage = StageId.IMPLEMENT)
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            tasks = listOf(
                task(id = "T-001", status = TaskStatus.IN_PROGRESS),
                task(id = "T-002", status = TaskStatus.PENDING),
            ),
        )

        assertEquals(SpecWorkflowWorkbenchActionKind.COMPLETE_TASK, workbenchState.primaryAction?.kind)
        assertEquals("T-001", workbenchState.primaryAction?.taskId)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.primary.implement.completeTask", "T-001"),
            workbenchState.primaryAction?.label,
        )
        assertEquals("T-001", workbenchState.implementationFocus?.taskId)
        assertEquals(TaskStatus.IN_PROGRESS, workbenchState.implementationFocus?.status)
        assertTrue(workbenchState.primaryAction?.enabled == true)
    }

    @Test
    fun `workbench builder should surface continue-check action during implement stage after tasks are settled`() {
        val workflow = workflow(currentStage = StageId.IMPLEMENT, verifyEnabled = true)
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            tasks = listOf(
                task(id = "T-001", status = TaskStatus.COMPLETED, relatedFiles = listOf("src/main/kotlin/App.kt")),
                task(id = "T-002", status = TaskStatus.COMPLETED, relatedFiles = listOf("src/test/kotlin/AppTest.kt")),
            ),
        )

        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, workbenchState.primaryAction?.kind)
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.overview.primary.implement.continueCheck.withTarget",
                SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
            ),
            workbenchState.primaryAction?.label,
        )
        assertNull(workbenchState.primaryAction?.taskId)
        assertNull(workbenchState.implementationFocus)
        assertTrue(workbenchState.primaryAction?.enabled == true)
    }

    @Test
    fun `workbench builder should surface verify focus details and actions`() {
        val workflow = workflow(currentStage = StageId.VERIFY, verifyEnabled = true)
        val overviewState = SpecWorkflowOverviewPresenter.buildState(
            workflow = workflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val workbenchState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = workflow,
            overviewState = overviewState,
            verifyDeltaState = SpecWorkflowVerifyDeltaState(
                workflowId = workflow.id,
                verifyEnabled = true,
                verificationDocumentAvailable = false,
                verificationHistory = emptyList(),
                baselineChoices = listOf(
                    SpecWorkflowReferenceBaselineChoice(
                        workflowId = "wf-base",
                        title = "Baseline",
                    ),
                ),
                deltaSummary = "Baseline: wf-base | Current: wf-toolwindow | Added: 1, Modified: 0, Removed: 0, Unchanged: 3",
                preferredBaselineChoiceId = "workflow:wf-base",
                canPinBaseline = true,
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        assertEquals(StageId.VERIFY, workbenchState.currentStage)
        assertEquals(StageId.VERIFY, workbenchState.focusedStage)
        assertEquals(SpecWorkflowWorkbenchActionKind.RUN_VERIFY, workbenchState.primaryAction?.kind)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.primary.verify.run"),
            workbenchState.primaryAction?.label,
        )
        assertTrue(
            workbenchState.overflowActions.any { action ->
                action.kind == SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN
            },
        )
        assertTrue(
            workbenchState.overflowActions.any { action ->
                action.kind == SpecWorkflowWorkbenchActionKind.SHOW_DELTA
            },
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.verify.plan.ready"),
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.verify.latest.none"),
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.detail.delta.available",
                    "Baseline: wf-base | Current: wf-toolwindow | Added: 1, Modified: 0, Removed: 0, Unchanged: 3",
                ),
            ),
            workbenchState.focusDetails,
        )
    }

    @Test
    fun `workbench builder should expose archive completion then archive action`() {
        val archiveWorkflow = workflow(currentStage = StageId.ARCHIVE, verifyEnabled = true)
        val archiveOverview = SpecWorkflowOverviewPresenter.buildState(
            workflow = archiveWorkflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )
        val verifyState = SpecWorkflowVerifyDeltaState(
            workflowId = archiveWorkflow.id,
            verifyEnabled = true,
            verificationDocumentAvailable = true,
            verificationHistory = listOf(
                VerifyRunHistoryEntry(
                    runId = "verify-run-1",
                    planId = "verify-plan-1",
                    executedAt = "2026-03-12T10:00:00Z",
                    occurredAtEpochMs = 1_710_000_000_000,
                    currentStage = StageId.VERIFY,
                    conclusion = VerificationConclusion.PASS,
                    summary = "Verification passed with archived evidence.",
                    commandCount = 2,
                ),
            ),
            baselineChoices = listOf(
                SpecWorkflowReferenceBaselineChoice(
                    workflowId = "wf-base",
                    title = "Baseline",
                ),
            ),
            deltaSummary = "Baseline: wf-base | Current: wf-toolwindow | Added: 0, Modified: 2, Removed: 0, Unchanged: 2",
            preferredBaselineChoiceId = "workflow:wf-base",
            canPinBaseline = true,
            refreshedAtMillis = 1_710_000_000_000,
        )

        val inProgressArchiveState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = archiveWorkflow,
            overviewState = archiveOverview,
            verifyDeltaState = verifyState,
        )
        assertEquals(SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW, inProgressArchiveState.primaryAction?.kind)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.primary.archive.complete"),
            inProgressArchiveState.primaryAction?.label,
        )
        assertTrue(
            inProgressArchiveState.focusDetails.any { detail ->
                detail == SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.completeFirst")
            },
        )

        val completedWorkflow = archiveWorkflow.copy(status = WorkflowStatus.COMPLETED)
        val completedOverview = SpecWorkflowOverviewPresenter.buildState(
            workflow = completedWorkflow,
            gatePreview = null,
            latestTemplateSwitch = null,
            refreshedAtMillis = 1_710_000_000_000,
        )
        val completedArchiveState = SpecWorkflowStageWorkbenchBuilder.build(
            workflow = completedWorkflow,
            overviewState = completedOverview,
            verifyDeltaState = verifyState,
            focusedStage = StageId.ARCHIVE,
        )

        assertEquals(SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW, completedArchiveState.primaryAction?.kind)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.primary.archive"),
            completedArchiveState.primaryAction?.label,
        )
        assertTrue(
            completedArchiveState.overflowActions.any { action ->
                action.kind == SpecWorkflowWorkbenchActionKind.SHOW_DELTA
            },
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.ready"),
            completedArchiveState.focusDetails.first(),
        )
    }

    private fun workflow(
        status: WorkflowStatus = WorkflowStatus.IN_PROGRESS,
        verifyEnabled: Boolean = false,
        documents: Map<SpecPhase, SpecDocument> = emptyMap(),
        currentStage: StageId = StageId.TASKS,
    ): SpecWorkflow {
        val orderedStages = listOf(
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
            StageId.IMPLEMENT,
        ) + listOfNotNull(StageId.VERIFY.takeIf { verifyEnabled }) + listOf(StageId.ARCHIVE)
        val currentStageIndex = orderedStages.indexOf(currentStage).coerceAtLeast(0)
        return SpecWorkflow(
            id = "wf-toolwindow",
            currentPhase = when (currentStage) {
                StageId.REQUIREMENTS -> SpecPhase.SPECIFY
                StageId.DESIGN -> SpecPhase.DESIGN
                else -> SpecPhase.IMPLEMENT
            },
            documents = documents,
            status = status,
            title = "ToolWindow Demo",
            description = "Demo workflow",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = linkedMapOf(
                StageId.REQUIREMENTS to stageState(StageId.REQUIREMENTS, currentStageIndex, orderedStages),
                StageId.DESIGN to stageState(StageId.DESIGN, currentStageIndex, orderedStages),
                StageId.TASKS to stageState(StageId.TASKS, currentStageIndex, orderedStages),
                StageId.IMPLEMENT to stageState(StageId.IMPLEMENT, currentStageIndex, orderedStages),
                StageId.VERIFY to if (verifyEnabled) {
                    stageState(StageId.VERIFY, currentStageIndex, orderedStages)
                } else {
                    StageState(active = false, status = StageProgress.NOT_STARTED)
                },
                StageId.ARCHIVE to stageState(StageId.ARCHIVE, currentStageIndex, orderedStages),
            ),
            currentStage = currentStage,
            verifyEnabled = verifyEnabled,
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
        dependsOn: List<String> = emptyList(),
        status: TaskStatus = TaskStatus.PENDING,
        relatedFiles: List<String> = emptyList(),
    ): StructuredTask {
        return StructuredTask(
            id = id,
            title = "Task $id",
            status = status,
            priority = TaskPriority.P1,
            dependsOn = dependsOn,
            relatedFiles = relatedFiles,
        )
    }

    private fun stageState(
        stageId: StageId,
        currentStageIndex: Int,
        orderedStages: List<StageId>,
    ): StageState {
        val stageIndex = orderedStages.indexOf(stageId)
        val status = when {
            stageIndex < 0 -> StageProgress.NOT_STARTED
            stageIndex < currentStageIndex -> StageProgress.DONE
            stageIndex == currentStageIndex -> StageProgress.IN_PROGRESS
            else -> StageProgress.NOT_STARTED
        }
        return StageState(active = true, status = status)
    }
}
