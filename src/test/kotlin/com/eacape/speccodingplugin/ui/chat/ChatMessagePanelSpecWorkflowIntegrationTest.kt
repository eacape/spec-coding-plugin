package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import java.awt.BorderLayout

class ChatMessagePanelSpecWorkflowIntegrationTest {

    @Test
    fun `assistant message should render workflow sections and support spec command quick action`() {
        var insertedCommand: String? = null
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowCommandExecute = { command -> insertedCommand = command },
        )

        val response = SpecWorkflowResponseBuilder.buildPhaseTransitionResponse(
            workflowId = "spec-chat-1",
            phaseDisplayName = "Specify",
            template = "## Requirements\n- Objective:",
            advanced = false,
            templateInserted = true,
        )

        runOnEdt {
            panel.appendContent(response)
            panel.finishMessage()
        }

        val labelTexts = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(labelTexts.contains(SpecCodingBundle.message("chat.workflow.section.plan")))
        assertTrue(labelTexts.contains(SpecCodingBundle.message("chat.workflow.section.execute")))
        assertTrue(labelTexts.contains(SpecCodingBundle.message("chat.workflow.section.verify")))

        val commandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText?.contains("/spec ") == true }
        assertNotNull(commandButton, "Expected at least one /spec command button")
        runOnEdt { commandButton!!.doClick() }
        assertTrue(insertedCommand?.startsWith("/spec ") == true)
    }

    @Test
    fun `assistant message should render spec quick action buttons for transition response`() {
        var insertedCommand: String? = null
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowCommandExecute = { command -> insertedCommand = command },
        )
        val response = SpecWorkflowResponseBuilder.buildPhaseTransitionResponse(
            workflowId = "spec-chat-2",
            phaseDisplayName = "Design",
            template = "## Design\n- Architecture:",
            advanced = false,
            templateInserted = false,
        )
        runOnEdt {
            panel.appendContent(response)
            panel.finishMessage()
        }

        val commandButtons = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .filter { button ->
                val tooltip = button.toolTipText ?: ""
                tooltip.contains("/spec generate <input>") || tooltip.contains("/spec status")
            }
            .toList()
        assertFalse(commandButtons.isEmpty(), "Expected spec quick action buttons")

        val statusButton = commandButtons.firstOrNull { it.toolTipText?.contains("/spec status") == true }
        assertNotNull(statusButton, "Expected status command button")
        runOnEdt { statusButton!!.doClick() }
        assertEquals("/spec status", insertedCommand)
    }

    @Test
    fun `workflow command buttons should be rendered inside markdown area not footer action bar`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val response = SpecWorkflowResponseBuilder.buildPhaseTransitionResponse(
            workflowId = "spec-chat-3",
            phaseDisplayName = "Tasks",
            template = "## Tasks\n- Item",
            advanced = true,
            templateInserted = true,
        )

        runOnEdt {
            panel.appendContent(response)
            panel.finishMessage()
        }

        val layout = panel.layout as BorderLayout
        val footer = layout.getLayoutComponent(BorderLayout.SOUTH) as? Container
        val footerHasCommandButton = footer
            ?.let { collectDescendants(it).filterIsInstance<JButton>() }
            ?.any { it.toolTipText?.contains("/spec ") == true }
            ?: false

        val hasMarkdownCommandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .any { it.toolTipText?.contains("/spec ") == true }

        assertFalse(footerHasCommandButton, "Footer action bar should not host workflow command buttons")
        assertTrue(hasMarkdownCommandButton, "Expected workflow command button inside markdown content area")
    }

    @Test
    fun `non slash workflow command should render run action only and invoke execute callback`() {
        var executedCommand: String? = null
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowCommandExecute = { command -> executedCommand = command },
        )

        runOnEdt {
            panel.appendContent(
                """
                ## Plan
                ```bash
                git status
                ```
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val buttons = collectDescendants(panel).filterIsInstance<JButton>().toList()
        val runButton = buttons.firstOrNull { it.toolTipText?.contains("git status") == true }
        assertNotNull(runButton, "Expected run button for non-slash command")
        runOnEdt { runButton!!.doClick() }
        assertEquals("git status", executedCommand)

        val stopButton = buttons.firstOrNull {
            it.text == SpecCodingBundle.message("chat.workflow.action.stopCommand") &&
                it.toolTipText?.contains("git status") == true
        }
        assertNull(stopButton, "Stop button should not be rendered for workflow command")
    }

    @Test
    fun `slash workflow command should not render stop action`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                ## Plan
                Command: /spec status
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val buttons = collectDescendants(panel).filterIsInstance<JButton>().toList()
        val runButton = buttons.firstOrNull { it.toolTipText?.contains("/spec status") == true }
        assertNotNull(runButton, "Expected run button for slash command")

        val stopButton = buttons.firstOrNull {
            it.text == SpecCodingBundle.message("chat.workflow.action.stopCommand") &&
                it.toolTipText?.contains("/spec status") == true
        }
        assertNull(stopButton, "Slash command should not render stop button")
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
