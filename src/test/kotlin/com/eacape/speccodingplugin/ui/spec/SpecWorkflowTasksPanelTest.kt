package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowTasksPanelTest {

    @Test
    fun `updateTasks should render task list and enable controls for pending tasks`() {
        val panel = SpecWorkflowTasksPanel()

        panel.updateTasks(
            workflowId = "wf-1",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "First",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
                StructuredTask(
                    id = "T-002",
                    title = "Second",
                    status = TaskStatus.IN_PROGRESS,
                    priority = TaskPriority.P1,
                    dependsOn = listOf("T-001"),
                    relatedFiles = listOf("src/main.kt"),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        panel.selectTaskForTest("T-001")
        val snapshot = panel.snapshotForTest()
        assertEquals("wf-1", snapshot.getValue("workflowId"))
        assertTrue(snapshot.getValue("tasks").contains("T-001:PENDING:P0"))
        assertEquals("T-001", snapshot.getValue("selectedTaskId"))
        assertEquals("28", snapshot.getValue("statusComboHeight"))
        assertEquals("", snapshot.getValue("applyText"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.status.apply"), snapshot.getValue("applyTooltip"))
        assertEquals("true", snapshot.getValue("applyHasIcon"))
        assertEquals("true", snapshot.getValue("applyRolloverEnabled"))
        assertEquals("", snapshot.getValue("dependsOnText"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"), snapshot.getValue("dependsOnTooltip"))
        assertEquals("true", snapshot.getValue("dependsOnHasIcon"))
        assertEquals("true", snapshot.getValue("dependsOnRolloverEnabled"))
        assertEquals("", snapshot.getValue("relatedFilesText"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.edit"), snapshot.getValue("relatedFilesTooltip"))
        assertEquals("true", snapshot.getValue("relatedFilesHasIcon"))
        assertEquals("true", snapshot.getValue("relatedFilesRolloverEnabled"))
        assertEquals("true", snapshot.getValue("applyEnabled"))
        assertEquals("true", snapshot.getValue("dependsOnEnabled"))
        assertEquals("true", snapshot.getValue("relatedFilesEnabled"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow"), snapshot.getValue("emptyText"))
    }

    @Test
    fun `completed tasks should disable status and dependsOn editing`() {
        val panel = SpecWorkflowTasksPanel()

        panel.updateTasks(
            workflowId = "wf-1",
            tasks = listOf(
                StructuredTask(
                    id = "T-010",
                    title = "Done",
                    status = TaskStatus.COMPLETED,
                    priority = TaskPriority.P0,
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        panel.selectTaskForTest("T-010")
        val snapshot = panel.snapshotForTest()
        assertEquals("false", snapshot.getValue("applyEnabled"))
        assertEquals("false", snapshot.getValue("dependsOnEnabled"))
        assertEquals("true", snapshot.getValue("relatedFilesEnabled"))
    }

    @Test
    fun `showLoading should expose loading empty text`() {
        val panel = SpecWorkflowTasksPanel()

        panel.showLoading()

        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.loading"), panel.snapshotForTest().getValue("emptyText"))
    }

    @Test
    fun `embedded tasks panel should hide duplicated header block`() {
        val panel = SpecWorkflowTasksPanel(showHeader = false)

        panel.updateTasks(
            workflowId = "wf-1",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "First",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("false", snapshot.getValue("headerVisible"))
        assertTrue(snapshot.getValue("tasks").contains("T-001:PENDING:P0"))
    }
}
