package com.eacape.speccodingplugin.ui.worktree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NewWorktreeDialogTest {

    @Test
    fun `validateInput should return expected errors for blank fields`() {
        val missingSpec = NewWorktreeDialog.validateInput(
            NewWorktreeInput(specTaskId = "   ", shortName = "feat-x", baseBranch = "main")
        )
        assertEquals(NewWorktreeValidationError.SPEC_TASK_ID_REQUIRED, missingSpec)

        val missingShortName = NewWorktreeDialog.validateInput(
            NewWorktreeInput(specTaskId = "SPEC-1", shortName = " ", baseBranch = "main")
        )
        assertEquals(NewWorktreeValidationError.SHORT_NAME_REQUIRED, missingShortName)

        val missingBaseBranch = NewWorktreeDialog.validateInput(
            NewWorktreeInput(specTaskId = "SPEC-1", shortName = "feat-x", baseBranch = "")
        )
        assertEquals(NewWorktreeValidationError.BASE_BRANCH_REQUIRED, missingBaseBranch)
    }

    @Test
    fun `normalizeInput should trim values and keep valid input`() {
        val normalized = NewWorktreeDialog.normalizeInput(
            NewWorktreeInput(
                specTaskId = "  SPEC-101  ",
                shortName = "  auth-flow  ",
                baseBranch = "  develop  ",
            )
        )

        assertEquals("SPEC-101", normalized.specTaskId)
        assertEquals("auth-flow", normalized.shortName)
        assertEquals("develop", normalized.baseBranch)

        val validation = NewWorktreeDialog.validateInput(normalized)
        assertNull(validation)
        assertEquals("main", NewWorktreeDialog.DEFAULT_BASE_BRANCH)
    }
}

