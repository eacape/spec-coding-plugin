package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.StageId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
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
