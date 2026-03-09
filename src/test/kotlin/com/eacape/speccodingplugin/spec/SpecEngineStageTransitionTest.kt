package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
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

    @Test
    fun `advanceWorkflow should use rule severity override from project config`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            rules:
              document-validation:
                severity: WARNING
            """.trimIndent(),
        )
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Rule Override Workflow",
            description = "warning override",
        ).getOrThrow()

        storage.saveDocument(created.id, invalidRequirementsDocument()).getOrThrow()
        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageId.REQUIREMENTS, reloaded.currentStage)

        val blocked = engine.advanceWorkflow(created.id)
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull() is StageWarningConfirmationRequiredError)

        val result = engine.advanceWorkflow(created.id) { true }.getOrThrow()

        assertEquals(GateStatus.WARNING, result.gateResult.status)
        assertFalse(result.gateResult.ruleResults.isEmpty())
        val ruleResult = result.gateResult.ruleResults.first { it.ruleId == "document-validation" }
        assertTrue(ruleResult.severityOverridden)
        assertEquals(GateStatus.WARNING, ruleResult.effectiveSeverity)
        assertTrue(ruleResult.violations.isNotEmpty())
        assertTrue(ruleResult.violations.all { it.severity == GateStatus.WARNING })
        assertTrue(ruleResult.violations.all { !it.fixHint.isNullOrBlank() })
    }

    @Test
    fun `advanceWorkflow should block when active artifact uses non canonical file name`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Naming Workflow",
            description = "fixed naming",
        ).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(created.id)
        val canonicalPath = workflowDir.resolve("requirements.md")
        val aliasPath = workflowDir.resolve("requirement.md")
        Files.move(canonicalPath, aliasPath)

        val blocked = engine.advanceWorkflow(created.id)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        assertEquals(GateStatus.ERROR, gateResult.status)
        val namingRule = gateResult.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertEquals(1, namingRule.violations.size)
        assertEquals("requirement.md", namingRule.violations.single().fileName)
        assertTrue(namingRule.violations.single().message.contains("requirements.md"))
        val requiredRule = gateResult.ruleResults.first { it.ruleId == "artifact-required" }
        assertTrue(requiredRule.violations.isEmpty())
    }

    @Test
    fun `advanceWorkflow should block when tasks document violates structured task syntax`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Tasks Rule Workflow",
            description = "tasks syntax",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate requirements").collect()
        }
        engine.advanceWorkflow(created.id).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate design").collect()
        }
        engine.advanceWorkflow(created.id).getOrThrow()

        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(created.id)
        Files.writeString(
            workflowDir.resolve("tasks.md"),
            """
                ## 任务列表

                ### T-001 Broken heading
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val blocked = engine.advanceWorkflow(created.id)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        assertEquals(GateStatus.ERROR, gateResult.status)
        val syntaxRule = gateResult.ruleResults.first { it.ruleId == "tasks-syntax" }
        assertEquals(1, syntaxRule.violations.size)
        assertTrue(syntaxRule.violations.single().message.contains("### T-001: Title"))
    }

    @Test
    fun `advanceWorkflow should audit downgraded verify conclusion before warning confirmation`() {
        writeProjectConfig(
            """
                schemaVersion: 1
                rules:
                  verify-conclusion:
                    severity: WARNING
            """.trimIndent(),
        )
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val workflowId = "spec-test-verify-downgrade-audit"
        persistVerifyWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                ## 任务列表

                ### T-001: Verify downgrade
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult:
                  conclusion: FAIL
                  runId: verify-001
                  summary: regression suite failed
                  at: "2026-03-09T12:00:00Z"
                ```
            """.trimIndent() + "\n",
        )

        val blocked = engine.advanceWorkflow(workflowId)

        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull() is StageWarningConfirmationRequiredError)
        val downgradeEvent = loadAuditEvents(workflowId)
            .last { it.eventType == SpecAuditEventType.GATE_RULE_DOWNGRADED }
        assertEquals("VERIFY", downgradeEvent.details["fromStage"])
        assertEquals("ARCHIVE", downgradeEvent.details["toStage"])
        assertEquals("1", downgradeEvent.details["downgradeCount"])
        assertEquals("verify-conclusion:ERROR->WARNING", downgradeEvent.details["downgradedRules"])
        assertTrue(downgradeEvent.details.getValue("downgradedViolations").contains("tasks.md"))

        val result = engine.advanceWorkflow(workflowId) { true }.getOrThrow()

        assertEquals(GateStatus.WARNING, result.gateResult.status)
        val verifyRule = result.gateResult.ruleResults.first { it.ruleId == "verify-conclusion" }
        assertEquals(GateStatus.WARNING, verifyRule.violations.single().severity)
        assertEquals(GateStatus.ERROR, verifyRule.violations.single().originalSeverity)
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

    private fun writeProjectConfig(raw: String) {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "$raw\n", StandardCharsets.UTF_8)
    }

    private fun persistVerifyWorkflow(workflowId: String, tasksMarkdown: String) {
        val stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = true))
        val stageStates = stagePlan.initialStageStates("2026-03-09T00:00:00Z").toMutableMap()
        stageStates[StageId.REQUIREMENTS] = stageStates.getValue(StageId.REQUIREMENTS).copy(status = StageProgress.DONE)
        stageStates[StageId.DESIGN] = stageStates.getValue(StageId.DESIGN).copy(status = StageProgress.DONE)
        stageStates[StageId.TASKS] = stageStates.getValue(StageId.TASKS).copy(status = StageProgress.DONE)
        stageStates[StageId.IMPLEMENT] = stageStates.getValue(StageId.IMPLEMENT).copy(status = StageProgress.DONE)
        stageStates[StageId.VERIFY] = stageStates.getValue(StageId.VERIFY).copy(status = StageProgress.IN_PROGRESS)
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Verify Downgrade Workflow",
            description = "verify conclusion audit",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = stageStates,
            currentStage = StageId.VERIFY,
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(workflowId)
        Files.writeString(workflowDir.resolve("tasks.md"), tasksMarkdown, StandardCharsets.UTF_8)
        Files.writeString(
            workflowDir.resolve("verification.md"),
            "# Verification Document\n\n## Result\n- FAIL\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun invalidRequirementsDocument(): SpecDocument {
        return SpecDocument(
            id = "invalid-requirements",
            phase = SpecPhase.SPECIFY,
            content = "不完整的需求",
            metadata = SpecMetadata(
                title = "Invalid Requirements",
                description = "missing required sections",
            ),
        )
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

                ### T-001: 更新状态机
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] 编写状态机实现

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
