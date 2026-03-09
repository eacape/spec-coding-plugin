package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecGateRuleEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var configService: SpecProjectConfigService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        configService = SpecProjectConfigService(project)
    }

    @Test
    fun `evaluate should skip disabled rules with explainable summary`() {
        val rule = testRule(
            id = "custom-disabled",
            severity = GateStatus.ERROR,
            remediationHint = "Fix custom-disabled",
        )
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))

        val result = engine.evaluate(
            request = stageRequest(),
            projectConfig = configService.load().copy(
                rules = mapOf(
                    rule.id to SpecRulePolicy(enabled = false),
                ),
            ),
        )

        assertEquals(GateStatus.PASS, result.status)
        val evaluation = result.ruleResults.single()
        assertFalse(evaluation.enabled)
        assertEquals("Disabled by project config.", evaluation.summary)
        assertTrue(evaluation.violations.isEmpty())
    }

    @Test
    fun `evaluate should apply severity override and remediation hint`() {
        val rule = testRule(
            id = "custom-warning",
            severity = GateStatus.ERROR,
            remediationHint = "Add the missing detail",
        )
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))

        val result = engine.evaluate(
            request = stageRequest(),
            projectConfig = configService.load().copy(
                rules = mapOf(
                    rule.id to SpecRulePolicy(severityOverride = GateStatus.WARNING),
                ),
            ),
        )

        assertEquals(GateStatus.WARNING, result.status)
        val violation = result.violations.single()
        assertEquals(GateStatus.WARNING, violation.severity)
        assertEquals("Add the missing detail", violation.fixHint)

        val evaluation = result.ruleResults.single()
        assertTrue(evaluation.severityOverridden)
        assertEquals(GateStatus.WARNING, evaluation.effectiveSeverity)
        assertTrue(evaluation.summary.contains("Found 1 violation"))
    }

    @Test
    fun `evaluate should report pass summary for applicable rules without violations`() {
        val rule = object : Rule {
            override val id: String = "custom-pass"
            override val description: String = "Pass rule"
            override val defaultSeverity: GateStatus = GateStatus.ERROR
            override val remediationHint: String? = null

            override fun appliesTo(stage: StageId): Boolean = stage == StageId.REQUIREMENTS

            override fun evaluate(ctx: RuleContext): List<Violation> = emptyList()
        }
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))

        val result = engine.evaluate(
            request = stageRequest(),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.PASS, result.status)
        val evaluation = result.ruleResults.single()
        assertTrue(evaluation.enabled)
        assertEquals(listOf(StageId.REQUIREMENTS), evaluation.appliedStages)
        assertTrue(evaluation.summary.contains("Passed for REQUIREMENTS"))
        assertTrue(evaluation.violations.isEmpty())
    }

    @Test
    fun `evaluate should report missing required artifact for active stage`() {
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(FixedArtifactNamingRule(), RequiredArtifactRule()),
        )

        val result = engine.evaluate(
            request = stageRequest(workflowId = "spec-test-missing-artifact"),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val requiredResult = result.ruleResults.first { it.ruleId == "artifact-required" }
        assertEquals(listOf(StageId.REQUIREMENTS), requiredResult.appliedStages)
        assertEquals(1, requiredResult.violations.size)
        assertTrue(requiredResult.violations.single().message.contains("requirements.md"))

        val namingResult = result.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertTrue(namingResult.violations.isEmpty())
    }

    @Test
    fun `evaluate should prefer fixed naming violation over missing artifact when alias exists`() {
        val workflowId = "spec-test-fixed-naming"
        artifactService.writeArtifact(workflowId, "requirement.md", "# misplaced requirement\n")
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(FixedArtifactNamingRule(), RequiredArtifactRule()),
        )

        val result = engine.evaluate(
            request = stageRequest(workflowId = workflowId),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val namingResult = result.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertEquals(1, namingResult.violations.size)
        assertEquals("requirement.md", namingResult.violations.single().fileName)
        assertTrue(namingResult.violations.single().message.contains("requirements.md"))

        val requiredResult = result.ruleResults.first { it.ruleId == "artifact-required" }
        assertTrue(requiredResult.violations.isEmpty())
    }

    @Test
    fun `evaluate should ignore inactive verify artifact for baseline rules`() {
        val stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = false))
        val workflow = SpecWorkflow(
            id = "spec-test-inactive-verify",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            stageStates = stagePlan.initialStageStates("2026-03-09T00:00:00Z"),
            currentStage = StageId.IMPLEMENT,
        )
        val request = StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.IMPLEMENT,
            targetStage = StageId.ARCHIVE,
            evaluatedStages = listOf(StageId.VERIFY),
            stagePlan = stagePlan,
            workflow = workflow,
        )
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(FixedArtifactNamingRule(), RequiredArtifactRule()),
        )

        val result = engine.evaluate(
            request = request,
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.PASS, result.status)
        assertTrue(result.ruleResults.all { it.violations.isEmpty() })
    }

    private fun stageRequest(workflowId: String = "spec-test-rule-framework"): StageTransitionRequest {
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to SpecDocument(
                    id = "requirements-doc",
                    phase = SpecPhase.SPECIFY,
                    content = "## 功能需求\n- details\n\n## 非功能需求\n- stability\n\n## 用户故事\nAs a user, I want traceable rules.",
                    metadata = SpecMetadata(
                        title = "Requirements",
                        description = "rule test",
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            stageStates = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
                .buildStagePlan(StageActivationOptions.of(verifyEnabled = false))
                .initialStageStates("2026-03-09T00:00:00Z"),
            currentStage = StageId.REQUIREMENTS,
        )
        return StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.REQUIREMENTS,
            targetStage = StageId.DESIGN,
            evaluatedStages = listOf(StageId.REQUIREMENTS),
            stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
                .buildStagePlan(StageActivationOptions.of(verifyEnabled = false)),
            workflow = workflow,
        )
    }

    private fun testRule(
        id: String,
        severity: GateStatus,
        remediationHint: String,
    ): Rule {
        return object : Rule {
            override val id: String = id
            override val description: String = "Synthetic rule $id"
            override val defaultSeverity: GateStatus = severity
            override val remediationHint: String = remediationHint

            override fun appliesTo(stage: StageId): Boolean = stage == StageId.REQUIREMENTS

            override fun evaluate(ctx: RuleContext): List<Violation> {
                return listOf(
                    Violation(
                        ruleId = id,
                        severity = severity,
                        fileName = "requirements.md",
                        line = 1,
                        message = "Synthetic violation for $id",
                    ),
                )
            }
        }
    }
}
