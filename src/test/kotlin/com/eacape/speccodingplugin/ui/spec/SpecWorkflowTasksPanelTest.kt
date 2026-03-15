package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import java.time.Instant
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
        assertEquals("", snapshot.getValue("chatText"))
        assertEquals("openToolWindow", snapshot.getValue("chatIconId"))
        assertEquals("true", snapshot.getValue("chatHasIcon"))
        assertEquals("true", snapshot.getValue("chatRolloverEnabled"))
        assertEquals("true", snapshot.getValue("chatFocusable"))
        assertEquals("true", snapshot.getValue("chatEnabled"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.tooltip", "T-001"),
            snapshot.getValue("chatTooltip"),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.chat.open"),
            snapshot.getValue("chatAccessibleName"),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.tooltip", "T-001"),
            snapshot.getValue("chatAccessibleDescription"),
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
        assertEquals("", snapshot.getValue("verificationText"))
        assertEquals("verificationResult", snapshot.getValue("verificationIconId"))
        assertEquals("true", snapshot.getValue("verificationHasIcon"))
        assertEquals("true", snapshot.getValue("verificationRolloverEnabled"))
        assertEquals("true", snapshot.getValue("verificationFocusable"))
        assertEquals("true", snapshot.getValue("verificationEnabled"))
        assertEquals("true", snapshot.getValue("dependsOnEnabled"))
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
        assertEquals("true", snapshot.getValue("chatEnabled"))
        assertEquals("true", snapshot.getValue("chatFocusable"))
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
    fun `preferred height should grow with task count before workspace cap applies`() {
        val panel = SpecWorkflowTasksPanel(showHeader = false)

        panel.updateTasks(
            workflowId = "wf-height-single",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "Single task",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
        val singleTaskHeight = panel.preferredSize.height

        panel.updateTasks(
            workflowId = "wf-height-many",
            tasks = (1..6).map { index ->
                StructuredTask(
                    id = "T-%03d".format(index),
                    title = "Task $index",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                )
            },
            refreshedAtMillis = 1_710_000_000_000,
        )
        val manyTasksHeight = panel.preferredSize.height

        assertTrue(manyTasksHeight > singleTaskHeight)
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
    fun `pending task with unfinished dependencies should disable execute action`() {
        val executions = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onExecuteTask = { taskId, retry -> executions += "$taskId:$retry" },
        )

        panel.updateTasks(
            workflowId = "wf-dependency-disabled",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "Dependency",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
                StructuredTask(
                    id = "T-002",
                    title = "Blocked task",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                    dependsOn = listOf("T-001"),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-002"))
        val snapshot = panel.snapshotForTest()

        assertEquals("execute", snapshot.getValue("executeIconId"))
        assertEquals("false", snapshot.getValue("executeEnabled"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.dependenciesBlocked", "T-002", "T-001"),
            snapshot.getValue("executeTooltip"),
        )
        assertFalse(panel.requestExecutionForTask("T-002"))
        assertTrue(executions.isEmpty())
    }

    @Test
    fun `blocked task with unfinished dependencies should disable retry action`() {
        val executions = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onExecuteTask = { taskId, retry -> executions += "$taskId:$retry" },
        )

        panel.updateTasks(
            workflowId = "wf-dependency-retry-disabled",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "Dependency",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
                StructuredTask(
                    id = "T-002",
                    title = "Retry blocked task",
                    status = TaskStatus.BLOCKED,
                    priority = TaskPriority.P1,
                    dependsOn = listOf("T-001"),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-002"))
        val snapshot = panel.snapshotForTest()

        assertEquals("refresh", snapshot.getValue("executeIconId"))
        assertEquals("false", snapshot.getValue("executeEnabled"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.dependenciesBlocked.retry", "T-002", "T-001"),
            snapshot.getValue("executeTooltip"),
        )
        assertFalse(panel.requestExecutionForTask("T-002"))
        assertTrue(executions.isEmpty())
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
    fun `cancel secondary action should stay disabled while downstream tasks are not cancelled`() {
        val transitions = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onTransitionStatus = { taskId, to -> transitions += "$taskId:${to.name}" },
        )

        panel.updateTasks(
            workflowId = "wf-cancel-blocked",
            tasks = listOf(
                StructuredTask(
                    id = "T-001",
                    title = "Shared dependency",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                ),
                StructuredTask(
                    id = "T-002",
                    title = "Downstream task",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                    dependsOn = listOf("T-001"),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-001"))
        assertFalse(panel.triggerSecondaryActionForTest(TaskStatus.CANCELLED))
        assertTrue(transitions.isEmpty())
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
        assertEquals("false", snapshot.getValue("secondaryEnabled"))
        assertEquals("WAITING_CONFIRMATION", snapshot.getValue("selectedTaskPhase"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.execution.chip.waitingConfirmation"),
            snapshot.getValue("selectedTaskChip"),
        )
        assertTrue(
            snapshot.getValue("selectedTaskMeta").contains(
                SpecCodingBundle.message("spec.toolwindow.execution.phase.waitingConfirmation"),
            ),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.secondary.waitingConfirmation", "T-010"),
            snapshot.getValue("secondaryTooltip"),
        )
    }

    @Test
    fun `in progress task should expose stop execution secondary action`() {
        val cancellations = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onCancelExecution = { taskId -> cancellations += taskId },
        )
        val now = Instant.now()

        panel.updateTasks(
            workflowId = "wf-stop",
            tasks = listOf(
                StructuredTask(
                    id = "T-011",
                    title = "Cancelable run",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P0,
                    activeExecutionRun = TaskExecutionRun(
                        runId = "run-11",
                        taskId = "T-011",
                        status = TaskExecutionRunStatus.RUNNING,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        startedAt = "2026-03-13T12:05:00Z",
                    ),
                ),
            ),
            liveProgressByTaskId = mapOf(
                "T-011" to TaskExecutionLiveProgress(
                    workflowId = "wf-stop",
                    runId = "run-11",
                    taskId = "T-011",
                    phase = ExecutionLivePhase.STREAMING,
                    startedAt = now.minusSeconds(90),
                    lastUpdatedAt = now.minusSeconds(3),
                    lastDetail = "Reading task context",
                    recentEvents = listOf(
                        ChatStreamEvent(
                            kind = ChatTraceKind.READ,
                            detail = "SpecWorkflowPanel.kt",
                            status = ChatTraceStatus.RUNNING,
                        ),
                        ChatStreamEvent(
                            kind = ChatTraceKind.EDIT,
                            detail = "SpecTaskExecutionService.kt",
                            status = ChatTraceStatus.INFO,
                        ),
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-011"))
        val snapshot = panel.snapshotForTest()
        assertEquals("close", snapshot.getValue("executeIconId"))
        assertEquals("true", snapshot.getValue("executeEnabled"))
        assertEquals("STREAMING", snapshot.getValue("selectedTaskPhase"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.execution.chip.running"),
            snapshot.getValue("selectedTaskChip"),
        )
        assertEquals("Reading task context", snapshot.getValue("selectedTaskExecutionDetail"))
        assertTrue(snapshot.getValue("selectedTaskMeta").contains("Edit: SpecTaskExecutionService.kt"))
        assertTrue(panel.triggerStopExecutionForTest())
        assertEquals(listOf("T-011"), cancellations)
    }

    @Test
    fun `updateLiveProgress should refresh selected task execution summary and keep selection`() {
        val panel = SpecWorkflowTasksPanel()
        val start = Instant.parse("2026-03-13T12:05:00Z")
        val task = StructuredTask(
            id = "T-013",
            title = "Refresh live state",
            status = TaskStatus.PENDING,
            priority = TaskPriority.P0,
            activeExecutionRun = TaskExecutionRun(
                runId = "run-13",
                taskId = "T-013",
                status = TaskExecutionRunStatus.RUNNING,
                trigger = ExecutionTrigger.USER_EXECUTE,
                startedAt = start.toString(),
            ),
        )

        panel.updateTasks(
            workflowId = "wf-refresh-live",
            tasks = listOf(task),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-013"))
        var snapshot = panel.snapshotForTest()
        assertEquals("T-013", snapshot.getValue("selectedTaskId"))
        assertEquals("REQUEST_DISPATCHED", snapshot.getValue("selectedTaskPhase"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.execution.detail.requestDispatched"),
            snapshot.getValue("selectedTaskExecutionDetail"),
        )

        panel.updateLiveProgress(
            tasks = listOf(task),
            liveProgressByTaskId = mapOf(
                "T-013" to TaskExecutionLiveProgress(
                    workflowId = "wf-refresh-live",
                    runId = "run-13",
                    taskId = "T-013",
                    phase = ExecutionLivePhase.STREAMING,
                    startedAt = start,
                    lastUpdatedAt = start.plusSeconds(87),
                    lastDetail = "Reading task context",
                    recentEvents = listOf(
                        ChatStreamEvent(
                            kind = ChatTraceKind.READ,
                            detail = "SpecWorkflowPanel.kt",
                            status = ChatTraceStatus.RUNNING,
                        ),
                    ),
                ),
            ),
        )

        snapshot = panel.snapshotForTest()
        assertEquals("T-013", snapshot.getValue("selectedTaskId"))
        assertEquals("STREAMING", snapshot.getValue("selectedTaskPhase"))
        assertEquals("Reading task context", snapshot.getValue("selectedTaskExecutionDetail"))
        assertTrue(snapshot.getValue("selectedTaskMeta").contains("Read: SpecWorkflowPanel.kt"))
        assertEquals("close", snapshot.getValue("executeIconId"))
    }

    @Test
    fun `cancelling task should disable primary execution action and show cancelling chip`() {
        val panel = SpecWorkflowTasksPanel()
        val now = Instant.now()

        panel.updateTasks(
            workflowId = "wf-cancelling",
            tasks = listOf(
                StructuredTask(
                    id = "T-012",
                    title = "Cancelling run",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                    activeExecutionRun = TaskExecutionRun(
                        runId = "run-12",
                        taskId = "T-012",
                        status = TaskExecutionRunStatus.RUNNING,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        startedAt = now.minusSeconds(45).toString(),
                    ),
                ),
            ),
            liveProgressByTaskId = mapOf(
                "T-012" to TaskExecutionLiveProgress(
                    workflowId = "wf-cancelling",
                    runId = "run-12",
                    taskId = "T-012",
                    phase = ExecutionLivePhase.CANCELLING,
                    startedAt = now.minusSeconds(45),
                    lastUpdatedAt = now.minusSeconds(1),
                    lastDetail = "Cancelling execution",
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-012"))
        val snapshot = panel.snapshotForTest()
        assertEquals("close", snapshot.getValue("executeIconId"))
        assertEquals("false", snapshot.getValue("executeEnabled"))
        assertEquals("CANCELLING", snapshot.getValue("selectedTaskPhase"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.execution.chip.cancelling"),
            snapshot.getValue("selectedTaskChip"),
        )
        assertTrue(
            snapshot.getValue("selectedTaskMeta").contains(
                SpecCodingBundle.message("spec.toolwindow.execution.phase.cancelling"),
            ),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelling.tooltip", "T-012"),
            snapshot.getValue("executeTooltip"),
        )
    }

    @Test
    fun `open workflow chat button should route selected task binding`() {
        val bindings = mutableListOf<String>()
        val panel = SpecWorkflowTasksPanel(
            onOpenWorkflowChat = { workflowId, taskId -> bindings += "$workflowId:$taskId" },
        )

        panel.updateTasks(
            workflowId = "wf-chat",
            tasks = listOf(
                StructuredTask(
                    id = "T-321",
                    title = "Chat binding task",
                    status = TaskStatus.PENDING,
                    priority = TaskPriority.P1,
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.selectTask("T-321"))
        panel.clickOpenWorkflowChatForTest()

        assertEquals(listOf("wf-chat:T-321"), bindings)
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
        assertEquals("verificationResult", snapshot.getValue("verificationIconId"))
        assertEquals("true", snapshot.getValue("verificationEnabled"))
        assertFalse(snapshot.getValue("verificationTooltip").isBlank())
    }
}
