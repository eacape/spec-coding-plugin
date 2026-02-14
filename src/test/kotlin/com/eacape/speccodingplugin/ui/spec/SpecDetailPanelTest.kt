package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPanelTest {

    @Test
    fun `showEmpty should clear preview and disable all actions`() {
        val panel = createPanel()

        panel.showEmpty()

        assertEquals("", panel.currentPreviewTextForTest())
        assertEquals("Select a workflow to view details", panel.currentValidationTextForTest())

        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertFalse(states["nextEnabled"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertFalse(states["pauseResumeEnabled"] as Boolean)
        assertFalse(states["openEditorEnabled"] as Boolean)
        assertFalse(states["historyDiffEnabled"] as Boolean)
    }

    @Test
    fun `updateWorkflow should show current phase preview and button states`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-1",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Demo",
            description = "Demo workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        assertEquals("design content", panel.currentPreviewTextForTest())
        assertEquals("Validation: PASSED", panel.currentValidationTextForTest())

        val states = panel.buttonStatesForTest()
        assertTrue(states["generateEnabled"] as Boolean)
        assertTrue(states["nextEnabled"] as Boolean)
        assertTrue(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertTrue(states["pauseResumeEnabled"] as Boolean)
        assertEquals("Pause", states["pauseResumeText"])
        assertTrue(states["openEditorEnabled"] as Boolean)
        assertTrue(states["historyDiffEnabled"] as Boolean)
    }

    @Test
    fun `updateWorkflow should enable complete only when implement document is valid`() {
        val panel = createPanel()

        val workflow = SpecWorkflow(
            id = "wf-2",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                    valid = true,
                )
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Impl",
            description = "Impl workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertTrue(states["completeEnabled"] as Boolean)
    }

    @Test
    fun `paused workflow should show resume text and disable generate related actions`() {
        val panel = createPanel()

        val workflow = SpecWorkflow(
            id = "wf-3",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                )
            ),
            status = WorkflowStatus.PAUSED,
            title = "Paused",
            description = "Paused workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertFalse(states["nextEnabled"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertTrue(states["pauseResumeEnabled"] as Boolean)
        assertEquals("Resume", states["pauseResumeText"])
        assertTrue(states["historyDiffEnabled"] as Boolean)
    }

    private fun createPanel(): SpecDetailPanel {
        return SpecDetailPanel(
            onGenerate = {},
            onNextPhase = {},
            onGoBack = {},
            onComplete = {},
            onPauseResume = {},
            onOpenInEditor = {},
            onShowHistoryDiff = {},
        )
    }

    private fun document(phase: SpecPhase, content: String, valid: Boolean): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "test",
            ),
            validationResult = ValidationResult(valid = valid),
        )
    }
}
