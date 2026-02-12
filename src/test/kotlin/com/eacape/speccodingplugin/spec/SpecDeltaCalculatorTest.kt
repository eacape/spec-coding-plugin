package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDeltaCalculatorTest {

    @Test
    fun `compareWorkflows should mark added modified removed and unchanged`() {
        val baseline = SpecWorkflow(
            id = "wf-baseline",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirement-v1",
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design-v1",
                ),
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks-v1",
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "baseline",
            description = "baseline",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val target = SpecWorkflow(
            id = "wf-target",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirement-v1",
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design-v2",
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "target",
            description = "target",
            createdAt = 3L,
            updatedAt = 4L,
        )

        val delta = SpecDeltaCalculator.compareWorkflows(
            baselineWorkflow = baseline,
            targetWorkflow = target,
        )

        assertEquals("wf-baseline", delta.baselineWorkflowId)
        assertEquals("wf-target", delta.targetWorkflowId)
        assertEquals(SpecDeltaStatus.UNCHANGED, delta.phaseDeltas.first { it.phase == SpecPhase.SPECIFY }.status)
        assertEquals(SpecDeltaStatus.MODIFIED, delta.phaseDeltas.first { it.phase == SpecPhase.DESIGN }.status)
        assertEquals(SpecDeltaStatus.REMOVED, delta.phaseDeltas.first { it.phase == SpecPhase.IMPLEMENT }.status)
        assertEquals(0, delta.count(SpecDeltaStatus.ADDED))
        assertEquals(1, delta.count(SpecDeltaStatus.MODIFIED))
        assertEquals(1, delta.count(SpecDeltaStatus.REMOVED))
        assertEquals(1, delta.count(SpecDeltaStatus.UNCHANGED))
        assertTrue(delta.hasChanges())
    }

    @Test
    fun `compareWorkflows should treat normalized content as unchanged`() {
        val baseline = SpecWorkflow(
            id = "wf-a",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "line-1\r\nline-2\r\n",
                )
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "A",
            description = "A",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val target = SpecWorkflow(
            id = "wf-b",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "line-1\nline-2",
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "new-design",
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "B",
            description = "B",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val delta = SpecDeltaCalculator.compareWorkflows(
            baselineWorkflow = baseline,
            targetWorkflow = target,
        )

        assertEquals(SpecDeltaStatus.UNCHANGED, delta.phaseDeltas.first { it.phase == SpecPhase.SPECIFY }.status)
        assertEquals(SpecDeltaStatus.ADDED, delta.phaseDeltas.first { it.phase == SpecPhase.DESIGN }.status)
        assertEquals(1, delta.count(SpecDeltaStatus.ADDED))
        assertFalse(delta.phaseDeltas.isEmpty())
    }

    private fun document(
        phase: SpecPhase,
        content: String,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "test",
            ),
            validationResult = ValidationResult(valid = true),
        )
    }
}
