package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkflowChatTaskIntentResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var storage: SpecStorage
    private lateinit var tasksService: SpecTasksService
    private lateinit var resolver: WorkflowChatTaskIntentResolver

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        storage = SpecStorage.getInstance(project)
        tasksService = SpecTasksService(project)
        resolver = WorkflowChatTaskIntentResolver(
            project = project,
            storage = storage,
            tasksService = tasksService,
        )
    }

    @Test
    fun `resolve should ignore generic continue discussion prompt`() {
        seedWorkflow(
            workflowId = "wf-generic-chat",
            tasksMarkdown = tasksMarkdown(
                taskSection(
                    id = "T-001",
                    title = "Implement workflow chat binding",
                    status = "PENDING",
                ),
            ),
        )

        val resolution = resolver.resolve(
            workflowId = "wf-generic-chat",
            binding = null,
            userInput = "继续解释一下这个设计方案",
        )

        assertTrue(resolution === WorkflowChatTaskIntentResolution.NoMatch)
    }

    @Test
    fun `resolve should select next actionable task for execute intent`() {
        seedWorkflow(
            workflowId = "wf-next-task",
            tasksMarkdown = tasksMarkdown(
                taskSection(
                    id = "T-001",
                    title = "Bootstrap parser",
                    status = "COMPLETED",
                ),
                taskSection(
                    id = "T-002",
                    title = "Blocked follow-up",
                    status = "BLOCKED",
                    dependsOn = listOf("T-001"),
                ),
                taskSection(
                    id = "T-003",
                    title = "Implement workflow intent resolver",
                    status = "PENDING",
                    dependsOn = listOf("T-001"),
                ),
            ),
        )

        val resolution = resolver.resolve(
            workflowId = "wf-next-task",
            binding = null,
            userInput = "进行下一个任务",
        ) as WorkflowChatTaskIntentResolution.Resolved

        assertEquals(WorkflowChatActionIntent.EXECUTE_TASK, resolution.actionIntent)
        assertEquals("T-003", resolution.task.id)
        assertEquals(WorkflowChatTaskMatchSource.NEXT_ACTIONABLE, resolution.matchSource)
    }

    @Test
    fun `resolve should map explicit task id to retry intent`() {
        seedWorkflow(
            workflowId = "wf-retry-task",
            tasksMarkdown = tasksMarkdown(
                taskSection(
                    id = "T-021",
                    title = "Retry blocked implementation",
                    status = "BLOCKED",
                ),
            ),
        )

        val resolution = resolver.resolve(
            workflowId = "wf-retry-task",
            binding = null,
            userInput = "重试 Task 21",
        ) as WorkflowChatTaskIntentResolution.Resolved

        assertEquals(WorkflowChatActionIntent.RETRY_TASK, resolution.actionIntent)
        assertEquals("T-021", resolution.task.id)
        assertEquals(WorkflowChatTaskMatchSource.EXPLICIT_TASK_ID, resolution.matchSource)
    }

    @Test
    fun `resolve should use bound task for current task completion intent`() {
        seedWorkflow(
            workflowId = "wf-current-task",
            tasksMarkdown = tasksMarkdown(
                taskSection(
                    id = "T-002",
                    title = "Confirm execution outcome",
                    status = "PENDING",
                ),
            ),
            taskExecutionRuns = listOf(
                TaskExecutionRun(
                    runId = "run-current-task",
                    taskId = "T-002",
                    status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                    trigger = ExecutionTrigger.USER_EXECUTE,
                    startedAt = "2026-03-17T10:00:00Z",
                    summary = "Waiting for confirmation.",
                ),
            ),
        )

        val resolution = resolver.resolve(
            workflowId = "wf-current-task",
            binding = WorkflowChatBinding(
                workflowId = "wf-current-task",
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.SESSION_RESTORE,
            ),
            userInput = "完成当前任务",
        ) as WorkflowChatTaskIntentResolution.Resolved

        assertEquals(WorkflowChatActionIntent.COMPLETE_TASK, resolution.actionIntent)
        assertEquals("T-002", resolution.task.id)
        assertEquals(WorkflowChatTaskMatchSource.CURRENT_BINDING, resolution.matchSource)
    }

    @Test
    fun `resolve should match task title fragment`() {
        seedWorkflow(
            workflowId = "wf-title-match",
            tasksMarkdown = tasksMarkdown(
                taskSection(
                    id = "T-004",
                    title = "实现聊天绑定",
                    status = "PENDING",
                ),
            ),
        )

        val resolution = resolver.resolve(
            workflowId = "wf-title-match",
            binding = null,
            userInput = "完成聊天绑定的任务",
        ) as WorkflowChatTaskIntentResolution.Resolved

        assertEquals(WorkflowChatActionIntent.COMPLETE_TASK, resolution.actionIntent)
        assertEquals("T-004", resolution.task.id)
        assertEquals(WorkflowChatTaskMatchSource.TITLE_MATCH, resolution.matchSource)
    }

    @Test
    fun `resolve should report ambiguous title matches`() {
        seedWorkflow(
            workflowId = "wf-ambiguous-title",
            tasksMarkdown = tasksMarkdown(
                taskSection(
                    id = "T-010",
                    title = "聊天绑定路由",
                    status = "PENDING",
                ),
                taskSection(
                    id = "T-011",
                    title = "聊天绑定面板",
                    status = "PENDING",
                ),
            ),
        )

        val resolution = resolver.resolve(
            workflowId = "wf-ambiguous-title",
            binding = null,
            userInput = "完成聊天绑定的任务",
        ) as WorkflowChatTaskIntentResolution.Unresolved

        assertEquals(WorkflowChatActionIntent.COMPLETE_TASK, resolution.actionIntent)
        assertEquals(WorkflowChatTaskIntentFailureReason.AMBIGUOUS_TASK, resolution.reason)
        assertEquals(listOf("T-010", "T-011"), resolution.candidateTaskIds)
    }

    @Test
    fun `resolve should require active workflow for task action request`() {
        val resolution = resolver.resolve(
            workflowId = null,
            binding = null,
            userInput = "开发下一个任务",
        ) as WorkflowChatTaskIntentResolution.Unresolved

        assertEquals(WorkflowChatActionIntent.EXECUTE_TASK, resolution.actionIntent)
        assertEquals(WorkflowChatTaskIntentFailureReason.NO_ACTIVE_WORKFLOW, resolution.reason)
    }

    private fun seedWorkflow(
        workflowId: String,
        tasksMarkdown: String,
        taskExecutionRuns: List<TaskExecutionRun> = emptyList(),
    ) {
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.IMPLEMENT,
                title = workflowId,
                description = "workflow chat intent resolver test",
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                taskExecutionRuns = taskExecutionRuns,
                stageStates = linkedMapOf(
                    StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.DONE),
                    StageId.DESIGN to StageState(active = true, status = StageProgress.DONE),
                    StageId.TASKS to StageState(active = true, status = StageProgress.DONE),
                    StageId.IMPLEMENT to StageState(active = true, status = StageProgress.IN_PROGRESS),
                    StageId.VERIFY to StageState(active = true, status = StageProgress.NOT_STARTED),
                    StageId.ARCHIVE to StageState(active = true, status = StageProgress.NOT_STARTED),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(
            workflowId = workflowId,
            stageId = StageId.TASKS,
            content = tasksMarkdown,
        )
    }

    private fun tasksMarkdown(vararg sections: String): String {
        return buildString {
            appendLine("# Implement Document")
            appendLine()
            appendLine("## Task List")
            appendLine()
            sections.forEach { section ->
                append(section.trimEnd())
                appendLine()
                appendLine()
            }
        }.trimEnd()
    }

    private fun taskSection(
        id: String,
        title: String,
        status: String,
        dependsOn: List<String> = emptyList(),
    ): String {
        val dependsOnMarkdown = dependsOn.joinToString(prefix = "[", postfix = "]")
        return """
            ### $id: $title
            ```spec-task
            status: $status
            priority: P1
            dependsOn: $dependsOnMarkdown
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] $title
        """.trimIndent()
    }
}
