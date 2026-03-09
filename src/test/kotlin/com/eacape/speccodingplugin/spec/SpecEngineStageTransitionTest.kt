package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineStageTransitionTest {

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
    fun `advanceWorkflow should persist stage transition audit and snapshots`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Advance Workflow",
            description = "stage advance",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate requirements").collect()
        }

        val result = engine.advanceWorkflow(created.id).getOrThrow()

        assertEquals(StageTransitionType.ADVANCE, result.transitionType)
        assertEquals(StageId.REQUIREMENTS, result.fromStage)
        assertEquals(StageId.DESIGN, result.targetStage)
        assertEquals(GateStatus.PASS, result.gateResult.status)
        assertNotNull(result.beforeSnapshotId)
        assertNotNull(result.afterSnapshotId)

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageId.DESIGN, reloaded.currentStage)
        assertEquals(StageProgress.DONE, reloaded.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertEquals(StageProgress.IN_PROGRESS, reloaded.stageStates.getValue(StageId.DESIGN).status)

        val event = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.STAGE_ADVANCED }
        assertEquals("REQUIREMENTS", event.details["fromStage"])
        assertEquals("DESIGN", event.details["toStage"])
        assertEquals("PASS", event.details["gateStatus"])
        assertEquals(result.beforeSnapshotId, event.details["beforeSnapshotId"])
        assertEquals(result.afterSnapshotId, event.details["afterSnapshotId"])
    }

    @Test
    fun `jumpToStage should require warning confirmation and mark skipped stages done`() {
        val warningGate = SpecStageGateEvaluator {
            GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "warn-only",
                        severity = GateStatus.WARNING,
                        fileName = "requirements.md",
                        line = 1,
                        message = "warning for confirmation",
                    ),
                ),
            )
        }
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { SpecGenerationResult.Failure("unused") },
            stageGateEvaluator = warningGate,
        )
        val created = engine.createWorkflow(
            title = "Jump Workflow",
            description = "warning jump",
        ).getOrThrow()

        val rejected = engine.jumpToStage(created.id, StageId.TASKS)
        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull() is StageWarningConfirmationRequiredError)

        val result = engine.jumpToStage(created.id, StageId.TASKS) { true }.getOrThrow()

        assertEquals(StageTransitionType.JUMP, result.transitionType)
        assertTrue(result.warningConfirmed)
        assertEquals(GateStatus.WARNING, result.gateResult.status)

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageId.TASKS, reloaded.currentStage)
        assertEquals(StageProgress.DONE, reloaded.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertEquals(StageProgress.DONE, reloaded.stageStates.getValue(StageId.DESIGN).status)
        assertEquals(StageProgress.IN_PROGRESS, reloaded.stageStates.getValue(StageId.TASKS).status)

        val event = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.STAGE_JUMPED }
        assertEquals("WARNING", event.details["gateStatus"])
        assertEquals("true", event.details["warningConfirmed"])
        assertEquals("REQUIREMENTS,DESIGN", event.details["evaluatedStages"])
    }

    @Test
    fun `rollbackToStage should reset later stage metadata without deleting artifacts`() {
        val passGate = SpecStageGateEvaluator { GateResult.fromViolations(emptyList()) }
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { SpecGenerationResult.Failure("unused") },
            stageGateEvaluator = passGate,
        )
        val created = engine.createWorkflow(
            title = "Rollback Workflow",
            description = "rollback metadata",
        ).getOrThrow()

        engine.jumpToStage(created.id, StageId.IMPLEMENT).getOrThrow()
        val rolledBack = engine.rollbackToStage(created.id, StageId.DESIGN).getOrThrow()

        assertEquals(StageId.DESIGN, rolledBack.currentStage)
        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageProgress.DONE, reloaded.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertEquals(StageProgress.IN_PROGRESS, reloaded.stageStates.getValue(StageId.DESIGN).status)
        assertEquals(StageProgress.NOT_STARTED, reloaded.stageStates.getValue(StageId.TASKS).status)
        assertEquals(StageProgress.NOT_STARTED, reloaded.stageStates.getValue(StageId.IMPLEMENT).status)

        val tasksPath = tempDir.resolve(".spec-coding").resolve("specs").resolve(created.id).resolve("tasks.md")
        assertTrue(Files.exists(tasksPath))

        val event = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.STAGE_ROLLED_BACK }
        assertEquals("IMPLEMENT", event.details["fromStage"])
        assertEquals("DESIGN", event.details["toStage"])
        assertEquals("PASS", event.details["gateStatus"])
    }

    private fun loadAuditEvents(workflowId: String): List<SpecAuditEvent> {
        val auditPath = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve(".history")
            .resolve("audit.yaml")
        return SpecAuditLogCodec.decodeDocuments(Files.readString(auditPath))
    }

    private suspend fun generateValidDocument(request: SpecGenerationRequest): SpecGenerationResult {
        val content = when (request.phase) {
            SpecPhase.SPECIFY -> """
                ## 功能需求
                - 用户可推进工作流

                ## 非功能需求
                - 需要稳定审计

                ## 用户故事
                As a user, I want to advance stages safely.

                ## 验收标准
                - [ ] 可以推进到下一阶段
            """.trimIndent()

            SpecPhase.DESIGN -> """
                ## 架构设计
                - 状态机服务

                ## 技术选型
                - Kotlin

                ## 数据模型
                - StageTransitionResult

                ## API 设计
                - advanceWorkflow()

                ## 非功能设计
                - 审计与快照
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                ## 任务列表
                - [ ] Task 1: 更新状态机

                ## 实现步骤
                1. 编写状态机
                2. 增加测试

                ## 测试计划
                - [ ] 单元测试
            """.trimIndent()
        }
        val candidate = SpecDocument(
            id = "doc-${request.phase.name.lowercase()}",
            phase = request.phase,
            content = content,
            metadata = SpecMetadata(
                title = "${request.phase.displayName} Document",
                description = "generated",
            ),
        )
        return SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
    }
}
