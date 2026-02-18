package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class ChatMessagePanelTraceStreamingTest {

    @Test
    fun `assistant trace should support collapse and expand during streaming`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Thinking] analyze execution flow and keep the trace visible.
                [Task] 1/2 implement streaming trace
                """.trimIndent()
            )
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button during streaming")

        runOnEdt { expandButton!!.doClick() }

        val collapseText = SpecCodingBundle.message("chat.timeline.toggle.collapse")
        val collapseButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == collapseText }
        assertNotNull(collapseButton, "Expected collapse trace button after expanding")
    }

    @Test
    fun `edit trace row should expose open file action`() {
        var opened: WorkflowQuickActionParser.FileAction? = null
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowFileOpen = { opened = it },
        )

        runOnEdt {
            panel.appendContent("[Edit] src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt:120")
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button for collapsed trace panel")
        runOnEdt { expandButton!!.doClick() }

        val openButtonText = SpecCodingBundle.message("chat.workflow.action.openFile.short")
        val openButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == openButtonText }
        assertNotNull(openButton, "Expected open file action on edit trace item")

        runOnEdt { openButton!!.doClick() }
        assertNotNull(opened)
        assertEquals(
            "src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt",
            opened!!.path,
        )
        assertEquals(120, opened!!.line)
    }

    @Test
    fun `assistant answer should not duplicate timeline prefix lines`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Thinking] analyze user request
                Plan
                - clarify constraints
                Execute
                [Task] create requirements draft
                - implement UI changes
                Verify
                [Verify] run tests done
                - all checks passed
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("[Thinking]"))
        assertFalse(allText.contains("[Task]"))
        assertFalse(allText.contains("[Verify]"))
    }

    private fun collectDescendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        val container = component as? Container ?: return@sequence
        container.components.forEach { child ->
            yieldAll(collectDescendants(child))
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
