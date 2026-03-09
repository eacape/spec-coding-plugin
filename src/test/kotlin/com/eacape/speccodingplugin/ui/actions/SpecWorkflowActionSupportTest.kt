package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowActionSupportTest {

    @Test
    fun `jump targets include only later active stages`() {
        val meta = workflowMeta(
            currentStage = StageId.DESIGN,
            stageStates = mapOf(
                StageId.REQUIREMENTS to state(active = true, status = StageProgress.DONE),
                StageId.DESIGN to state(active = true, status = StageProgress.IN_PROGRESS),
                StageId.TASKS to state(active = true, status = StageProgress.NOT_STARTED),
                StageId.IMPLEMENT to state(active = true, status = StageProgress.NOT_STARTED),
                StageId.VERIFY to state(active = false, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to state(active = true, status = StageProgress.NOT_STARTED),
            ),
        )

        assertEquals(
            listOf(StageId.TASKS, StageId.IMPLEMENT, StageId.ARCHIVE),
            SpecWorkflowActionSupport.jumpTargets(meta),
        )
    }

    @Test
    fun `rollback targets include only completed earlier stages`() {
        val meta = workflowMeta(
            currentStage = StageId.IMPLEMENT,
            stageStates = mapOf(
                StageId.REQUIREMENTS to state(active = true, status = StageProgress.DONE),
                StageId.DESIGN to state(active = true, status = StageProgress.DONE),
                StageId.TASKS to state(active = true, status = StageProgress.IN_PROGRESS),
                StageId.IMPLEMENT to state(active = true, status = StageProgress.IN_PROGRESS),
                StageId.VERIFY to state(active = false, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to state(active = true, status = StageProgress.NOT_STARTED),
            ),
        )

        assertEquals(
            listOf(StageId.REQUIREMENTS, StageId.DESIGN),
            SpecWorkflowActionSupport.rollbackTargets(meta),
        )
    }

    @Test
    fun `gate summary includes violation details and truncation hint`() {
        val gateResult = GateResult.fromViolations(
            listOf(
                violation("rule-1", 5),
                violation("rule-2", 8),
                violation("rule-3", 13),
                violation("rule-4", 21),
                violation("rule-5", 34),
                violation("rule-6", 55),
            ),
        )

        val summary = SpecWorkflowActionSupport.gateSummary(gateResult, limit = 3)

        assertTrue(summary.contains("rule-1"))
        assertTrue(summary.contains("requirements.md:5"))
        assertTrue(summary.contains("requirement heading"))
        assertTrue(summary.contains("rule-3"))
        assertTrue(!summary.contains("rule-4"))
    }

    private fun workflowMeta(
        currentStage: StageId,
        stageStates: Map<StageId, StageState>,
    ): WorkflowMeta {
        return WorkflowMeta(
            workflowId = "wf-001",
            title = "Workflow 001",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = stageStates,
            currentStage = currentStage,
            verifyEnabled = false,
            configPinHash = null,
            baselineWorkflowId = null,
            status = WorkflowStatus.IN_PROGRESS,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun state(active: Boolean, status: StageProgress): StageState {
        return StageState(active = active, status = status)
    }

    private fun violation(ruleId: String, line: Int): Violation {
        return Violation(
            ruleId = ruleId,
            severity = GateStatus.ERROR,
            fileName = "requirements.md",
            line = line,
            message = "Broken requirement",
            fixHint = "Fix the requirement heading",
        )
    }
}
