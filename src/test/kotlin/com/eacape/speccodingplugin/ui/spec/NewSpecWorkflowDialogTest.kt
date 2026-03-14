package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewSpecWorkflowDialogTest {

    @Test
    fun `templateSupportsRequirementScope should only allow requirement templates`() {
        assertTrue(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.FULL_SPEC))
        assertFalse(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.QUICK_TASK))
        assertFalse(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.DESIGN_REVIEW))
        assertFalse(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.DIRECT_IMPLEMENT))
    }

    @Test
    fun `normalizeChangeIntent should fallback to full for templates without requirements`() {
        assertEquals(
            SpecChangeIntent.INCREMENTAL,
            NewSpecWorkflowDialog.normalizeChangeIntent(
                template = WorkflowTemplate.FULL_SPEC,
                requestedIntent = SpecChangeIntent.INCREMENTAL,
            ),
        )
        assertEquals(
            SpecChangeIntent.FULL,
            NewSpecWorkflowDialog.normalizeChangeIntent(
                template = WorkflowTemplate.QUICK_TASK,
                requestedIntent = SpecChangeIntent.INCREMENTAL,
            ),
        )
        assertEquals(
            SpecChangeIntent.FULL,
            NewSpecWorkflowDialog.normalizeChangeIntent(
                template = WorkflowTemplate.DIRECT_IMPLEMENT,
                requestedIntent = SpecChangeIntent.FULL,
            ),
        )
    }
}
