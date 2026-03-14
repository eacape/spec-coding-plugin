package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshEvent
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowContentUiType
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class ChatToolWindowFactoryPlatformTest : BasePlatformTestCase() {

    fun `test create tool window content should expose chat and spec header tabs`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val primaryContents = toolWindow.contentManager.contents.filter(ChatToolWindowFactory::isPrimaryContent)
        assertEquals(2, primaryContents.size)
        assertTrue(primaryContents.any { it.displayName == SpecCodingBundle.message("toolwindow.tab.chat") })
        assertTrue(primaryContents.any { it.displayName == SpecCodingBundle.message("spec.tab.title") })
        assertTrue(primaryContents.any { it.component is ImprovedChatPanel })
        assertTrue(primaryContents.any { it.component is SpecWorkflowPanel })
        assertEquals(ToolWindowContentUiType.TABBED, toolWindow.contentUiType)
    }

    fun `test select spec content should activate spec content tab`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(ChatToolWindowFactory.selectSpecContent(toolWindow, project))
        val selected = toolWindow.contentManager.selectedContent
        assertNotNull(selected)
        assertTrue(ChatToolWindowFactory.isSpecContent(selected))
        assertEquals(SpecCodingBundle.message("spec.tab.title"), selected!!.displayName)
    }

    fun `test removing spec content should restore primary tabs`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        ApplicationManager.getApplication().invokeAndWait {
            val specContent = toolWindow.contentManager.contents.firstOrNull(ChatToolWindowFactory::isSpecContent)
            assertNotNull(specContent)
            toolWindow.contentManager.removeContent(specContent!!, true)
        }
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(toolWindow.contentManager.contents.any(ChatToolWindowFactory::isChatContent))
        assertTrue(toolWindow.contentManager.contents.any(ChatToolWindowFactory::isSpecContent))
    }

    fun `test task panel should open chat with workflow and task binding chips`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Binding",
            description = "task 79",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Discuss task scope",
            priority = TaskPriority.P1,
        )
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(task.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            ChatToolWindowFactory.isChatContent(toolWindow.contentManager.selectedContent) &&
                chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "true"
        }

        val snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals("SPEC", snapshot.getValue("mode"))
        assertEquals("true", snapshot.getValue("workflowChipVisible"))
        assertTrue(snapshot.getValue("workflowChipText").contains(workflow.title))
        assertEquals("true", snapshot.getValue("taskChipVisible"))
        assertEquals(
            SpecCodingBundle.message("toolwindow.workflow.binding.task", task.id),
            snapshot.getValue("taskChipText"),
        )
        assertEquals(
            SpecCodingBundle.message("toolwindow.workflow.binding.task.tooltip.state", task.id, TaskStatus.PENDING.name),
            snapshot.getValue("taskChipTooltip"),
        )
        assertEquals("true", snapshot.getValue("executeTaskVisible"))
        assertEquals("true", snapshot.getValue("executeTaskEnabled"))
        assertEquals("execute", snapshot.getValue("executeTaskIconId"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", task.id),
            snapshot.getValue("executeTaskTooltip"),
        )
        assertEquals("true", snapshot.getValue("retryTaskVisible"))
        assertEquals("false", snapshot.getValue("retryTaskEnabled"))
        assertEquals("refresh", snapshot.getValue("retryTaskIconId"))
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.retry.unavailable.state",
                task.id,
                TaskStatus.PENDING.name,
            ),
            snapshot.getValue("retryTaskTooltip"),
        )
        assertEquals("true", snapshot.getValue("completeTaskVisible"))
        assertEquals("false", snapshot.getValue("completeTaskEnabled"))
        assertEquals("complete", snapshot.getValue("completeTaskIconId"))
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.complete.unavailable.state",
                task.id,
                TaskStatus.PENDING.name,
            ),
            snapshot.getValue("completeTaskTooltip"),
        )
        assertEquals("true", snapshot.getValue("taskClearVisible"))
        assertFalse(snapshot.getValue("sessionId").isBlank())

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.clearTaskBindingForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "false"
        }

        val clearedSnapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(workflow.id, clearedSnapshot.getValue("workflowId"))
        assertEquals("", clearedSnapshot.getValue("taskId"))
        assertEquals("true", clearedSnapshot.getValue("workflowChipVisible"))
        assertEquals("false", clearedSnapshot.getValue("taskChipVisible"))
        assertEquals("false", clearedSnapshot.getValue("executeTaskVisible"))
        assertEquals("false", clearedSnapshot.getValue("retryTaskVisible"))
        assertEquals("false", clearedSnapshot.getValue("completeTaskVisible"))
    }

    fun `test task binding chip should deep link back to workflow task in specs tab`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Task Deep Link",
            description = "task 82",
        ).getOrThrow()
        val tasksService = SpecTasksService(project)
        val primaryTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Primary linked task",
            priority = TaskPriority.P0,
        )
        val secondaryTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Secondary task",
            priority = TaskPriority.P1,
        )
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(primaryTask.id) &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(secondaryTask.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(primaryTask.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == primaryTask.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory.selectSpecContent(toolWindow, project)
            assertTrue(specPanel.selectTaskForTest(secondaryTask.id))
            specPanel.focusStageForTest(StageId.TASKS)
        }
        UIUtil.dispatchAllInvocationEvents()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory.selectChatContent(toolWindow, project)
            chatPanel.clickTaskBindingChipForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            ChatToolWindowFactory.isSpecContent(toolWindow.contentManager.selectedContent) &&
                specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.focusedStageForTest() == StageId.IMPLEMENT &&
                specPanel.tasksSnapshotForTest().getValue("selectedTaskId") == primaryTask.id
        }
    }

    fun `test workflow chat refresh event should update bound task run state presentation`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Refresh",
            description = "task 82 refresh",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Refresh bound task",
            priority = TaskPriority.P0,
        )
        val executionService = SpecTaskExecutionService(project)
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(task.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("completeTaskEnabled") == "false"
        }

        val run = executionService.createRun(
            workflowId = workflow.id,
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
        )
        project.messageBus.syncPublisher(WorkflowChatRefreshListener.TOPIC)
            .onWorkflowChatRefreshRequested(
                WorkflowChatRefreshEvent(
                    workflowId = workflow.id,
                    taskId = task.id,
                    focusedStage = StageId.IMPLEMENT,
                    reason = "test_waiting_confirmation",
                ),
            )

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("completeTaskEnabled") == "true"
        }

        val snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.tooltip.run",
                task.id,
                TaskStatus.IN_PROGRESS.name,
                run.runId,
                TaskExecutionRunStatus.WAITING_CONFIRMATION.name,
            ),
            snapshot.getValue("taskChipTooltip"),
        )
        assertEquals("false", snapshot.getValue("executeTaskEnabled"))
        assertEquals("false", snapshot.getValue("retryTaskEnabled"))
        assertEquals("true", snapshot.getValue("completeTaskEnabled"))
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
