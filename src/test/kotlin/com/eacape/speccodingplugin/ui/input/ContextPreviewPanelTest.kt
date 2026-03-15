package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ContextPreviewPanelTest {

    @Test
    fun `addItem should deduplicate exact context entries`() {
        val panel = ContextPreviewPanel(project = mockk<Project>(relaxed = true))
        val item = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "src/App.kt",
            content = "fun main() = Unit",
            filePath = "src/App.kt",
        )

        runOnEdt {
            panel.addItem(item)
            panel.addItem(item.copy())
        }

        assertEquals(1, panel.getItems().size)
        assertTrue(panel.isVisible)
    }

    @Test
    fun `summary chip should show aggregated count and clear all items`() {
        val removed = mutableListOf<ContextItem>()
        val panel = ContextPreviewPanel(
            project = mockk<Project>(relaxed = true),
            onRemove = { removed += it },
        )

        runOnEdt {
            panel.addItem(ContextItem(ContextType.REFERENCED_FILE, "src/App.kt", "fun main() = Unit", "src/App.kt"))
            panel.addItem(ContextItem(ContextType.REFERENCED_SYMBOL, "AppService", "class AppService"))
            panel.addItem(ContextItem(ContextType.PROJECT_STRUCTURE, "Project tree", "src/\n test/"))
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(labels.any { it.contains("3") })

        val clearButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .first { it.toolTipText?.isNotBlank() == true }

        runOnEdt {
            clearButton.doClick()
        }

        assertTrue(panel.getItems().isEmpty())
        assertEquals(3, removed.size)
        assertFalse(panel.isVisible)
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
