package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

class SpecWorkflowListPanelTest {

    @Test
    fun `updateWorkflows should replace list items and selection can be set`() {
        val panel = SpecWorkflowListPanel(
            onWorkflowSelected = {},
            onCreateWorkflow = {},
            onEditWorkflow = {},
            onDeleteWorkflow = {},
        )

        panel.updateWorkflows(
            listOf(
                item(id = "wf-1", title = "Workflow 1", phase = SpecPhase.SPECIFY),
                item(id = "wf-2", title = "Workflow 2", phase = SpecPhase.DESIGN),
            )
        )

        assertEquals(2, panel.itemsForTest().size)
        assertEquals("Workflow 1", panel.itemsForTest()[0].title)
        assertEquals("Workflow 2", panel.itemsForTest()[1].title)

        panel.setSelectedWorkflow("wf-2")
        assertEquals("wf-2", panel.selectedWorkflowIdForTest())

        panel.setSelectedWorkflow(null)
        assertNull(panel.selectedWorkflowIdForTest())
    }

    @Test
    fun `toolbar buttons should trigger callbacks`() {
        var createCalls = 0
        val editedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()

        val panel = SpecWorkflowListPanel(
            onWorkflowSelected = {},
            onCreateWorkflow = { createCalls += 1 },
            onEditWorkflow = { editedIds += it },
            onDeleteWorkflow = { deletedIds += it },
        )

        panel.updateWorkflows(listOf(item(id = "wf-del", title = "To Delete", phase = SpecPhase.SPECIFY)))

        panel.clickNewForTest()
        assertEquals(1, createCalls)

        panel.setSelectedWorkflow("wf-del")
        panel.clickDeleteForTest()
        assertEquals(listOf("wf-del"), deletedIds)

        panel.clickEditForTest()
        assertEquals(listOf("wf-del"), editedIds)
    }

    @Test
    fun `single click on edit icon should trigger edit callback`() {
        val editedIds = mutableListOf<String>()
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowSelected = {},
                onCreateWorkflow = {},
                onEditWorkflow = { editedIds += it },
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-edit",
                        title = "Editable Workflow",
                        description = "",
                        currentPhase = SpecPhase.SPECIFY,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            val rowComponent = rendererComponentFor(list, panel.itemsForTest().first(), cellBounds!!)
            val editLabel = findLabelByTooltip(rowComponent, SpecCodingBundle.message("spec.workflow.edit"))
            assertNotNull(editLabel)

            val editRect = SwingUtilities.convertRectangle(
                editLabel!!.parent,
                editLabel.bounds,
                rowComponent,
            )
            val clickX = cellBounds.x + editRect.x + editRect.width / 2
            val clickY = cellBounds.y + editRect.y + editRect.height / 2
            list.dispatchEvent(
                MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    clickX,
                    clickY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals(listOf("wf-edit"), editedIds)
    }

    private fun item(id: String, title: String, phase: SpecPhase): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = id,
            title = title,
            description = "desc",
            currentPhase = phase,
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
        )
    }

    private fun extractWorkflowList(panel: SpecWorkflowListPanel): JList<SpecWorkflowListPanel.WorkflowListItem> {
        val field = SpecWorkflowListPanel::class.java.getDeclaredField("workflowList")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(panel) as JList<SpecWorkflowListPanel.WorkflowListItem>
    }

    private fun rendererComponentFor(
        list: JList<SpecWorkflowListPanel.WorkflowListItem>,
        item: SpecWorkflowListPanel.WorkflowListItem,
        cellBounds: Rectangle,
    ): Component {
        @Suppress("UNCHECKED_CAST")
        val renderer = list.cellRenderer as ListCellRenderer<SpecWorkflowListPanel.WorkflowListItem>
        val component = renderer.getListCellRendererComponent(list, item, 0, true, false)
        component.setBounds(0, 0, cellBounds.width, cellBounds.height)
        layoutRecursively(component)
        return component
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach { child ->
            layoutRecursively(child)
        }
    }

    private fun findLabelByTooltip(component: Component, tooltip: String): JLabel? {
        if (component is JLabel && component.toolTipText == tooltip) {
            return component
        }
        if (component !is Container) {
            return null
        }
        return component.components.asSequence()
            .mapNotNull { child -> findLabelByTooltip(child, tooltip) }
            .firstOrNull()
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private fun <T> runOnEdtResult(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
