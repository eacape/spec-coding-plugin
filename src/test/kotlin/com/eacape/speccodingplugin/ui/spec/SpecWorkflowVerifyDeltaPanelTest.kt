package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowVerifyDeltaPanelTest {

    @Test
    fun `embedded verify panel should hide duplicated header block`() {
        val panel = SpecWorkflowVerifyDeltaPanel(showHeader = false)

        panel.updateState(
            SpecWorkflowVerifyDeltaState(
                workflowId = "wf-1",
                verifyEnabled = true,
                verificationDocumentAvailable = true,
                verificationHistory = listOf(
                    VerifyRunHistoryEntry(
                        runId = "verify-run-1",
                        planId = "verify-plan-1",
                        executedAt = "2026-03-12T15:27:30Z",
                        occurredAtEpochMs = 1_710_000_000_000,
                        currentStage = StageId.VERIFY,
                        conclusion = VerificationConclusion.PASS,
                        summary = "Verification passed",
                        commandCount = 2,
                    ),
                ),
                baselineChoices = emptyList(),
                preferredBaselineChoiceId = null,
                canPinBaseline = false,
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("false", snapshot.getValue("headerVisible"))
        assertEquals("wf-1", snapshot.getValue("workflowId"))
        assertEquals("1", snapshot.getValue("historyCount"))
    }
}
