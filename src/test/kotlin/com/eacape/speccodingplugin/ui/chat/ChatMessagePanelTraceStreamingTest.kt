package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
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

        val expectedTooltip = SpecCodingBundle.message(
            "chat.workflow.action.openFile.tooltip",
            "src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt:120",
        )
        val openButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == expectedTooltip }
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

    @Test
    fun `running trace status should become done when message finishes`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                com.eacape.speccodingplugin.stream.ChatStreamEvent(
                    kind = com.eacape.speccodingplugin.stream.ChatTraceKind.TASK,
                    detail = "implement ui polish",
                    status = com.eacape.speccodingplugin.stream.ChatTraceStatus.RUNNING,
                )
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val doneText = SpecCodingBundle.message("chat.timeline.status.done")
        val runningText = SpecCodingBundle.message("chat.timeline.status.running")
        val labels = collectDescendants(panel)
            .filterIsInstance<javax.swing.JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(labels.any { it.contains(doneText) })
        assertFalse(labels.any { it.contains(runningText) })
    }

    @Test
    fun `trace detail should render markdown style content`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = "**项目背景**\n- 第一项\n- 第二项",
                    status = ChatTraceStatus.RUNNING,
                )
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("项目背景"))
        assertFalse(allText.contains("**项目背景**"))
    }

    @Test
    fun `expanded trace should merge consecutive same kind steps`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Read] docs/spec-a.md done
                [Read] docs/spec-b.md done
                [Read] docs/spec-c.md done
                [Edit] src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt done
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val mergedLabel = "${SpecCodingBundle.message("chat.timeline.kind.read")} · ${SpecCodingBundle.message("chat.timeline.status.done")}"
        val labels = collectDescendants(panel)
            .filterIsInstance<javax.swing.JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertEquals(1, labels.count { it == mergedLabel })
        assertTrue(labels.any { it == "x3" })
    }

    @Test
    fun `expanded output should merge multiple output events into one detail block`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] OpenAI Codex v0.104.0
                [Output] workdir: C:/Users/12186/PyCharmMiscProject
                [Output] model: gpt-5.3-codex
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val textPanes = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .map { it.text.orEmpty() }
            .toList()

        val mergedPane = textPanes.firstOrNull {
            it.contains("OpenAI Codex v0.104.0") &&
                it.contains("workdir: C:/Users/12186/PyCharmMiscProject") &&
                it.contains("model: gpt-5.3-codex")
        }
        assertNotNull(mergedPane, "Expected one merged output detail block containing all lines")
        assertTrue(textPanes.count { it.contains("OpenAI Codex v0.104.0") } == 1)
    }

    @Test
    fun `expanded trace should display latest 10 timeline entries only`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        val content = (1..15).joinToString("\n") { index ->
            val id = index.toString().padStart(2, '0')
            if (index % 2 == 0) {
                "[Read] trace-entry-$id done"
            } else {
                "[Edit] trace-entry-$id done"
            }
        }

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("trace-entry-01"))
        assertFalse(allText.contains("trace-entry-05"))
        assertTrue(allText.contains("trace-entry-06"))
        assertTrue(allText.contains("trace-entry-15"))
    }

    @Test
    fun `expanded output should display latest 10 output entries only in all mode`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        val content = (1..15).joinToString("\n") { index ->
            val id = index.toString().padStart(2, '0')
            "[Output] output-entry-$id"
        }

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")
        runOnEdt { filterButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("output-entry-01"))
        assertFalse(allText.contains("output-entry-05"))
        assertTrue(allText.contains("output-entry-06"))
        assertTrue(allText.contains("output-entry-15"))
    }

    @Test
    fun `output filter level should toggle between key and all lines`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] model: gpt-5.3-codex
                [Output] noise-line-0001
                [Output] noise-line-0002
                [Output] noise-line-0003
                [Output] noise-line-0004
                [Output] noise-line-0005
                [Output] noise-line-0006
                [Output] final-noise-tail
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")

        val filteredText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(filteredText.contains("model: gpt-5.3-codex"))
        assertFalse(filteredText.contains("final-noise-tail"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 6)
            )
        )

        runOnEdt { filterButton!!.doClick() }

        val allFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.all"),
        )
        val switchedButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == allFilterText }
        assertNotNull(switchedButton, "Expected output filter button in all mode")

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("final-noise-tail"))
        assertFalse(
            allText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 6)
            )
        )
    }

    @Test
    fun `garbled output event should not be rendered in timeline`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "正常响应",
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "'C:\\Users\\12186\\.claude' ç═╬▌xóèij",
                    status = ChatTraceStatus.ERROR,
                )
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("正常响应"))
        assertFalse(allText.contains(".claude"))
        assertFalse(allText.contains("ç═"))
    }

    @Test
    fun `placeholder output event should not be rendered in timeline`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "正常响应",
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "-",
                    status = ChatTraceStatus.INFO,
                )
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("正常响应"))
        assertFalse(allText.lines().any { it.trim() == "-" })
    }

    @Test
    fun `message text pane should be focusable for in-place copy`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "copy me",
        )

        runOnEdt { panel.finishMessage() }

        val textPanes = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .toList()

        assertTrue(textPanes.isNotEmpty())
        assertTrue(textPanes.all { it.isFocusable })
    }

    @Test
    fun `copy all action should be clickable`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "clipboard payload",
        )

        runOnEdt { panel.finishMessage() }

        val tooltip = SpecCodingBundle.message("chat.message.copy.all")
        val copyButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == tooltip }
        assertNotNull(copyButton, "Expected copy-all icon button")
        val button = copyButton!!

        runOnEdt { button.doClick() }

        val copied = SpecCodingBundle.message("chat.message.copy.copied")
        val failed = SpecCodingBundle.message("chat.message.copy.failed")
        assertTrue(button.toolTipText == copied || button.toolTipText == failed)
        assertTrue(button.text == "OK" || button.text == "!")
    }

    @Test
    fun `top-level fenced code block should render as code card`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = """
            before
            ```kotlin
            println("hello")
            ```
            after
        """.trimIndent()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val copyCodeTooltip = SpecCodingBundle.message("chat.message.copy.code")
        val copyCodeButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == copyCodeTooltip }
        assertNotNull(copyCodeButton, "Expected copy-code action for top-level fenced block")

        val markdownText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(markdownText.contains("before"))
        assertTrue(markdownText.contains("after"))

        val codeText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(codeText.contains("println(\"hello\")"))
    }

    @Test
    fun `indented fenced code block should not render as code card`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = """
            1. step one
               ```kotlin
               val nested = true
               ```
            2. step two
        """.trimIndent()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val copyCodeTooltip = SpecCodingBundle.message("chat.message.copy.code")
        val copyCodeButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == copyCodeTooltip }
        assertTrue(copyCodeButton == null, "Indented fenced block should stay in markdown renderer")

        val markdownText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(markdownText.contains("val nested = true"))
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
