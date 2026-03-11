package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
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
}
