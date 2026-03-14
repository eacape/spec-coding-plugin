package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateQuickFixDescriptor
import com.eacape.speccodingplugin.spec.GateQuickFixKind
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.MissingRequirementsSectionsQuickFixPayload
import com.eacape.speccodingplugin.spec.RequirementsSectionId
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

    fun `test gate panel should expose quick fix labels for selected violation`() {
        val panel = SpecWorkflowGateDetailsPanel(project, showHeader = true)
        val payload = MissingRequirementsSectionsQuickFixPayload(
            listOf(RequirementsSectionId.USER_STORIES, RequirementsSectionId.ACCEPTANCE_CRITERIA),
        )

        panel.updateGateResult(
            workflowId = "wf-quick-fix",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "stage-completion-checks",
                        severity = GateStatus.ERROR,
                        fileName = "requirements.md",
                        line = 1,
                        message = "Missing requirements sections",
                        quickFixes = listOf(
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                                payload = payload,
                            ),
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS,
                                payload = payload,
                            ),
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.OPEN_FOR_MANUAL_EDIT,
                                payload = payload,
                            ),
                        ),
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
        panel.selectViolationForTest(0)

        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill"),
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarifyThenFill"),
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.manualEdit"),
            ),
            panel.selectedQuickFixLabelsForTest(),
        )
    }
}
