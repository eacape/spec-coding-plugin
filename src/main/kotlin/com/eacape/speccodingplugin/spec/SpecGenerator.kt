package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.*
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec 文档生成器
 * 使用 LLM 生成各阶段的 Spec 文档
 */
class SpecGenerator(
    private val llmRouter: LlmRouter
) {
    private val logger = thisLogger()

    /**
     * 生成 Spec 文档
     */
    suspend fun generate(request: SpecGenerationRequest): SpecGenerationResult = withContext(Dispatchers.IO) {
        try {
            logger.info("Generating ${request.phase} document")

            // 构建 Prompt
            val prompt = buildPrompt(request)

            // 调用 LLM
            val llmRequest = LlmRequest(
                messages = listOf(
                    LlmMessage(LlmRole.SYSTEM, getSystemPrompt(request.phase)),
                    LlmMessage(LlmRole.USER, prompt)
                ),
                model = request.options.model,
                temperature = request.options.temperature,
                maxTokens = request.options.maxTokens
            )

            val response = runCatching { llmRouter.generate(providerId = null, request = llmRequest) }

            if (response.isFailure) {
                return@withContext SpecGenerationResult.Failure(
                    error = "LLM 调用失败",
                    details = response.exceptionOrNull()?.message
                )
            }

            val llmResponse = response.getOrThrow()
            val content = llmResponse.content

            // 创建文档
            val document = SpecDocument(
                id = "${System.currentTimeMillis()}-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document"
                )
            )

            // 验证文档
            if (request.options.validateOutput) {
                val validation = SpecValidator.validate(document)
                val validatedDocument = document.copy(validationResult = validation)

                if (!validation.valid) {
                    return@withContext SpecGenerationResult.ValidationFailed(
                        document = validatedDocument,
                        validation = validation
                    )
                }

                return@withContext SpecGenerationResult.Success(validatedDocument)
            }

            SpecGenerationResult.Success(document)
        } catch (e: Exception) {
            logger.warn("Failed to generate ${request.phase} document", e)
            SpecGenerationResult.Failure(
                error = "生成失败: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }

    /**
     * 构建生成 Prompt
     */
    private fun buildPrompt(request: SpecGenerationRequest): String {
        return when (request.phase) {
            SpecPhase.SPECIFY -> buildSpecifyPrompt(request)
            SpecPhase.DESIGN -> buildDesignPrompt(request)
            SpecPhase.IMPLEMENT -> buildImplementPrompt(request)
        }
    }

    /**
     * 构建 Specify 阶段 Prompt
     */
    private fun buildSpecifyPrompt(request: SpecGenerationRequest): String {
        return buildString {
            appendLine("请将以下自然语言需求转换为结构化的需求文档（requirements.md）：")
            appendLine()
            appendLine("```")
            appendLine(request.input)
            appendLine("```")
            appendLine()
            appendLine("要求：")
            appendLine("1. 提取功能需求（Functional Requirements）")
            appendLine("2. 提取非功能需求（Non-Functional Requirements）")
            appendLine("3. 编写用户故事（User Stories）格式：As a... I want... So that...")
            appendLine("4. 定义验收标准（Acceptance Criteria）")
            appendLine("5. 识别约束条件（Constraints）")
            appendLine("6. 使用 Markdown 格式，结构清晰")

            if (request.options.includeExamples) {
                appendLine()
                appendLine("参考格式：")
                appendLine(getSpecifyTemplate())
            }
        }
    }

    /**
     * 构建 Design 阶段 Prompt
     */
    private fun buildDesignPrompt(request: SpecGenerationRequest): String {
        return buildString {
            appendLine("请基于以下需求文档设计技术方案（design.md）：")
            appendLine()
            appendLine("## 需求文档")
            appendLine("```")
            appendLine(request.previousDocument?.content ?: request.input)
            appendLine("```")
            appendLine()
            appendLine("要求：")
            appendLine("1. 设计系统架构（Architecture Design）")
            appendLine("2. 选择技术栈（Technology Stack）")
            appendLine("3. 设计数据模型（Data Model）")
            appendLine("4. 设计 API 接口（API Design）")
            appendLine("5. 考虑非功能需求（性能、安全、可扩展性）")
            appendLine("6. 使用 Markdown 格式，包含架构图（使用 Mermaid 或文字描述）")

            if (request.options.includeExamples) {
                appendLine()
                appendLine("参考格式：")
                appendLine(getDesignTemplate())
            }
        }
    }

    /**
     * 构建 Implement 阶段 Prompt
     */
    private fun buildImplementPrompt(request: SpecGenerationRequest): String {
        return buildString {
            appendLine("请基于以下设计文档拆解实现任务（tasks.md）：")
            appendLine()
            appendLine("## 设计文档")
            appendLine("```")
            appendLine(request.previousDocument?.content ?: request.input)
            appendLine("```")
            appendLine()
            appendLine("要求：")
            appendLine("1. 将设计拆解为具体的开发任务")
            appendLine("2. 每个任务应该是可独立完成的")
            appendLine("3. 标记任务优先级（P0/P1/P2）")
            appendLine("4. 估算任务工时")
            appendLine("5. 定义任务依赖关系")
            appendLine("6. 包含测试计划")
            appendLine("7. 使用 Markdown Checkbox 格式")

            if (request.options.includeExamples) {
                appendLine()
                appendLine("参考格式：")
                appendLine(getImplementTemplate())
            }
        }
    }

    /**
     * 获取系统 Prompt
     */
    private fun getSystemPrompt(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> """
                你是一个专业的需求分析师。你的任务是将自然语言需求转换为结构化的需求文档。
                你需要：
                - 准确理解用户意图
                - 识别功能需求和非功能需求
                - 编写清晰的用户故事
                - 定义明确的验收标准
                - 使用专业的需求工程术语
            """.trimIndent()

            SpecPhase.DESIGN -> """
                你是一个资深的系统架构师。你的任务是基于需求文档设计技术方案。
                你需要：
                - 设计合理的系统架构
                - 选择合适的技术栈
                - 设计清晰的数据模型
                - 定义完整的 API 接口
                - 考虑性能、安全、可扩展性等非功能需求
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                你是一个经验丰富的技术负责人。你的任务是将设计方案拆解为可执行的任务。
                你需要：
                - 将设计拆解为具体的开发任务
                - 合理安排任务优先级
                - 估算任务工时
                - 识别任务依赖关系
                - 制定测试计划
            """.trimIndent()
        }
    }

    /**
     * 获取 Specify 阶段模板
     */
    private fun getSpecifyTemplate(): String {
        return """
            # 需求文档

            ## 功能需求

            ### FR-1: 用户登录
            - 用户可以使用邮箱和密码登录系统
            - 支持记住登录状态

            ## 非功能需求

            ### NFR-1: 性能
            - 登录响应时间 < 2 秒

            ## 用户故事

            ### US-1: 用户登录
            **As a** 注册用户
            **I want** 使用邮箱和密码登录
            **So that** 我可以访问个人账户

            **验收标准**:
            - [ ] 输入正确的邮箱和密码可以成功登录
            - [ ] 输入错误的密码显示错误提示
            - [ ] 可以选择记住登录状态
        """.trimIndent()
    }

    /**
     * 获取 Design 阶段模板
     */
    private fun getDesignTemplate(): String {
        return """
            # 设计文档

            ## 架构设计

            采用三层架构：
            - 表示层：Web UI
            - 业务层：业务逻辑处理
            - 数据层：数据持久化

            ## 技术选型

            - 后端：Kotlin + Spring Boot
            - 前端：React + TypeScript
            - 数据库：PostgreSQL

            ## 数据模型

            ```kotlin
            data class User(
                val id: String,
                val email: String,
                val passwordHash: String
            )
            ```

            ## API 设计

            ### POST /api/auth/login
            请求：
            ```json
            {
              "email": "user@example.com",
              "password": "password123"
            }
            ```
        """.trimIndent()
    }

    /**
     * 获取 Implement 阶段模板
     */
    private fun getImplementTemplate(): String {
        return """
            # 实现任务

            ## 任务列表

            ### Phase 1: 基础功能（P0）

            - [ ] Task 1: 创建 User 数据模型（2h）
            - [ ] Task 2: 实现用户注册 API（4h）
            - [ ] Task 3: 实现用户登录 API（4h）
            - [ ] Task 4: 实现 JWT Token 生成（2h）

            ### Phase 2: 前端集成（P1）

            - [ ] Task 5: 创建登录页面（4h）
            - [ ] Task 6: 集成登录 API（2h）

            ## 任务依赖

            - Task 2 依赖 Task 1
            - Task 3 依赖 Task 1, Task 4
            - Task 6 依赖 Task 3, Task 5

            ## 测试计划

            - [ ] 单元测试：User 模型测试
            - [ ] 集成测试：登录 API 测试
            - [ ] E2E 测试：完整登录流程测试
        """.trimIndent()
    }
}
