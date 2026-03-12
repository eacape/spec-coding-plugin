package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel

class SpecCollapsibleWorkspaceSectionTest {

    @Test
    fun `toggle button should keep english labels fully visible`() {
        val section = SpecCollapsibleWorkspaceSection(
            titleProvider = { "Documents" },
            content = JPanel(),
            expandedInitially = false,
        )

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.expand"), section.toggleButtonTextForTest())
        assertTrue(section.toggleButtonHasEnoughWidthForTextForTest())
        assertTrue(section.toggleButtonCanFitTextForTest("Expand"))

        section.setExpanded(true, notify = false)

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.collapse"), section.toggleButtonTextForTest())
        assertTrue(section.toggleButtonHasEnoughWidthForTextForTest())
        assertTrue(section.toggleButtonCanFitTextForTest("Collapse"))
    }
}
