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
}
