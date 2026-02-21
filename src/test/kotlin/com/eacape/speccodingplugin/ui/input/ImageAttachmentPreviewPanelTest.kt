package com.eacape.speccodingplugin.ui.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
