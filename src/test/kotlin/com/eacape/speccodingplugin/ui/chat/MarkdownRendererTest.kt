package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class MarkdownRendererTest {

    @Test
    fun `render should support level six heading syntax`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "###### 2.1 数据采集模块")
        }

        assertTrue(pane.text.contains("2.1 数据采集模块"))
        assertFalse(pane.text.contains("######"))
    }

    @Test
    fun `render should support indented heading syntax`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "  ####  details")
        }

        assertTrue(pane.text.contains("details"))
        assertFalse(pane.text.contains("####"))
    }

    @Test
    fun `render should remove code fences and keep code body`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                代码示例
                ```
                const id = 1;
                if (id) {
                    console.log(id);
                }
                ```
                结束
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("代码示例"))
        assertTrue(text.contains("const id = 1;"))
        assertTrue(text.contains("console.log(id);"))
        assertTrue(text.contains("结束"))
        assertFalse(text.contains("```"))
    }

    @Test
    fun `render should convert markdown table with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 层 | 选型 |
                | --- | --- |
                | 前端 | React |
                | 后端 | Kotlin |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should keep plain pipe text when separator row is missing`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                A | B
                plain
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("A | B"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should convert wide multi-column table with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 任务 | 说明 | 状态 |
                | --- | --- | --- |
                | 渲染优化 | 解决长文本表格在窄区域内换行错乱导致阅读困难的问题 | 进行中 |
                | 交互优化 | 保持布局轻量并提升可读性与信息密度的一致性 | 待验证 |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should switch back to plain mode after html table rendering`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | A | B |
                | --- | --- |
                | 1 | 2 |
                """.trimIndent(),
            )
            MarkdownRenderer.render(pane, "plain text")
        }

        assertTrue(pane.contentType.contains("plain"))
        assertTrue(pane.text.contains("plain text"))
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
