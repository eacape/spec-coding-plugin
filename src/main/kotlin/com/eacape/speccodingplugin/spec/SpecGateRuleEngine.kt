package com.eacape.speccodingplugin.spec

import java.nio.file.Files
import java.nio.file.Path

data class RuleArtifactContext(
    val stageId: StageId,
    val fileName: String?,
    val path: Path?,
    val actualPath: Path?,
    val exists: Boolean,
    val caseMismatch: Boolean,
    val aliasPaths: List<Path>,
    val phase: SpecPhase?,
    val document: SpecDocument?,
) {
    fun hasNamingMismatch(): Boolean = caseMismatch || aliasPaths.isNotEmpty()
}

data class RuleContext(
    val request: StageTransitionRequest,
    val projectConfig: SpecProjectConfig,
    val artifacts: Map<StageId, RuleArtifactContext>,
) {
    val workflow: SpecWorkflow
        get() = request.workflow

    val evaluatedStages: List<StageId>
        get() = request.evaluatedStages.distinct()

    fun artifact(stageId: StageId): RuleArtifactContext? = artifacts[stageId]

    fun applicableStages(rule: Rule): List<StageId> = evaluatedStages.filter(rule::appliesTo)

    fun rulePolicy(ruleId: String): SpecRulePolicy? = projectConfig.rules[ruleId]
}

interface Rule {
    val id: String
    val description: String
    val defaultSeverity: GateStatus
    val remediationHint: String?

    fun appliesTo(stage: StageId): Boolean

    fun evaluate(ctx: RuleContext): List<Violation>
}

data class RuleEvaluationResult(
    val ruleId: String,
    val description: String,
    val enabled: Boolean,
    val appliedStages: List<StageId>,
    val defaultSeverity: GateStatus,
    val effectiveSeverity: GateStatus,
    val severityOverridden: Boolean,
    val summary: String,
    val violations: List<Violation>,
)

class SpecGateRuleEngine(
    private val artifactService: SpecArtifactService,
    private val rules: List<Rule>,
) {
    fun evaluate(
        request: StageTransitionRequest,
        projectConfig: SpecProjectConfig,
    ): GateResult {
        val context = RuleContext(
            request = request,
            projectConfig = projectConfig,
            artifacts = buildArtifacts(request),
        )
        val evaluations = rules
            .sortedBy(Rule::id)
            .map { rule -> evaluateRule(rule, context) }
        return GateResult.fromRuleResults(evaluations)
    }

    private fun buildArtifacts(request: StageTransitionRequest): Map<StageId, RuleArtifactContext> {
        val workflowArtifacts = artifactService.listWorkflowMarkdownArtifacts(request.workflowId)
        val workflowArtifactsByLowercaseName = workflowArtifacts.associateBy {
            it.fileName.toString().lowercase()
        }
        return request.evaluatedStages
            .distinct()
            .associateWith { stageId ->
                val fileName = stageId.artifactFileName
                val path = fileName?.let { artifactService.locateArtifact(request.workflowId, stageId) }
                val actualPath = fileName?.let { workflowArtifactsByLowercaseName[it.lowercase()] }
                val phase = stageId.toDocumentPhaseOrNull()
                RuleArtifactContext(
                    stageId = stageId,
                    fileName = fileName,
                    path = path,
                    actualPath = actualPath,
                    exists = actualPath != null || path?.let(Files::exists) == true,
                    caseMismatch = actualPath != null && actualPath.fileName.toString() != fileName,
                    aliasPaths = fileName?.let {
                        workflowArtifacts.filter { candidate ->
                            candidate.fileName.toString().lowercase() != it.lowercase() &&
                                candidate.fileName.toString().lowercase() in stageId.namingAliasFileNames()
                        }
                    } ?: emptyList(),
                    phase = phase,
                    document = phase?.let(request.workflow::getDocument),
                )
            }
    }

    private fun evaluateRule(rule: Rule, ctx: RuleContext): RuleEvaluationResult {
        val policy = ctx.rulePolicy(rule.id)
        val enabled = policy?.enabled ?: true
        val appliedStages = ctx.applicableStages(rule)
        val effectiveSeverity = policy?.severityOverride ?: rule.defaultSeverity
        val severityOverridden = policy?.severityOverride != null

        if (!enabled) {
            return RuleEvaluationResult(
                ruleId = rule.id,
                description = rule.description,
                enabled = false,
                appliedStages = appliedStages,
                defaultSeverity = rule.defaultSeverity,
                effectiveSeverity = effectiveSeverity,
                severityOverridden = severityOverridden,
                summary = "Disabled by project config.",
                violations = emptyList(),
            )
        }

        if (appliedStages.isEmpty()) {
            return RuleEvaluationResult(
                ruleId = rule.id,
                description = rule.description,
                enabled = true,
                appliedStages = emptyList(),
                defaultSeverity = rule.defaultSeverity,
                effectiveSeverity = effectiveSeverity,
                severityOverridden = severityOverridden,
                summary = "Not applicable for current gate scope.",
                violations = emptyList(),
            )
        }

        val violations = rule.evaluate(ctx)
            .map { violation ->
                violation.copy(
                    ruleId = rule.id,
                    severity = policy?.severityOverride ?: violation.severity,
                    fixHint = violation.fixHint ?: rule.remediationHint,
                )
            }
            .sortedWith(compareBy(Violation::fileName, Violation::line, Violation::ruleId, Violation::message))

        val summary = if (violations.isEmpty()) {
            "Passed for ${appliedStages.joinToString(",") { it.name }}."
        } else {
            "Found ${violations.size} violation(s) for ${appliedStages.joinToString(",") { it.name }}."
        }

        return RuleEvaluationResult(
            ruleId = rule.id,
            description = rule.description,
            enabled = true,
            appliedStages = appliedStages,
            defaultSeverity = rule.defaultSeverity,
            effectiveSeverity = effectiveSeverity,
            severityOverridden = severityOverridden,
            summary = summary,
            violations = violations,
        )
    }
}

class RequiredArtifactRule : Rule {
    override val id: String = "artifact-required"
    override val description: String = "Require active stage artifacts to exist before transition."
    override val defaultSeverity: GateStatus = GateStatus.ERROR
    override val remediationHint: String = "Create the required artifact before continuing."

    override fun appliesTo(stage: StageId): Boolean = stage.requiresArtifact()

    override fun evaluate(ctx: RuleContext): List<Violation> {
        val violations = mutableListOf<Violation>()
        ctx.applicableStages(this)
            .filter(ctx.request.stagePlan::participatesInGate)
            .forEach { stageId ->
                val artifact = ctx.artifact(stageId) ?: return@forEach
                val fileName = artifact.fileName ?: return@forEach
                if (!artifact.exists && !artifact.hasNamingMismatch()) {
                    violations += Violation(
                        ruleId = id,
                        severity = defaultSeverity,
                        fileName = fileName,
                        line = 1,
                        message = "Required artifact $fileName is missing for stage $stageId",
                        fixHint = "Create $fileName before continuing",
                    )
                }
            }
        return violations
    }
}

class FixedArtifactNamingRule : Rule {
    override val id: String = "artifact-fixed-naming"
    override val description: String = "Require active stage artifacts to use the canonical fixed file names."
    override val defaultSeverity: GateStatus = GateStatus.ERROR
    override val remediationHint: String = "Rename the artifact to the canonical fixed file name."

    override fun appliesTo(stage: StageId): Boolean = stage.requiresArtifact()

    override fun evaluate(ctx: RuleContext): List<Violation> {
        val violations = mutableListOf<Violation>()
        ctx.applicableStages(this)
            .filter(ctx.request.stagePlan::participatesInGate)
            .forEach { stageId ->
                val artifact = ctx.artifact(stageId) ?: return@forEach
                val expectedFileName = artifact.fileName ?: return@forEach

                if (artifact.caseMismatch) {
                    val actualFileName = artifact.actualPath?.fileName?.toString() ?: expectedFileName
                    violations += Violation(
                        ruleId = id,
                        severity = defaultSeverity,
                        fileName = actualFileName,
                        line = 1,
                        message = "Artifact for stage $stageId must be named $expectedFileName, found $actualFileName",
                        fixHint = "Rename $actualFileName to $expectedFileName",
                    )
                }

                if (!artifact.exists && artifact.aliasPaths.isNotEmpty()) {
                    artifact.aliasPaths
                        .sortedBy { it.fileName.toString().lowercase() }
                        .forEach { aliasPath ->
                            val actualFileName = aliasPath.fileName.toString()
                            violations += Violation(
                                ruleId = id,
                                severity = defaultSeverity,
                                fileName = actualFileName,
                                line = 1,
                                message = "Artifact for stage $stageId must use fixed name $expectedFileName, found $actualFileName",
                                fixHint = "Rename $actualFileName to $expectedFileName",
                            )
                        }
                }
            }
        return violations
    }
}

class DocumentValidationRule : Rule {
    override val id: String = "document-validation"
    override val description: String = "Validate workflow documents before stage transition."
    override val defaultSeverity: GateStatus = GateStatus.ERROR
    override val remediationHint: String = "Fix the document validation errors before continuing."

    override fun appliesTo(stage: StageId): Boolean = stage.toDocumentPhaseOrNull() != null

    override fun evaluate(ctx: RuleContext): List<Violation> {
        val violations = mutableListOf<Violation>()
        ctx.applicableStages(this).forEach { stageId ->
            val artifact = ctx.artifact(stageId) ?: return@forEach
            val phase = artifact.phase ?: return@forEach
            val document = artifact.document ?: return@forEach
            val validation = SpecValidator.validate(document)
            validation.errors.forEach { message ->
                violations += Violation(
                    ruleId = id,
                    severity = defaultSeverity,
                    fileName = phase.outputFileName,
                    line = 1,
                    message = message,
                )
            }
        }
        return violations
    }
}

private fun StageId.toDocumentPhaseOrNull(): SpecPhase? {
    return when (this) {
        StageId.REQUIREMENTS -> SpecPhase.SPECIFY
        StageId.DESIGN -> SpecPhase.DESIGN
        StageId.TASKS -> SpecPhase.IMPLEMENT
        StageId.IMPLEMENT,
        StageId.VERIFY,
        StageId.ARCHIVE,
        -> null
    }
}

private fun StageId.namingAliasFileNames(): Set<String> {
    return when (this) {
        StageId.REQUIREMENTS -> setOf("requirement.md")
        StageId.DESIGN -> emptySet()
        StageId.TASKS -> setOf("task.md")
        StageId.VERIFY -> setOf("verify.md")
        StageId.IMPLEMENT,
        StageId.ARCHIVE,
        -> emptySet()
    }
}
