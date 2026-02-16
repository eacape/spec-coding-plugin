package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionTimelineParserTest {

    @Test
    fun `parse should detect english prefixed timeline lines`() {
        val content = """
            [Thinking] Analyze current architecture
            [Task] 1/3 collect context
            [Read] src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt
            [Edit] src/main/kotlin/com/eacape/speccodingplugin/engine/CliDiscoveryService.kt
            [Task] 3/3 completed
            [Verify] test completed
        """.trimIndent()

        val items = ExecutionTimelineParser.parse(content)

        assertEquals(6, items.size)
        assertEquals(ExecutionTimelineParser.Kind.THINK, items[0].kind)
        assertEquals(ExecutionTimelineParser.Kind.TASK, items[1].kind)
        assertEquals(ExecutionTimelineParser.Status.RUNNING, items[1].status)
        assertEquals(ExecutionTimelineParser.Kind.TASK, items[4].kind)
        assertEquals(ExecutionTimelineParser.Status.DONE, items[4].status)
        assertEquals(ExecutionTimelineParser.Kind.VERIFY, items[5].kind)
    }

    @Test
    fun `parse should detect chinese timeline lines`() {
        val content = """
            思考 >
            批量读取文件 (2)
            编辑文件 CliDiscoveryService.kt +13
            子任务 20/20
        """.trimIndent()

        val items = ExecutionTimelineParser.parse(content)

        assertEquals(4, items.size)
        assertEquals(ExecutionTimelineParser.Kind.THINK, items[0].kind)
        assertEquals(ExecutionTimelineParser.Status.RUNNING, items[0].status)
        assertEquals(ExecutionTimelineParser.Kind.READ, items[1].kind)
        assertEquals(ExecutionTimelineParser.Kind.EDIT, items[2].kind)
        assertEquals(ExecutionTimelineParser.Kind.TASK, items[3].kind)
        assertEquals(ExecutionTimelineParser.Status.DONE, items[3].status)
    }

    @Test
    fun `parse should ignore normal prose without execution signals`() {
        val content = """
            这是普通回答内容。
            我建议先从核心服务开始重构，然后补充测试。
        """.trimIndent()

        val items = ExecutionTimelineParser.parse(content)
        assertTrue(items.isEmpty())
    }
}
