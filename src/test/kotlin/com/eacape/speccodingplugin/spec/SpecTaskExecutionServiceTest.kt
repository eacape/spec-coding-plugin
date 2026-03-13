package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecTaskExecutionServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var storage: SpecStorage
    private lateinit var executionService: SpecTaskExecutionService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        storage = SpecStorage.getInstance(project)
        executionService = SpecTaskExecutionService(project)
    }

    @Test
    fun `createRun should persist execution run metadata and audit`() {
        val workflowId = "wf-run-create"
        seedWorkflow(workflowId)

        val run = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.QUEUED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T08:00:00Z",
            summary = "Queued from task action.",
        )

        val loaded = storage.loadWorkflow(workflowId).getOrThrow()
        val auditEvents = storage.listAuditEvents(workflowId).getOrThrow()
        val runCreated = auditEvents.last { event -> event.eventType == SpecAuditEventType.TASK_EXECUTION_RUN_CREATED }

        assertEquals(1, loaded.taskExecutionRuns.size)
        assertEquals(run, loaded.taskExecutionRuns.single())
        assertEquals(TaskExecutionRunStatus.QUEUED, loaded.taskExecutionRuns.single().status)
        assertEquals("T-001", runCreated.details["taskId"])
        assertEquals(run.runId, runCreated.details["runId"])
        assertEquals(ExecutionTrigger.USER_EXECUTE.name, runCreated.details["trigger"])
    }

    @Test
    fun `migrateLegacyInProgressTasks should create system recovery run once`() {
        val workflowId = "wf-run-migrate"
        seedWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Recover me
                ```spec-task
                status: IN_PROGRESS
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Resume later.
            """.trimIndent(),
        )

        val firstMigration = executionService.migrateLegacyInProgressTasks(
            storage.loadWorkflow(workflowId).getOrThrow(),
        )
        val secondMigration = executionService.migrateLegacyInProgressTasks(firstMigration.workflow)
        val runs = executionService.listRuns(workflowId, "T-001")

        assertTrue(firstMigration.migrated)
        assertEquals(1, firstMigration.migratedRuns.size)
        assertFalse(secondMigration.migrated)
        assertEquals(1, runs.size)
        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, runs.single().status)
        assertEquals(ExecutionTrigger.SYSTEM_RECOVERY, runs.single().trigger)

        val recoveryAudit = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.TASK_EXECUTION_RUN_CREATED }
        assertEquals(TaskStatus.IN_PROGRESS.name, recoveryAudit.details["migratedFromStatus"])
    }

    @Test
    fun `updateRunStatus should reject invalid transitions`() {
        val workflowId = "wf-run-transition"
        seedWorkflow(workflowId)
        val run = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.QUEUED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T09:00:00Z",
        )

        val error = assertThrows(InvalidTaskExecutionRunTransitionError::class.java) {
            executionService.updateRunStatus(
                workflowId = workflowId,
                runId = run.runId,
                status = TaskExecutionRunStatus.SUCCEEDED,
            )
        }

        assertTrue(error.message?.contains("QUEUED -> SUCCEEDED") == true)
    }

    private fun seedWorkflow(
        workflowId: String,
        tasksMarkdown: String = """
            # Implement Document

            ## Task List

            ### T-001: Execute task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this task.
        """.trimIndent(),
    ) {
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.IMPLEMENT,
                title = workflowId,
                description = "execution run test",
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                stageStates = linkedMapOf(
                    StageId.TASKS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:50:00Z",
                        completedAt = "2026-03-13T07:55:00Z",
                    ),
                    StageId.IMPLEMENT to StageState(
                        active = true,
                        status = StageProgress.IN_PROGRESS,
                        enteredAt = "2026-03-13T08:00:00Z",
                        completedAt = null,
                    ),
                    StageId.ARCHIVE to StageState(
                        active = true,
                        status = StageProgress.NOT_STARTED,
                        enteredAt = null,
                        completedAt = null,
                    ),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(workflowId, StageId.TASKS, tasksMarkdown)
    }
}
