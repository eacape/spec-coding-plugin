package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
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
        val workflowTitle = "CBA档案室"
        val documentTitle = "Implement Document"

        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to SpecDocument(
                    id = "doc-implement",
                    phase = SpecPhase.IMPLEMENT,
                    content = "## 任务列表\n- [ ] Task 1: ...",
                    metadata = SpecMetadata(
                        title = documentTitle,
                        description = "doc",
                    ),
                    validationResult = ValidationResult(valid = true),
                )
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
        assertTrue(yamlContent.contains("title: $workflowTitle"))
        assertTrue(yamlContent.contains("    title: $documentTitle"))

        val loaded = SpecStorage.getInstance(project).loadWorkflow(workflowId).getOrThrow()
        assertEquals(workflowTitle, loaded.title)
        assertEquals("desc", loaded.description)
        assertEquals(SpecPhase.IMPLEMENT, loaded.currentPhase)
    }

    @Test
    fun `loadWorkflow should preserve change intent and baseline workflow metadata`() {
        val workflowId = "wf-incremental"
        val baselineWorkflowId = "wf-baseline"
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "NBA 档案室增量",
            description = "补充赛季维度的统计口径",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = baselineWorkflowId,
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

        val loaded = SpecStorage.getInstance(project).loadWorkflow(workflowId).getOrThrow()
        assertEquals(SpecChangeIntent.INCREMENTAL, loaded.changeIntent)
        assertEquals(baselineWorkflowId, loaded.baselineWorkflowId)
    }

    @Test
    fun `loadWorkflow should preserve clarification retry state metadata`() {
        val workflowId = "wf-retry"
        val retryState = ClarificationRetryState(
            input = "请生成需求文档",
            confirmedContext = "**已确认澄清项**\n- 支持离线",
            questionsMarkdown = "1. 是否需要离线缓存？",
            structuredQuestions = listOf("是否需要离线缓存？", "是否需要多语言？"),
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
}
