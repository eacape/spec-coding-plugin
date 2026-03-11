package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecStorageWorkflowMetadataTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `loadWorkflow should not override workflow title with document title entries`() {
        val workflowId = "wf-title"
        val workflowTitle = "CBA Archive"
        val documentTitle = "Implement Document"

        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to SpecDocument(
                    id = "doc-implement",
                    phase = SpecPhase.IMPLEMENT,
                    content = "## Task List\n- [ ] Task 1: ...",
                    metadata = SpecMetadata(
                        title = documentTitle,
                        description = "doc",
                    ),
                    validationResult = ValidationResult(valid = true),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = workflowTitle,
            description = "desc",
            createdAt = 1234567890L,
            updatedAt = 1234567891L,
        )

        storage.saveWorkflow(workflow).getOrThrow()

        val yamlPath = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve("workflow.yaml")
        val yamlContent = Files.readString(yamlPath)
        assertTrue(yamlContent.contains("schemaVersion: 1"))
        assertTrue(yamlContent.contains("title: $workflowTitle"))
        assertTrue(yamlContent.contains("title: $documentTitle"))

        val loaded = SpecStorage.getInstance(project).loadWorkflow(workflowId).getOrThrow()
        assertEquals(workflowTitle, loaded.title)
        assertEquals("desc", loaded.description)
        assertEquals(SpecPhase.IMPLEMENT, loaded.currentPhase)
    }

    @Test
    fun `loadWorkflow should preserve change intent and baseline workflow metadata`() {
        val workflowId = "wf-incremental"
        val baselineWorkflowId = "wf-baseline"
        val configPinHash = "6f8d5d99145e2db6bbf06f57a43a837cd7f4a0f7eaee1cf5bd0c302f49d53b39"
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "NBA Incremental",
            description = "add seasonal metrics",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = baselineWorkflowId,
            configPinHash = configPinHash,
            createdAt = 1700000000000L,
            updatedAt = 1700000005000L,
        )

        storage.saveWorkflow(workflow).getOrThrow()

        val yamlPath = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve("workflow.yaml")
        val yamlContent = Files.readString(yamlPath)
        assertTrue(yamlContent.contains("changeIntent: INCREMENTAL"))
        assertTrue(yamlContent.contains("baselineWorkflowId: $baselineWorkflowId"))
        assertTrue(yamlContent.contains("configPinHash: $configPinHash"))

        val loaded = SpecStorage.getInstance(project).loadWorkflow(workflowId).getOrThrow()
        assertEquals(SpecChangeIntent.INCREMENTAL, loaded.changeIntent)
        assertEquals(baselineWorkflowId, loaded.baselineWorkflowId)
        assertEquals(configPinHash, loaded.configPinHash)
    }

    @Test
    fun `loadWorkflow should preserve clarification retry state metadata`() {
        val workflowId = "wf-retry"
        val retryState = ClarificationRetryState(
            input = "generate requirement document",
            confirmedContext = "**Confirmed context**\n- support offline",
            questionsMarkdown = "1. Need offline cache?",
            structuredQuestions = listOf("Need offline cache?", "Need i18n?"),
            clarificationRound = 2,
            lastError = "request interrupted",
            confirmed = true,
        )
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Retry Workflow",
            description = "retry state persist",
            clarificationRetryState = retryState,
            createdAt = 1700000010000L,
            updatedAt = 1700000015000L,
        )

        storage.saveWorkflow(workflow).getOrThrow()

        val yamlPath = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve("workflow.yaml")
        val yamlContent = Files.readString(yamlPath)
        assertTrue(yamlContent.contains("clarificationRetryState:"))

        val loaded = SpecStorage.getInstance(project).loadWorkflow(workflowId).getOrThrow()
        val loadedRetry = loaded.clarificationRetryState
        assertNotNull(loadedRetry)
        assertEquals(retryState.input, loadedRetry?.input)
        assertEquals(retryState.confirmedContext, loadedRetry?.confirmedContext)
        assertEquals(retryState.questionsMarkdown, loadedRetry?.questionsMarkdown)
        assertEquals(retryState.structuredQuestions, loadedRetry?.structuredQuestions)
        assertEquals(retryState.clarificationRound, loadedRetry?.clarificationRound)
        assertEquals(retryState.lastError, loadedRetry?.lastError)
        assertEquals(retryState.confirmed, loadedRetry?.confirmed)
    }

    @Test
    fun `loadWorkflow should preserve template stage metadata and current stage`() {
        val workflowId = "wf-stage-metadata"
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.DESIGN,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Stage metadata",
            description = "persist stage states",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.DESIGN,
            verifyEnabled = false,
            stageStates = linkedMapOf(
                StageId.REQUIREMENTS to StageState(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = "2026-03-08T09:00:00Z",
                    completedAt = "2026-03-08T09:05:00Z",
                ),
                StageId.DESIGN to StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = "2026-03-08T09:06:00Z",
                    completedAt = null,
                ),
                StageId.TASKS to StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                ),
                StageId.VERIFY to StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                ),
            ),
        )

        storage.saveWorkflow(workflow).getOrThrow()
        val loaded = storage.loadWorkflow(workflowId).getOrThrow()
        assertEquals(WorkflowTemplate.FULL_SPEC, loaded.template)
        assertEquals(StageId.DESIGN, loaded.currentStage)
        assertFalse(loaded.verifyEnabled)
        assertEquals(StageProgress.DONE, loaded.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertEquals(StageProgress.IN_PROGRESS, loaded.stageStates.getValue(StageId.DESIGN).status)
        assertFalse(loaded.stageStates.getValue(StageId.VERIFY).active)
    }

    @Test
    fun `loadWorkflow should upgrade legacy metadata without schemaVersion or currentStage`() {
        val workflowId = "wf-legacy-quick-task"
        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
        Files.createDirectories(workflowDir)
        Files.writeString(
            workflowDir.resolve("workflow.yaml"),
            """
            id: $workflowId
            title: Legacy quick task
            description: missing schema version
            template: QUICK_TASK
            currentPhase: IMPLEMENT
            status: IN_PROGRESS
            verifyEnabled: true
            createdAt: 1700000000000
            updatedAt: 1700000005000
            documents: []
            """.trimIndent() + "\n",
        )

        val loaded = storage.loadWorkflow(workflowId).getOrThrow()

        assertEquals(WorkflowTemplate.QUICK_TASK, loaded.template)
        assertEquals(StageId.TASKS, loaded.currentStage)
        assertEquals(StageProgress.IN_PROGRESS, loaded.stageStates.getValue(StageId.TASKS).status)
        assertEquals(StageProgress.NOT_STARTED, loaded.stageStates.getValue(StageId.IMPLEMENT).status)
        assertTrue(loaded.stageStates.getValue(StageId.VERIFY).active)
        assertFalse(loaded.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertFalse(loaded.stageStates.getValue(StageId.DESIGN).active)
    }

    @Test
    fun `listWorkflowMetadata and openWorkflow should return persisted stage metadata`() {
        val workflow = SpecWorkflow(
            id = "wf-meta-open",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.PAUSED,
            title = "Open me",
            description = "metadata list/open",
            template = WorkflowTemplate.QUICK_TASK,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
            stageStates = linkedMapOf(
                StageId.TASKS to StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = "2026-03-08T11:00:00Z",
                ),
                StageId.IMPLEMENT to StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                ),
                StageId.VERIFY to StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                ),
                StageId.ARCHIVE to StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                ),
            ),
        )
        storage.saveWorkflow(workflow).getOrThrow()

        val listed = storage.listWorkflowMetadata()
        val listedMeta = listed.firstOrNull { it.workflowId == workflow.id }
        assertNotNull(listedMeta)
        assertEquals(WorkflowTemplate.QUICK_TASK, listedMeta?.template)
        assertEquals(StageId.TASKS, listedMeta?.currentStage)
        assertTrue(listedMeta?.verifyEnabled == true)

        val opened = storage.openWorkflow(workflow.id).getOrThrow()
        assertEquals(workflow.id, opened.meta.workflowId)
        assertEquals(StageId.TASKS, opened.meta.currentStage)
        assertEquals(StageProgress.IN_PROGRESS, opened.meta.stageStates.getValue(StageId.TASKS).status)
        assertTrue(opened.documents.isEmpty())
    }
}
