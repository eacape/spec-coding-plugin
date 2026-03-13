package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                    dependsOn = listOf("T-001"),
                    relatedFiles = listOf("src/main.kt"),
                    activeExecutionRun = TaskExecutionRun(
                        runId = "run-1",
                        taskId = "T-002",
                        status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        startedAt = "2026-03-13T12:00:00Z",
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        panel.selectTaskForTest("T-001")
        val snapshot = panel.snapshotForTest()
        assertEquals("wf-1", snapshot.getValue("workflowId"))
        assertTrue(snapshot.getValue("tasks").contains("T-001:PENDING:P0"))
        assertTrue(snapshot.getValue("tasks").contains("T-002:IN_PROGRESS:P1"))
        assertEquals("T-001", snapshot.getValue("selectedTaskId"))
        assertEquals("", snapshot.getValue("executeText"))
        assertEquals("execute", snapshot.getValue("executeIconId"))
        assertEquals("true", snapshot.getValue("executeHasIcon"))
        assertEquals("true", snapshot.getValue("executeRolloverEnabled"))
        assertEquals("true", snapshot.getValue("executeFocusable"))
        assertEquals("true", snapshot.getValue("executeEnabled"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", "T-001"),
            snapshot.getValue("executeTooltip"),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.start"),
            snapshot.getValue("executeAccessibleName"),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", "T-001"),
            snapshot.getValue("executeAccessibleDescription"),
        )
        assertEquals("", snapshot.getValue("secondaryText"))
        assertEquals("overflow", snapshot.getValue("secondaryIconId"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.secondary.tooltip", "T-001"),
            snapshot.getValue("secondaryTooltip"),
        )
        assertEquals("true", snapshot.getValue("secondaryHasIcon"))
        assertEquals("true", snapshot.getValue("secondaryRolloverEnabled"))
        assertEquals("true", snapshot.getValue("secondaryFocusable"))
        assertEquals("true", snapshot.getValue("secondaryEnabled"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.secondary.button"),
            snapshot.getValue("secondaryAccessibleName"),
        )
        assertEquals("", snapshot.getValue("dependsOnText"))
        assertEquals("edit", snapshot.getValue("dependsOnIconId"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"), snapshot.getValue("dependsOnTooltip"))
        assertEquals("true", snapshot.getValue("dependsOnHasIcon"))
        assertEquals("true", snapshot.getValue("dependsOnRolloverEnabled"))
        assertEquals("true", snapshot.getValue("dependsOnFocusable"))
        assertEquals("", snapshot.getValue("relatedFilesText"))
        assertEquals("edit", snapshot.getValue("relatedFilesIconId"))
        assertEquals(SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.edit"), snapshot.getValue("relatedFilesTooltip"))
        assertEquals("true", snapshot.getValue("relatedFilesHasIcon"))
        assertEquals("true", snapshot.getValue("relatedFilesRolloverEnabled"))
        assertEquals("true", snapshot.getValue("relatedFilesFocusable"))
        assertEquals("", snapshot.getValue("verificationText"))
        assertEquals("add", snapshot.getValue("verificationIconId"))
        assertEquals("true", snapshot.getValue("verificationHasIcon"))
        assertEquals("true", snapshot.getValue("verificationRolloverEnabled"))
        assertEquals("true", snapshot.getValue("verificationFocusable"))
        assertEquals("true", snapshot.getValue("verificationEnabled"))
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
        assertEquals("", snapshot.getValue("executeText"))
        assertEquals("complete", snapshot.getValue("executeIconId"))
        assertEquals("false", snapshot.getValue("executeEnabled"))
        assertEquals("true", snapshot.getValue("executeFocusable"))
        assertEquals(
            "${SpecCodingBundle.message("spec.toolwindow.tasks.execute.done")}. " +
                SpecCodingBundle.message("spec.toolwindow.tasks.execute.done.tooltip", "T-010"),
            snapshot.getValue("executeAccessibleDescription"),
        )
        assertEquals("false", snapshot.getValue("secondaryEnabled"))
        assertEquals("true", snapshot.getValue("secondaryFocusable"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.secondary.none", "T-010"),
            snapshot.getValue("secondaryTooltip"),
        )
        assertEquals(
            "${SpecCodingBundle.message("spec.toolwindow.tasks.secondary.button")}. " +
                SpecCodingBundle.message("spec.toolwindow.tasks.secondary.none", "T-010"),
            snapshot.getValue("secondaryAccessibleDescription"),
        )
        assertEquals("false", snapshot.getValue("dependsOnEnabled"))
        assertEquals("true", snapshot.getValue("relatedFilesEnabled"))
        assertEquals("true", snapshot.getValue("verificationEnabled"))
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

    @Test
    fun `requestExecutionForTask should route pending tasks to execute and blocked tasks to retry`() {
        val executions = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onExecuteTask = { taskId, retry -> executions += "$taskId:$retry" },
        )

        panel.updateTasks(
            workflowId = "wf-1",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "Pending",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
                StructuredTask(
                    id = "T-002",
                    title = "Blocked",
                    status = TaskStatus.BLOCKED,
                    priority = TaskPriority.P1,
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.requestExecutionForTask("T-001"))
        assertTrue(panel.requestExecutionForTask("T-002"))

        assertEquals(listOf("T-001:false", "T-002:true"), executions)
    }

    @Test
    fun `triggerSecondaryActionForTest should route lifecycle secondary actions without status apply button`() {
        val transitions = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onTransitionStatus = { taskId, to -> transitions += "$taskId:${to.name}" },
        )

        panel.updateTasks(
            workflowId = "wf-secondary",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "Pending",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
                StructuredTask(
                    id = "T-002",
                    title = "Blocked",
                    status = TaskStatus.BLOCKED,
                    priority = TaskPriority.P1,
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-001"))
        assertTrue(panel.triggerSecondaryActionForTest(TaskStatus.BLOCKED))
        assertTrue(panel.selectTask("T-002"))
        assertTrue(panel.triggerSecondaryActionForTest(TaskStatus.PENDING))
        assertTrue(panel.triggerSecondaryActionForTest(TaskStatus.CANCELLED))

        assertEquals(
            listOf("T-001:BLOCKED", "T-002:PENDING", "T-002:CANCELLED"),
            transitions,
        )
    }

    @Test
    fun `updateTasks should derive in progress from active execution run`() {
        val panel = SpecWorkflowTasksPanel()

        panel.updateTasks(
            workflowId = "wf-run",
            tasks = listOf(
                StructuredTask(
                    id = "T-010",
                    title = "Derived progress",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                    activeExecutionRun = TaskExecutionRun(
                        runId = "run-10",
                        taskId = "T-010",
                        status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        startedAt = "2026-03-13T12:00:00Z",
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        panel.selectTaskForTest("T-010")
        val snapshot = panel.snapshotForTest()

        assertTrue(snapshot.getValue("tasks").contains("T-010:IN_PROGRESS:P0"))
        assertEquals("", snapshot.getValue("executeText"))
        assertEquals("complete", snapshot.getValue("executeIconId"))
        assertEquals("true", snapshot.getValue("executeEnabled"))
    }

    @Test
    fun `updateTasks should show resume and verification edit presentation for blocked verified tasks`() {
        val panel = SpecWorkflowTasksPanel()

        panel.updateTasks(
            workflowId = "wf-verify",
            tasks = listOf(
                StructuredTask(
                    id = "T-100",
                    title = "Blocked verification task",
                    status = TaskStatus.BLOCKED,
                    priority = TaskPriority.P1,
                    verificationResult = TaskVerificationResult(
                        conclusion = VerificationConclusion.WARN,
                        runId = "manual-t-100",
                        summary = "Needs follow-up",
                        at = "2026-03-13T10:00:00Z",
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        panel.selectTaskForTest("T-100")
        val snapshot = panel.snapshotForTest()

        assertEquals("", snapshot.getValue("executeText"))
        assertEquals("refresh", snapshot.getValue("executeIconId"))
        assertEquals("true", snapshot.getValue("executeEnabled"))
        assertEquals("true", snapshot.getValue("secondaryEnabled"))
        assertEquals("", snapshot.getValue("verificationText"))
        assertEquals("edit", snapshot.getValue("verificationIconId"))
        assertEquals("true", snapshot.getValue("verificationEnabled"))
        assertFalse(snapshot.getValue("verificationTooltip").isBlank())
    }
}
