package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ChatMessagePanelSpecWorkflowIntegrationTest {

    @Test
    fun `assistant message should render workflow sections and support spec command quick action`() {
        var insertedCommand: String? = null
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowCommandInsert = { command -> insertedCommand = command },
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
            onWorkflowCommandInsert = { command -> insertedCommand = command },
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
