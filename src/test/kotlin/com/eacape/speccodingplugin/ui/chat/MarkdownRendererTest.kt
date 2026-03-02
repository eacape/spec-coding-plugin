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
    fun `render should convert markdown table into normalized pipe table`() {
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
        assertTrue(text.contains("| 层"))
        assertTrue(text.contains("| 前端"))
        assertTrue(text.contains("前端"))
        assertTrue(text.contains("Kotlin"))
        assertTrue(text.contains("---"))
        assertFalse(text.contains("┌"))
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

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
