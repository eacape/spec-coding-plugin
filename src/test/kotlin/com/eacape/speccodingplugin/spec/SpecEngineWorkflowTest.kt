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

class SpecEngineWorkflowTest {

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
    fun `can complete full specify-design-implement flow and persist documents`() {
        val engine = SpecEngine(project, storage) { request ->
            val content = when (request.phase) {
                SpecPhase.SPECIFY -> """
                    ## 功能需求
                    - 用户可创建任务

                    ## 非功能需求
                    - 响应时间 < 1s

                    ## 用户故事
                    As a user, I want to create tasks, so that I can track work.

                    ## 验收标准
                    - [ ] 创建成功
                """.trimIndent()

                SpecPhase.DESIGN -> """
                    ## 架构设计
                    - 三层架构

                    ## 技术选型
                    - Kotlin + IntelliJ Platform SDK

                    ## 数据模型
                    data class Task(val id: String, val title: String)

                    ## API 设计
                    - createTask(title: String)

                    ## 非功能需求
                    - 安全、性能、可扩展
                """.trimIndent()

                SpecPhase.IMPLEMENT -> """
                    ## 任务列表
                    - [ ] Task 1: 实现数据模型（1h）
                    - [ ] Task 2: 实现服务层（2h）
                    - [ ] Task 3: 实现 UI 交互（2h）

                    ## 实现步骤
                    1. 先建模
                    2. 再实现服务
                    3. 最后接入 UI

                    ## 测试计划
                    - 单元测试
                    - 集成测试
                """.trimIndent()
            }

            val doc = SpecDocument(
                id = "doc-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
                validationResult = SpecValidator.validate(
                    SpecDocument(
                        id = "validate-${request.phase.name.lowercase()}",
                        phase = request.phase,
                        content = content,
                        metadata = SpecMetadata(
                            title = "validate",
                            description = "validate",
                        ),
                    )
                ),
            )

            SpecGenerationResult.Success(doc)
        }

        val created = engine.createWorkflow(
            title = "Todo App",
            description = "A simple todo workflow",
        ).getOrThrow()

        assertEquals(SpecPhase.SPECIFY, created.currentPhase)
        assertEquals("Todo App", created.title)
        assertEquals("A simple todo workflow", created.description)

        runBlocking {
            engine.generateCurrentPhase(created.id, "build a todo app").collect()
        }
        val afterSpecify = engine.loadWorkflow(created.id).getOrThrow()
        assertNotNull(afterSpecify.getDocument(SpecPhase.SPECIFY))
        assertTrue(afterSpecify.getDocument(SpecPhase.SPECIFY)?.validationResult?.valid == true)

        engine.proceedToNextPhase(created.id).getOrThrow()
        assertEquals(SpecPhase.DESIGN, engine.loadWorkflow(created.id).getOrThrow().currentPhase)

        runBlocking {
            engine.generateCurrentPhase(created.id, "design based on requirements").collect()
        }
        val afterDesign = engine.loadWorkflow(created.id).getOrThrow()
        assertNotNull(afterDesign.getDocument(SpecPhase.DESIGN))
        assertTrue(afterDesign.getDocument(SpecPhase.DESIGN)?.validationResult?.valid == true)

        engine.proceedToNextPhase(created.id).getOrThrow()
        assertEquals(SpecPhase.IMPLEMENT, engine.loadWorkflow(created.id).getOrThrow().currentPhase)

        runBlocking {
            engine.generateCurrentPhase(created.id, "implement tasks").collect()
        }
        val afterImplement = engine.loadWorkflow(created.id).getOrThrow()
        assertNotNull(afterImplement.getDocument(SpecPhase.IMPLEMENT))
        assertTrue(afterImplement.getDocument(SpecPhase.IMPLEMENT)?.validationResult?.valid == true)

        val completed = engine.completeWorkflow(created.id).getOrThrow()
        assertEquals(WorkflowStatus.COMPLETED, completed.status)

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(created.id)

        assertTrue(Files.exists(workflowDir.resolve("requirements.md")))
        assertTrue(Files.exists(workflowDir.resolve("design.md")))
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertTrue(Files.exists(workflowDir.resolve("workflow.yaml")))
    }

    @Test
    fun `cannot proceed to next phase before current phase document is generated`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val created = engine.createWorkflow(
            title = "Gate Test",
            description = "Transition gate",
        ).getOrThrow()

        val result = engine.proceedToNextPhase(created.id)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("Cannot proceed to next phase"))
    }

    @Test
    fun `proceed to next phase should return validation details when current document is invalid`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val created = engine.createWorkflow(
            title = "Detail Validation",
            description = "Validation detail surface",
        ).getOrThrow()

        engine.updateDocumentContent(
            workflowId = created.id,
            phase = SpecPhase.SPECIFY,
            content = """
                ## 功能需求
                - 仅保留功能需求和用户故事

                ## 用户故事
                As a user, I want to search archives quickly.
            """.trimIndent(),
        ).getOrThrow()

        val result = engine.proceedToNextPhase(created.id)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("Current phase validation failed"))
        assertTrue(message.contains("非功能需求"))
    }

    @Test
    fun `updateDocumentContent should persist edits and enforce revision conflict`() {
        val engine = SpecEngine(project, storage) { request ->
            val content = """
                ## 功能需求
                - 用户可以创建订单并查看详情
                - 系统支持订单状态跟踪与通知

                ## 非功能需求
                - 接口响应时间 < 500ms
                - 关键操作必须记录审计日志
                - 核心接口具备限流和重试机制

                ## 用户故事
                As a buyer, I want to submit and track orders, so that I can complete checkout confidently.

                ## 验收标准
                - [ ] 下单成功后可在历史列表查看详情
                - [ ] 订单状态变化时可收到通知
            """.trimIndent()

            val candidate = SpecDocument(
                id = "doc-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(
                candidate.copy(validationResult = SpecValidator.validate(candidate))
            )
        }

        val created = engine.createWorkflow(
            title = "Order Flow",
            description = "Order processing workflow",
        ).getOrThrow()
        runBlocking {
            engine.generateCurrentPhase(created.id, "generate specify").collect()
        }

        val beforeEdit = engine.loadWorkflow(created.id).getOrThrow()
        val beforeDocument = beforeEdit.getDocument(SpecPhase.SPECIFY)
        assertNotNull(beforeDocument)
        val beforeRevision = beforeDocument!!.metadata.updatedAt

        val editedText = """
            ## 功能需求
            - 支持订单创建、取消、查询

            ## 非功能需求
            - 延迟小于 300ms

            ## 用户故事
            As a buyer, I want to update an order after creation, so that I can fix mistakes quickly.
        """.trimIndent()
        val updatedWorkflow = engine.updateDocumentContent(
            workflowId = created.id,
            phase = SpecPhase.SPECIFY,
            content = editedText,
            expectedRevision = beforeRevision,
        ).getOrThrow()

        val updatedDocument = updatedWorkflow.getDocument(SpecPhase.SPECIFY)
        assertNotNull(updatedDocument)
        assertTrue(updatedDocument!!.content.contains("支持订单创建、取消、查询"))
        assertTrue(updatedDocument.metadata.updatedAt >= beforeRevision)

        val persisted = storage.loadDocument(created.id, SpecPhase.SPECIFY).getOrThrow()
        assertTrue(persisted.content.contains("支持订单创建、取消、查询"))

        val conflict = engine.updateDocumentContent(
            workflowId = created.id,
            phase = SpecPhase.SPECIFY,
            content = "## 功能需求\n- stale write",
            expectedRevision = beforeRevision,
        )
        assertTrue(conflict.isFailure)
        assertTrue(
            conflict.exceptionOrNull()?.message?.contains("revision conflict", ignoreCase = true) == true
        )
    }

    @Test
    fun `generateCurrentPhase should forward confirmed clarification context to generator`() {
        var capturedConfirmedContext: String? = null
        val engine = SpecEngine(project, storage, generationHandler = { request ->
            capturedConfirmedContext = request.options.confirmedContext
            val content = """
                ## 功能需求
                - 用户可以创建任务
                
                ## 非功能需求
                - 响应时间 < 1s
                
                ## 用户故事
                As a user, I want to create tasks, so that I can track work.
                
                ## 验收标准
                - [ ] 创建成功
            """.trimIndent()
            val candidate = SpecDocument(
                id = "doc-specify",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
        })

        val workflow = engine.createWorkflow(
            title = "Clarify Context",
            description = "Forward confirmed context",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflow.id,
                input = "build a todo app",
                options = GenerationOptions(
                    confirmedContext = "API 必须支持幂等 key；数据库使用 PostgreSQL",
                ),
            ).collect()
        }

        assertEquals("API 必须支持幂等 key；数据库使用 PostgreSQL", capturedConfirmedContext)
    }
}
