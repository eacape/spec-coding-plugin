package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SpecWorkflowStageStepperPanelTest {

    @Test
    fun `updateState should expose active inactive stages and enabled controls`() {
        var jumpClicks = 0
        val panel = SpecWorkflowStageStepperPanel(
            onJumpRequested = { jumpClicks += 1 },
        )

        panel.updateState(
            SpecWorkflowStageStepperState(
                stages = listOf(
                    SpecWorkflowStageStepState(StageId.REQUIREMENTS, active = true, current = false, progress = StageProgress.DONE),
                    SpecWorkflowStageStepState(StageId.DESIGN, active = true, current = true, progress = StageProgress.IN_PROGRESS),
                    SpecWorkflowStageStepState(StageId.TASKS, active = true, current = false, progress = StageProgress.NOT_STARTED),
                    SpecWorkflowStageStepState(StageId.IMPLEMENT, active = false, current = false, progress = StageProgress.NOT_STARTED),
                    SpecWorkflowStageStepState(StageId.VERIFY, active = false, current = false, progress = StageProgress.NOT_STARTED),
                    SpecWorkflowStageStepState(StageId.ARCHIVE, active = true, current = false, progress = StageProgress.NOT_STARTED),
                ),
                canAdvance = true,
                jumpTargets = listOf(StageId.TASKS, StageId.ARCHIVE),
                rollbackTargets = listOf(StageId.REQUIREMENTS),
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("true", snapshot.getValue("advanceEnabled"))
        assertEquals("true", snapshot.getValue("jumpEnabled"))
        assertEquals("true", snapshot.getValue("rollbackEnabled"))
        assertEquals("", snapshot.getValue("advanceText"))
        assertEquals(SpecCodingBundle.message("spec.action.advance.text"), snapshot.getValue("advanceTooltip"))
        assertEquals("true", snapshot.getValue("advanceHasIcon"))
        assertEquals("true", snapshot.getValue("advanceRolloverEnabled"))
        assertEquals("", snapshot.getValue("jumpText"))
        assertEquals(SpecCodingBundle.message("spec.action.jump.text"), snapshot.getValue("jumpTooltip"))
        assertEquals("true", snapshot.getValue("jumpHasIcon"))
        assertEquals("true", snapshot.getValue("jumpRolloverEnabled"))
        assertEquals("", snapshot.getValue("rollbackText"))
        assertEquals(SpecCodingBundle.message("spec.action.rollback.text"), snapshot.getValue("rollbackTooltip"))
        assertEquals("true", snapshot.getValue("rollbackHasIcon"))
        assertEquals("true", snapshot.getValue("rollbackRolloverEnabled"))
        assertEquals("true", snapshot.getValue("stageChipOpaque"))
        assertNotEquals("0,0,0,0", snapshot.getValue("stageChipInsets"))
        assertEquals(
            true,
            snapshot.getValue("stages").contains(
                "${SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT)}:${SpecCodingBundle.message("spec.toolwindow.stage.state.inactive")}",
            ),
        )
        assertEquals(
            true,
            snapshot.getValue("stages").contains(
                "${SpecWorkflowOverviewPresenter.stageLabel(StageId.DESIGN)}:${SpecCodingBundle.message("spec.action.stage.state.inProgress")}",
            ),
        )
        assertEquals(
            true,
            snapshot.getValue("stages").contains(
                "${SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS)}:${SpecCodingBundle.message("spec.action.stage.state.pending")}",
            ),
        )

        panel.clickJumpForTest()
        assertEquals(1, jumpClicks)
    }
}
