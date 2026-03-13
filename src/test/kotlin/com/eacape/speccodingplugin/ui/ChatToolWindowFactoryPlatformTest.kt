package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.TaskPriority
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
