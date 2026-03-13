package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmUsage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecTaskExecutionServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var storage: SpecStorage
    private lateinit var tasksService: SpecTasksService
    private lateinit var sessionManager: SessionManager
    private lateinit var projectService: SpecCodingProjectService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        storage = SpecStorage.getInstance(project)
        tasksService = SpecTasksService(project)
        sessionManager = SessionManager(project)
        projectService = mockk(relaxed = true)
    }

    @Test
    fun `createRun should persist execution run metadata and audit`() {
        val executionService = newExecutionService()
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
        val executionService = newExecutionService()
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
        val migratedTask = tasksService.parse(workflowId).single()
        val persistedTasks = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertTrue(firstMigration.migrated)
        assertEquals(1, firstMigration.migratedRuns.size)
        assertFalse(secondMigration.migrated)
        assertEquals(1, runs.size)
        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, runs.single().status)
        assertEquals(ExecutionTrigger.SYSTEM_RECOVERY, runs.single().trigger)
        assertEquals(TaskStatus.PENDING, migratedTask.status)
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))

        val recoveryAudit = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.TASK_EXECUTION_RUN_CREATED }
        assertEquals(TaskStatus.IN_PROGRESS.name, recoveryAudit.details["migratedFromStatus"])
    }

    @Test
    fun `updateRunStatus should reject invalid transitions`() {
        val executionService = newExecutionService()
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

    @Test
    fun `startAiExecution should create task scoped session with prompt context and waiting confirmation run`() {
        val workflowId = "wf-ai-start"
        val contextFile = tempDir.resolve("src/main/kotlin/demo/FeatureService.kt")
        Files.createDirectories(contextFile.parent)
        Files.writeString(
            contextFile,
            """
                package demo

                class FeatureService {
                    fun apply(): String = "done"
                }
            """.trimIndent(),
        )
        seedWorkflow(
            workflowId = workflowId,
            requirementsMarkdown = """
                # Requirements

                ## Summary
                Offline mode must stay available for task execution.

                ## Clarifications
                - The workflow must remain file-first.
            """.trimIndent(),
            designMarkdown = """
                # Design

                ## Decisions
                - Use task-scoped execution runs for AI execution.
            """.trimIndent(),
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Prepare base
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles:
                  - src/main/kotlin/demo/FeatureService.kt
                verificationResult: null
                ```
                - [x] Done.

                ### T-002: Execute AI task
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn:
                  - T-001
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Finish the feature.
            """.trimIndent(),
        )

        var capturedRequest: SpecTaskExecutionService.TaskExecutionChatRequest? = null
        val executionService = newExecutionService(
            relatedFilesResolver = { _, _ -> listOf("src/main/kotlin/demo/FeatureService.kt") },
            chatExecutor = { request ->
                capturedRequest = request
                LlmResponse(
                    content = "Implemented demo feature. Suggested relatedFiles: src/main/kotlin/demo/FeatureService.kt",
                    model = "mock-model-v1",
                    usage = LlmUsage(promptTokens = 10, completionTokens = 5),
                    finishReason = "stop",
                )
            },
        )

        val result = executionService.startAiExecution(
            workflowId = workflowId,
            taskId = "T-002",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
            auditContext = mapOf("triggerSource" to "TEST"),
        )

        val loadedWorkflow = storage.loadWorkflow(workflowId).getOrThrow()
        val latestTask = tasksService.parse(workflowId).first { task -> task.id == "T-002" }
        val persistedTasks = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val session = sessionManager.listSessions().single()
        val messages = sessionManager.listMessages(session.id)
        val userMetadata = TaskExecutionSessionMetadataCodec.decode(messages.first().metadataJson)

        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, result.run.status)
        assertEquals(
            TaskExecutionRunStatus.WAITING_CONFIRMATION,
            loadedWorkflow.taskExecutionRuns.single { run -> run.taskId == "T-002" }.status,
        )
        assertEquals(TaskStatus.PENDING, latestTask.status)
        assertTrue(persistedTasks.contains("### T-002: Execute AI task"))
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))
        assertEquals(workflowId, session.specTaskId)
        assertEquals(workflowId, session.workflowChatBinding?.workflowId)
        assertEquals("T-002", session.workflowChatBinding?.taskId)
        assertEquals(StageId.IMPLEMENT, session.workflowChatBinding?.focusedStage)
        assertEquals(WorkflowChatEntrySource.TASK_PANEL, session.workflowChatBinding?.source)
        assertEquals(WorkflowChatActionIntent.EXECUTE_TASK, session.workflowChatBinding?.actionIntent)
        assertTrue(session.title.contains("T-002"))
        assertEquals(listOf(ConversationRole.USER, ConversationRole.ASSISTANT), messages.map { it.role })
        assertTrue(messages.first().content.contains("Task ID: T-002"))
        assertTrue(messages.first().content.contains("T-001 · COMPLETED · Prepare base"))
        assertTrue(messages.first().content.contains("requirements.md:"))
        assertTrue(messages.first().content.contains("Use task-scoped execution runs for AI execution."))
        assertTrue(messages.first().content.contains("src/main/kotlin/demo/FeatureService.kt"))
        assertTrue(messages.last().content.contains("Implemented demo feature"))
        assertEquals(result.run.runId, userMetadata.runId)
        assertEquals("T-002", userMetadata.taskId)
        assertEquals(workflowId, userMetadata.workflowId)
        assertEquals(ExecutionTrigger.USER_EXECUTE, userMetadata.trigger)
        assertEquals(result.requestId, userMetadata.requestId)
        assertNotNull(capturedRequest)
        assertEquals(result.requestId, capturedRequest!!.requestId)
        assertEquals("mock-model-v1", capturedRequest!!.modelId)
        assertTrue(capturedRequest!!.userInput.contains("EXECUTE_WITH_AI"))
        assertEquals(
            "src/main/kotlin/demo/FeatureService.kt",
            capturedRequest!!.contextSnapshot?.items?.single()?.label,
        )
    }

    @Test
    fun `startAiExecution should fail run and block task when chat execution fails`() {
        val workflowId = "wf-ai-fail"
        seedWorkflow(workflowId)
        val executionService = newExecutionService(
            chatExecutor = {
                throw IllegalStateException("CLI unavailable")
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            executionService.startAiExecution(
                workflowId = workflowId,
                taskId = "T-001",
                providerId = "mock",
                modelId = "mock-model-v1",
                operationMode = OperationMode.AUTO,
            )
        }

        val runs = executionService.listRuns(workflowId, "T-001")
        val task = tasksService.parse(workflowId).first { it.id == "T-001" }
        val session = sessionManager.listSessions().single()
        val messages = sessionManager.listMessages(session.id)

        assertEquals("CLI unavailable", error.message)
        assertEquals(TaskExecutionRunStatus.FAILED, runs.single().status)
        assertEquals(TaskStatus.BLOCKED, task.status)
        assertEquals(listOf(ConversationRole.USER, ConversationRole.SYSTEM), messages.map { it.role })
        assertTrue(messages.last().content.contains("CLI unavailable"))
    }

    @Test
    fun `retryAiExecution should create retry run and include previous run context`() {
        val workflowId = "wf-ai-retry"
        seedWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Retry me
                ```spec-task
                status: BLOCKED
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Retry later.
            """.trimIndent(),
        )
        val bootstrapService = newExecutionService()
        val previousRun = bootstrapService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.FAILED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            finishedAt = "2026-03-13T12:05:00Z",
            summary = "First attempt failed.",
        )
        var capturedRequest: SpecTaskExecutionService.TaskExecutionChatRequest? = null
        val executionService = newExecutionService(
            chatExecutor = { request ->
                capturedRequest = request
                LlmResponse(
                    content = "Retried task successfully.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
        )

        val result = executionService.retryAiExecution(
            workflowId = workflowId,
            taskId = "T-001",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
            previousRunId = previousRun.runId,
        )

        val runs = executionService.listRuns(workflowId, "T-001")
        val latestTask = tasksService.parse(workflowId).first { it.id == "T-001" }
        val session = sessionManager.getSession(result.sessionId)
        val userMessage = sessionManager.listMessages(result.sessionId).first()
        val userMetadata = TaskExecutionSessionMetadataCodec.decode(userMessage.metadataJson)

        assertEquals(2, runs.size)
        assertEquals(ExecutionTrigger.USER_RETRY, result.run.trigger)
        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, result.run.status)
        assertEquals(TaskStatus.PENDING, latestTask.status)
        assertEquals(workflowId, session?.workflowChatBinding?.workflowId)
        assertEquals("T-001", session?.workflowChatBinding?.taskId)
        assertEquals(WorkflowChatActionIntent.RETRY_TASK, session?.workflowChatBinding?.actionIntent)
        assertTrue(userMessage.content.contains("Previous run ID: ${previousRun.runId}"))
        assertTrue(userMessage.content.contains("Previous summary: First attempt failed."))
        assertEquals(previousRun.runId, userMetadata.previousRunId)
        assertEquals(previousRun.runId, result.previousRunId)
        assertNotNull(capturedRequest)
        assertTrue(capturedRequest!!.userInput.contains("RETRY_EXECUTION"))
    }

    @Test
    fun `resolveWaitingConfirmationRun should mark latest pending confirmation run as succeeded`() {
        val workflowId = "wf-run-resolve"
        seedWorkflow(workflowId)
        val executionService = newExecutionService()
        val waitingRun = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            summary = "Ready for completion",
        )

        val resolvedRun = executionService.resolveWaitingConfirmationRun(
            workflowId = workflowId,
            taskId = "T-001",
            summary = "Completed from test",
        )

        val runs = executionService.listRuns(workflowId, "T-001")
        assertEquals(waitingRun.runId, resolvedRun?.runId)
        assertEquals(TaskExecutionRunStatus.SUCCEEDED, resolvedRun?.status)
        assertEquals(TaskExecutionRunStatus.SUCCEEDED, runs.single().status)
        assertEquals("Completed from test", runs.single().summary)
    }

    private fun newExecutionService(
        relatedFilesResolver: (String, List<String>) -> List<String> = { _, existing -> existing },
        chatExecutor: suspend (SpecTaskExecutionService.TaskExecutionChatRequest) -> LlmResponse = {
            LlmResponse(
                content = "Default assistant reply.",
                model = "mock-model-v1",
                usage = LlmUsage(),
                finishReason = "stop",
            )
        },
    ): SpecTaskExecutionService {
        return SpecTaskExecutionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = relatedFilesResolver,
            projectService = projectService,
            sessionManager = sessionManager,
            chatExecutor = chatExecutor,
        )
    }

    private fun seedWorkflow(
        workflowId: String,
        requirementsMarkdown: String? = null,
        designMarkdown: String? = null,
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
                    StageId.REQUIREMENTS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:30:00Z",
                        completedAt = "2026-03-13T07:40:00Z",
                    ),
                    StageId.DESIGN to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:40:00Z",
                        completedAt = "2026-03-13T07:50:00Z",
                    ),
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
        requirementsMarkdown?.let { markdown ->
            artifactService.writeArtifact(workflowId, StageId.REQUIREMENTS, markdown)
        }
        designMarkdown?.let { markdown ->
            artifactService.writeArtifact(workflowId, StageId.DESIGN, markdown)
        }
        artifactService.writeArtifact(workflowId, StageId.TASKS, tasksMarkdown)
    }
}
