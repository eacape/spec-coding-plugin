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

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
