package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SpecWorkflowPanelNavigationPlatformTest : BasePlatformTestCase() {

    fun `test workflow panel should default to list mode and return from detail mode`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Navigation Demo",
            description = "list to detail navigation",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest() && panel.isListModeForTest()
        }
        assertNull(panel.selectedWorkflowIdForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }
        assertTrue(panel.isBackButtonInlineForTest())
        waitUntil {
            panel.workspaceSummarySnapshotForTest().getValue("stageValue").isNotBlank()
        }

        val summary = panel.workspaceSummarySnapshotForTest()
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.overview.currentStage")}:", summary.getValue("stageTitle"))
        assertTrue(summary.getValue("stageValue").contains(SpecWorkflowOverviewPresenter.stageLabel(workflow.currentStage)))
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.section.gate")}:", summary.getValue("gateTitle"))
        assertFalse(summary.getValue("gateValue").isBlank())
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.tasks.title")}:", summary.getValue("tasksTitle"))
        assertEquals("0/0", summary.getValue("tasksValue"))
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.section.verify")}:", summary.getValue("verifyTitle"))
        assertFalse(summary.getValue("verifyValue").isBlank())

        val toolbar = panel.toolbarSnapshotForTest()
        assertEquals("", toolbar.getValue("back.text"))
        assertEquals("back", toolbar.getValue("back.iconId"))
        assertEquals("true", toolbar.getValue("back.focusable"))
        assertEquals("", toolbar.getValue("refresh.text"))
        assertEquals("refresh", toolbar.getValue("refresh.iconId"))
        assertEquals("true", toolbar.getValue("refresh.focusable"))
        assertEquals("", toolbar.getValue("delta.text"))
        assertEquals("history", toolbar.getValue("delta.iconId"))
        assertEquals("true", toolbar.getValue("delta.focusable"))
        assertEquals("", toolbar.getValue("codeGraph.text"))
        assertEquals("openToolWindow", toolbar.getValue("codeGraph.iconId"))
        assertEquals("true", toolbar.getValue("codeGraph.focusable"))
        assertEquals("", toolbar.getValue("archive.text"))
        assertEquals("save", toolbar.getValue("archive.iconId"))
        assertEquals("true", toolbar.getValue("archive.focusable"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickBackToListForTest()
        }
        waitUntil {
            panel.isListModeForTest() && panel.selectedWorkflowIdForTest() == null
        }
        assertEquals(workflow.id, panel.highlightedWorkflowIdForTest())
    }

    fun `test tool window selection event should still open detail view directly`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Selection Event",
            description = "external open",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
            .onSelectWorkflowRequested(workflow.id)

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }
    }

    fun `test requirements workflow should hide checks and verification sections until they are needed`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Section Visibility",
            description = "requirements stage visibility",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            panel.visibleWorkspaceSectionIdsForTest(),
        )
    }

    fun `test focused stage should drive workspace sections and document selection`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Focused Stage",
            description = "stage workbench focus",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.focusStageForTest(StageId.IMPLEMENT)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.IMPLEMENT
        }

        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            panel.visibleWorkspaceSectionIdsForTest(),
        )
        assertTrue(
            panel.workspaceSummarySnapshotForTest().getValue("stageValue").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
        assertTrue(
            panel.workspaceSummarySnapshotForTest().getValue("focusTitle").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
        assertEquals("IMPLEMENT", panel.selectedDocumentPhaseForTest())
    }

    fun `test verify focused stage should switch document preview to verification artifact`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Verify Preview",
            description = "stage artifact binding",
        ).getOrThrow()
        SpecArtifactService(project).writeArtifact(
            workflow.id,
            StageId.VERIFY,
            """
                # Verification Document

                ## Result
                conclusion: PASS
                summary: verification preview
            """.trimIndent(),
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.focusStageForTest(StageId.VERIFY)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.VERIFY &&
                panel.selectedDocumentPhaseForTest() == null &&
                panel.currentDocumentPreviewTextForTest().contains("verification preview")
        }

        assertEquals("verification.md", panel.currentDocumentMetaTextForTest())
        assertTrue(panel.currentDocumentPreviewTextForTest().contains("# Verification Document"))
    }

    fun `test clicking stage chip should sync overview progress primary action documents and workspace sections`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val artifactService = SpecArtifactService(project)
        val workflow = engine.createWorkflow(
            title = "Stage Sync",
            description = "task 65 stage click regression",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Wire stage workbench", TaskPriority.P1)
        artifactService.writeArtifact(
            workflow.id,
            StageId.VERIFY,
            """
                # Verification Document

                ## Result
                conclusion: PASS
                summary: verification preview
            """.trimIndent(),
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE &&
                panel.selectedDocumentPhaseForTest() == SpecPhase.IMPLEMENT.name
        }

        val tasksSnapshot = panel.overviewSnapshotForTest()
        val tasksProgress = tasksSnapshot.getValue("progress")
        assertEquals(StageId.TASKS.name, tasksSnapshot.getValue("focusedStage"))
        assertFalse(panel.visibleWorkspaceSectionIdsForTest().contains(SpecWorkflowWorkspaceSectionId.VERIFY))
        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, panel.currentPrimaryActionKindForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.VERIFY)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.VERIFY &&
                panel.selectedDocumentPhaseForTest() == null &&
                panel.currentDocumentPreviewTextForTest().contains("verification preview")
        }

        val verifySnapshot = panel.overviewSnapshotForTest()
        assertEquals(StageId.VERIFY.name, verifySnapshot.getValue("focusedStage"))
        assertTrue(verifySnapshot.getValue("focusTitle").contains(SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY)))
        assertNotEquals(tasksProgress, verifySnapshot.getValue("progress"))
        assertNull(panel.currentPrimaryActionKindForTest())
        assertTrue(panel.visibleWorkspaceSectionIdsForTest().contains(SpecWorkflowWorkspaceSectionId.VERIFY))
        assertEquals("verification.md", panel.currentDocumentMetaTextForTest())
    }

    fun `test implement workbench primary action should follow task lifecycle states`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Implement Action States",
            description = "task 65 implement states",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Ship stage workbench", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.START_TASK
        }

        assertEquals(task.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewPrimaryActionForTest()
        }

        waitUntil {
            panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.COMPLETE_TASK &&
                panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:IN_PROGRESS")
        }

        assertEquals(task.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))

        tasksService.updateRelatedFiles(workflow.id, task.id, listOf("src/main/kotlin/App.kt"))
        tasksService.transitionStatus(workflow.id, task.id, TaskStatus.COMPLETED)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
            includeTasksDocument = true,
        )

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE &&
                panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:COMPLETED")
        }

        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, panel.currentPrimaryActionKindForTest())
        assertEquals("true", panel.overviewSnapshotForTest().getValue("primaryActionEnabled"))
    }

    fun `test stage navigation should stay read only while current stage keeps the primary path`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Overflow Navigation",
            description = "task 65 overflow actions",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Protect primary path", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE
        }

        assertTrue(panel.currentOverflowActionKindsForTest().isEmpty())
        assertEquals("true", panel.overviewSnapshotForTest().getValue("primaryActionVisible"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.IMPLEMENT)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.IMPLEMENT
        }

        assertNull(panel.currentPrimaryActionKindForTest())
        assertTrue(panel.currentOverflowActionKindsForTest().isEmpty())
        assertEquals("false", panel.overviewSnapshotForTest().getValue("primaryActionVisible"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.TASKS)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.TASKS &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE
        }
    }

    fun `test clicking another stage should only change focused stage and keep persisted current stage`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Read Only Stage Navigation",
            description = "task 70 focused stage regression",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Keep stage read only", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.overviewSnapshotForTest().getValue("currentStage") ==
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS)
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.IMPLEMENT)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.IMPLEMENT &&
                panel.overviewSnapshotForTest().getValue("focusedStage") == StageId.IMPLEMENT.name
        }

        val persistedWorkflow = engine.reloadWorkflow(workflow.id).getOrThrow()
        val overviewSnapshot = panel.overviewSnapshotForTest()

        assertEquals(StageId.TASKS, persistedWorkflow.currentStage)
        assertEquals(
            SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
            overviewSnapshot.getValue("currentStage"),
        )
        assertEquals(StageId.IMPLEMENT.name, overviewSnapshot.getValue("focusedStage"))
        assertTrue(
            overviewSnapshot.getValue("focusTitle").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
        assertNull(panel.currentPrimaryActionKindForTest())
    }

    fun `test implement workbench should resume blocked task from spec page button`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Resume Blocked Task",
            description = "task 68 resume",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Finish dependency", TaskPriority.P0)
        val blockedTask = tasksService.addTask(
            workflow.id,
            "Resume blocked implementation",
            TaskPriority.P1,
            dependsOn = listOf("T-001"),
        )
        tasksService.updateRelatedFiles(workflow.id, "T-001", listOf("src/main/kotlin/Dependency.kt"))
        tasksService.transitionStatus(workflow.id, "T-001", TaskStatus.COMPLETED)
        tasksService.transitionStatus(workflow.id, blockedTask.id, TaskStatus.BLOCKED)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.RESUME_TASK
        }

        assertEquals(blockedTask.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))
        assertEquals("", panel.tasksSnapshotForTest().getValue("executeText"))
        assertEquals("refresh", panel.tasksSnapshotForTest().getValue("executeIconId"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewPrimaryActionForTest()
        }

        waitUntil {
            panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.COMPLETE_TASK &&
                panel.tasksSnapshotForTest().getValue("tasks").contains("${blockedTask.id}:IN_PROGRESS")
        }
    }

    fun `test execution run should derive in progress UI state without persisting task status`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val executionService = SpecTaskExecutionService.getInstance(project)
        val artifactService = SpecArtifactService(project)
        val workflow = engine.createWorkflow(
            title = "Execution Run Derived State",
            description = "task 76 derived in progress regression",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Drive UI from run state", TaskPriority.P0)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )
        executionService.createRun(
            workflowId = workflow.id,
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            summary = "Waiting for confirmation",
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:IN_PROGRESS:P0") &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.COMPLETE_TASK
        }

        val tasksSnapshot = panel.tasksSnapshotForTest()
        val persistedTasks = artifactService.readArtifact(workflow.id, StageId.TASKS).orEmpty()

        assertEquals(task.id, tasksSnapshot.getValue("selectedTaskId"))
        assertEquals("complete", tasksSnapshot.getValue("executeIconId"))
        assertEquals("true", tasksSnapshot.getValue("executeFocusable"))
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))
    }

    fun `test clarify then fill should reuse clarification ui and persist repair retry metadata`() {
        val engine = SpecEngine.getInstance(project)
        val workflow = engine.createWorkflow(
            title = "Requirements Clarify Repair",
            description = "task 86 clarify then fill",
        ).getOrThrow()
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(
                panel.startRequirementsClarifyThenFillForTest(
                    workflow.id,
                    listOf(RequirementsSectionId.USER_STORIES, RequirementsSectionId.ACCEPTANCE_CRITERIA),
                ),
            )
        }

        waitUntil {
            panel.isClarifyingForTest() && panel.focusedStageForTest() == StageId.REQUIREMENTS
        }

        val questionsText = panel.currentClarificationQuestionsTextForTest()
        val retryState = engine.reloadWorkflow(workflow.id).getOrThrow().clarificationRetryState

        assertTrue(questionsText.contains(SpecCodingBundle.message("spec.workflow.clarify.noQuestions")))
        assertTrue(
            questionsText.contains(
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.phase"),
            ),
        )
        assertEquals(ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR, retryState?.followUp)
        assertEquals(
            listOf(RequirementsSectionId.USER_STORIES, RequirementsSectionId.ACCEPTANCE_CRITERIA),
            retryState?.requirementsRepairSections,
        )
    }

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun stageWorkflow(
        workflowId: String,
        currentStage: StageId,
        verifyEnabled: Boolean,
        includeTasksDocument: Boolean,
    ) {
        val storage = SpecStorage.getInstance(project)
        val current = storage.loadWorkflow(workflowId).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = phaseForStage(currentStage),
                currentStage = currentStage,
                verifyEnabled = verifyEnabled,
                stageStates = buildStageStates(current.stageStates, currentStage, verifyEnabled),
                documents = buildDocuments(workflowId, includeTasksDocument),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
    }

    private fun buildDocuments(workflowId: String, includeTasksDocument: Boolean): Map<SpecPhase, SpecDocument> {
        if (!includeTasksDocument) {
            return emptyMap()
        }
        val tasksContent = SpecArtifactService(project).readArtifact(workflowId, StageId.TASKS)
            ?: return emptyMap()
        return mapOf(
            SpecPhase.IMPLEMENT to SpecDocument(
                id = "$workflowId-tasks",
                phase = SpecPhase.IMPLEMENT,
                content = tasksContent,
                metadata = SpecMetadata(
                    title = "tasks.md",
                    description = "Structured tasks for $workflowId",
                ),
            ),
        )
    }

    private fun buildStageStates(
        existing: Map<StageId, StageState>,
        currentStage: StageId,
        verifyEnabled: Boolean,
    ): Map<StageId, StageState> {
        val marker = "2026-03-13T00:00:00Z"
        return StageId.entries.associateWith { stageId ->
            val active = when (stageId) {
                StageId.VERIFY -> verifyEnabled
                else -> existing[stageId]?.active ?: true
            }
            when {
                !active -> StageState(active = false, status = StageProgress.NOT_STARTED)
                stageId.ordinal < currentStage.ordinal -> StageState(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = marker,
                    completedAt = marker,
                )

                stageId == currentStage -> StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = marker,
                )

                else -> StageState(active = true, status = StageProgress.NOT_STARTED)
            }
        }
    }

    private fun phaseForStage(stageId: StageId): SpecPhase {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> SpecPhase.IMPLEMENT
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }
}
