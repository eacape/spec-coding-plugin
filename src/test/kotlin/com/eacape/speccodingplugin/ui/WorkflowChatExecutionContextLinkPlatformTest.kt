package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.llm.ModelCapability
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class WorkflowChatExecutionContextLinkPlatformTest : BasePlatformTestCase() {

    fun `test task execution should switch to chat session and clear temporary task chip after terminal run`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Execution Link",
            description = "task 138 execution session link",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Link spec task execution to workflow chat",
            priority = TaskPriority.P0,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val modelId = "task-138-${System.nanoTime()}"
        ModelRegistry.getInstance().register(
            ModelInfo(
                id = modelId,
                name = "Task 138 Mock Model",
                provider = MockLlmProvider.ID,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)
        val executionService = SpecTaskExecutionService.getInstance(project)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.selectToolbarModelForTest(MockLlmProvider.ID, modelId)
            assertTrue(specPanel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil(timeoutMs = 10_000) {
            ChatToolWindowFactory.isChatContent(toolWindow.contentManager.selectedContent) &&
                chatPanel.workflowBindingSnapshotForTest().getValue("sessionId").isNotBlank() &&
                chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "true" &&
                chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "true"
        }

        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, task.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val runId = executionService.listRuns(workflow.id, task.id).first().runId
        val bindingSnapshot = chatPanel.workflowBindingSnapshotForTest()
        val liveSnapshot = chatPanel.liveTaskExecutionSnapshotForTest()
        assertEquals(workflow.id, bindingSnapshot.getValue("workflowId"))
        assertEquals(task.id, bindingSnapshot.getValue("taskId"))
        assertEquals("true", bindingSnapshot.getValue("taskChipVisible"))
        assertEquals(runId, liveSnapshot.getValue("runId"))
        assertEquals(task.id, liveSnapshot.getValue("taskId"))
        assertEquals("true", liveSnapshot.getValue("visible"))
        assertTrue(liveSnapshot.getValue("eventCount").toInt() >= 1)

        executionService.resolveWaitingConfirmationRun(
            workflowId = workflow.id,
            taskId = task.id,
            summary = "Completed from task 138 platform test.",
        )

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "false" &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId").isBlank()
        }

        val clearedSnapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(workflow.id, clearedSnapshot.getValue("workflowId"))
        assertEquals("", clearedSnapshot.getValue("taskId"))
        assertEquals("false", clearedSnapshot.getValue("taskChipVisible"))
    }

    fun `test sequential task executions should reuse same workflow chat session`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Session Reuse",
            description = "task 140 reuse workflow chat session",
        ).getOrThrow()
        val firstTask = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Implement session reuse entry",
            priority = TaskPriority.P0,
        )
        val secondTask = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Implement follow-up in same chat",
            priority = TaskPriority.P1,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val modelId = "task-140-${System.nanoTime()}"
        ModelRegistry.getInstance().register(
            ModelInfo(
                id = modelId,
                name = "Task 140 Mock Model",
                provider = MockLlmProvider.ID,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)
        val executionService = SpecTaskExecutionService.getInstance(project)
        val sessionManager = SessionManager.getInstance(project)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(firstTask.id) &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(secondTask.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.selectToolbarModelForTest(MockLlmProvider.ID, modelId)
            assertTrue(specPanel.requestExecutionForTaskForTest(firstTask.id))
        }

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == firstTask.id
        }
        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, firstTask.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val firstSessionId = chatPanel.workflowBindingSnapshotForTest().getValue("sessionId")
        assertTrue(firstSessionId.isNotBlank())
        assertEquals(1, sessionManager.listSessions().size)

        executionService.resolveWaitingConfirmationRun(
            workflowId = workflow.id,
            taskId = firstTask.id,
            summary = "Complete first task before starting second.",
        )

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "false"
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.requestExecutionForTaskForTest(secondTask.id))
        }

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == secondTask.id
        }
        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, secondTask.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val secondSnapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(firstSessionId, secondSnapshot.getValue("sessionId"))
        assertEquals(secondTask.id, secondSnapshot.getValue("taskId"))
        assertEquals("true", secondSnapshot.getValue("taskChipVisible"))
        assertEquals(1, sessionManager.listSessions().size)
    }

    private fun registerSpecCodeToolWindow(): ToolWindow {
        var toolWindow: ToolWindow? = null
        ApplicationManager.getApplication().invokeAndWait {
            toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(
                RegisterToolWindowTask.notClosable(
                    ChatToolWindowFactory.TOOL_WINDOW_ID,
                    ToolWindowAnchor.RIGHT,
                ),
            )
        }
        return toolWindow ?: error("Failed to register test tool window")
    }

    private fun currentChatPanel(toolWindow: ToolWindow): ImprovedChatPanel {
        return toolWindow.contentManager.contents
            .first(ChatToolWindowFactory::isChatContent)
            .component as ImprovedChatPanel
    }

    private fun currentSpecPanel(toolWindow: ToolWindow): SpecWorkflowPanel {
        return toolWindow.contentManager.contents
            .first(ChatToolWindowFactory::isSpecContent)
            .component as SpecWorkflowPanel
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
        val marker = "2026-03-18T00:00:00Z"
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
            StageId.ARCHIVE -> SpecPhase.IMPLEMENT
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
