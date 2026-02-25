package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmRouter
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecGeneratorClarificationTest {

    private val generator = SpecGenerator(mockk<LlmRouter>(relaxed = true))

    @Test
    fun `extractClarificationQuestions should parse markdown question section`() {
        val raw = """
            ## 需要确认的问题
            1. 目标用户是否只包含管理员？
            2. 排行榜是否允许手动修正？
            - 是否需要审计日志落库？
            
            ## 已有信息摘要
            - 当前系统已有 Redis。
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 5)

        assertEquals(
            listOf(
                "目标用户是否只包含管理员？",
                "排行榜是否允许手动修正？",
                "是否需要审计日志落库？",
            ),
            questions,
        )
    }

    @Test
    fun `extractClarificationQuestions should fallback to free-form question lines`() {
        val raw = """
            请先确认以下事项：
            是否需要支持离线模式？
            是否有固定 SLA？
            这个阶段先不涉及多租户。
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 5)

        assertEquals(
            listOf(
                "是否需要支持离线模式？",
                "是否有固定 SLA？",
            ),
            questions,
        )
    }

    @Test
    fun `extractClarificationQuestions should respect maxQuestions budget`() {
        val raw = """
            ## Clarification Questions
            1. Q1?
            2. Q2?
            3. Q3?
            4. Q4?
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 2)

        assertEquals(listOf("Q1?", "Q2?"), questions)
    }
}

