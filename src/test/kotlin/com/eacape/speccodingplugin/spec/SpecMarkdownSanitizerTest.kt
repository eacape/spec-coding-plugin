package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecMarkdownSanitizerTest {

    @Test
    fun `sanitize should remove tool call blocks and prelude narration`() {
        val raw = """
            先把计划写好：
            <tool_call>
            <tool_name>Write</tool_name>
            <tool_input>{"file_path":"requirements.md"}</tool_input>
            </tool_call>
            
            现在直接输出最终文档：
            ## 需求文档
            ### 功能需求
            - 支持名称悬浮显示
        """.trimIndent()

        val sanitized = SpecMarkdownSanitizer.sanitize(raw)

        assertTrue(sanitized.startsWith("## 需求文档"))
        assertTrue(sanitized.contains("功能需求"))
        assertFalse(sanitized.contains("<tool_call>", ignoreCase = true))
        assertFalse(sanitized.contains("先把计划写好"))
    }

    @Test
    fun `sanitize should decode escaped json content payload`() {
        val raw = """{"content":"## 技术选型\n\n- Kotlin\n- IntelliJ Platform\n\n## 数据模型\n- SpecWorkflow"}"""

        val sanitized = SpecMarkdownSanitizer.sanitize(raw)

        assertTrue(sanitized.contains("## 技术选型"))
        assertTrue(sanitized.contains("## 数据模型"))
        assertFalse(sanitized.contains("\\n"))
    }

    @Test
    fun `sanitize should extract likely markdown document from fenced payload`() {
        val raw = """
            以下是执行结果：
            ```text
            random log output
            ```
            
            ```markdown
            ## 任务列表
            - [ ] Task 1: 完成侧栏渲染清洗
            
            ## 实现步骤
            1. 解析并清洗文档内容
            ```
        """.trimIndent()

        val sanitized = SpecMarkdownSanitizer.sanitize(raw)

        assertTrue(sanitized.startsWith("## 任务列表"))
        assertTrue(sanitized.contains("## 实现步骤"))
        assertFalse(sanitized.contains("random log output"))
        assertFalse(sanitized.contains("以下是执行结果"))
    }

    @Test
    fun `sanitize should keep full markdown document when code fence is part of document`() {
        val raw = """
            ## 架构设计
            - 使用三层结构
            
            ```mermaid
            graph TD
              A[UI] --> B[Service]
            ```
            
            ## 技术选型
            - Kotlin
            - IntelliJ Platform
        """.trimIndent()

        val sanitized = SpecMarkdownSanitizer.sanitize(raw)

        assertTrue(sanitized.contains("## 架构设计"))
        assertTrue(sanitized.contains("## 技术选型"))
        assertTrue(sanitized.contains("```mermaid"))
        assertTrue(sanitized.contains("graph TD"))
    }
}
