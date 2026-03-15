package com.eacape.speccodingplugin.ui.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JLabel
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

class ImageAttachmentPreviewPanelTest {

    @Test
    fun `addImagePaths should deduplicate case insensitively`() {
        val panel = ImageAttachmentPreviewPanel()

        runOnEdt {
            panel.addImagePaths(
                listOf(
                    "C:/tmp/Design.png",
                    "c:/tmp/design.png",
                    "D:/tmp/Flow.jpg",
                )
            )
        }

        assertEquals(
            listOf("C:/tmp/Design.png", "D:/tmp/Flow.jpg"),
            panel.getImagePaths(),
        )
    }

    @Test
    fun `clear should empty attachments and hide panel`() {
        val panel = ImageAttachmentPreviewPanel()

        runOnEdt {
            panel.addImagePaths(listOf("C:/tmp/a.png"))
        }
        assertTrue(panel.isVisible)

        runOnEdt {
            panel.clear()
        }

        assertTrue(panel.getImagePaths().isEmpty())
        assertFalse(panel.isVisible)
    }

    @Test
    fun `preview chips should use image aliases and keep horizontal chip list`() {
        val panel = ImageAttachmentPreviewPanel()

        runOnEdt {
            panel.addImagePaths(
                listOf(
                    "C:/tmp/first.png",
                    "D:/tmp/second.jpg",
                    "E:/tmp/third.webp",
                )
            )
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(labels.any { it.contains("3") && !it.startsWith("image#") })

        val removeButtons = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.toolTipText?.isNotBlank() == true }
            .toList()
        assertEquals(1, removeButtons.size)
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
