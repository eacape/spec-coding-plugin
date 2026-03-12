package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.Violation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SpecWorkflowGateDetailsPanelTest : BasePlatformTestCase() {

    fun `test embedded gate panel should hide duplicated header block`() {
        val panel = SpecWorkflowGateDetailsPanel(project, showHeader = false)

        panel.updateGateResult(
            workflowId = "wf-1",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "gate.rule.required",
                        severity = GateStatus.WARNING,
                        fileName = "tasks.md",
                        line = 12,
                        message = "Missing task metadata",
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("false", snapshot.getValue("headerVisible"))
        assertEquals("wf-1", snapshot.getValue("workflowId"))
        assertEquals("1", snapshot.getValue("violationCount"))
    }
}
