package com.eacape.speccodingplugin.spec

/**
 * Workflow 模板枚举。
 */
enum class WorkflowTemplate {
    FULL_SPEC,
    QUICK_TASK,
    DESIGN_REVIEW,
    DIRECT_IMPLEMENT,
}

/**
 * 标准化阶段标识。
 */
enum class StageId(val artifactFileName: String?) {
    REQUIREMENTS("requirements.md"),
    DESIGN("design.md"),
    TASKS("tasks.md"),
    IMPLEMENT(null),
    VERIFY("verification.md"),
    ARCHIVE(null),
    ;

    fun requiresArtifact(): Boolean = artifactFileName != null
}

data class StagePlanItem(
    val id: StageId,
    val optional: Boolean = false,
    val defaultEnabled: Boolean = !optional,
) {
    fun isEnabled(options: StageActivationOptions = StageActivationOptions()): Boolean {
        return if (!optional) {
            true
        } else {
            options.overrideFor(id) ?: defaultEnabled
        }
    }
}

data class StageActivationOptions(
    val stageOverrides: Map<StageId, Boolean> = emptyMap(),
) {
    fun overrideFor(stageId: StageId): Boolean? = stageOverrides[stageId]

    companion object {
        fun of(
            verifyEnabled: Boolean? = null,
            implementEnabled: Boolean? = null,
        ): StageActivationOptions {
            val overrides = linkedMapOf<StageId, Boolean>()
            verifyEnabled?.let { overrides[StageId.VERIFY] = it }
            implementEnabled?.let { overrides[StageId.IMPLEMENT] = it }
            return StageActivationOptions(stageOverrides = overrides)
        }

        fun fromStageStates(stageStates: Map<StageId, StageState>): StageActivationOptions {
            return StageActivationOptions(
                stageOverrides = stageStates.mapValues { (_, state) -> state.active },
            )
        }
    }
}

enum class StageProgress {
    NOT_STARTED,
    IN_PROGRESS,
    DONE,
}

data class StageState(
    val active: Boolean,
    val status: StageProgress,
    val enteredAt: String? = null,
    val completedAt: String? = null,
)

data class WorkflowMeta(
    val workflowId: String,
    val title: String?,
    val template: WorkflowTemplate,
    val stageStates: Map<StageId, StageState>,
    val currentStage: StageId,
    val verifyEnabled: Boolean,
    val configPinHash: String?,
    val baselineWorkflowId: String?,
    val status: WorkflowStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

data class WorkflowSnapshot(
    val meta: WorkflowMeta,
    val workflow: SpecWorkflow,
    val documents: Map<SpecPhase, SpecDocument>,
)

/**
 * 模板定义：固定阶段顺序 + 可选阶段默认启用策略。
 */
data class TemplateDefinition(
    val template: WorkflowTemplate,
    val stagePlan: List<StagePlanItem>,
) {
    init {
        require(stagePlan.isNotEmpty()) { "stagePlan must not be empty" }
        require(stagePlan.map { it.id }.distinct().size == stagePlan.size) {
            "stagePlan contains duplicated stage ids"
        }
        require(stagePlan.last().id == StageId.ARCHIVE) {
            "template $template must end with ARCHIVE stage"
        }
    }

    fun activeStages(
        verifyEnabled: Boolean? = null,
        implementEnabled: Boolean? = null,
    ): List<StageId> {
        return buildStagePlan(
            StageActivationOptions.of(
                verifyEnabled = verifyEnabled,
                implementEnabled = implementEnabled,
            ),
        ).activeStages
    }

    fun buildStagePlan(
        options: StageActivationOptions = StageActivationOptions(),
    ): WorkflowStagePlan {
        val orderedStages = stagePlan.map { it.id }
        val activeStages = stagePlan
            .filter { item -> item.isEnabled(options) }
            .map { it.id }
        return WorkflowStagePlan(
            template = template,
            orderedStages = orderedStages,
            activeStages = activeStages,
        )
    }
}

data class WorkflowStagePlan(
    val template: WorkflowTemplate,
    val orderedStages: List<StageId>,
    val activeStages: List<StageId>,
) {
    init {
        require(orderedStages.isNotEmpty()) { "orderedStages must not be empty" }
        require(activeStages.isNotEmpty()) { "activeStages must not be empty for template $template" }
        require(activeStages.all { orderedStages.contains(it) }) {
            "activeStages must be a subset of orderedStages for template $template"
        }
    }

    val inactiveStages: List<StageId>
        get() = orderedStages.filterNot { isActive(it) }

    val gateArtifactStages: List<StageId>
        get() = activeStages.filter { it.requiresArtifact() }

    val firstActiveStage: StageId
        get() = activeStages.first()

    fun isActive(stageId: StageId): Boolean = activeStages.contains(stageId)

    fun activeStageIndex(stageId: StageId): Int {
        val index = activeStages.indexOf(stageId)
        require(index >= 0) {
            "stage $stageId is not active for template $template"
        }
        return index
    }

    fun activeStagesBefore(stageId: StageId): List<StageId> {
        return activeStages.take(activeStageIndex(stageId))
    }

    fun activeStagesThrough(stageId: StageId): List<StageId> {
        return activeStages.take(activeStageIndex(stageId) + 1)
    }

    fun activeStagesBetween(from: StageId, to: StageId): List<StageId> {
        val fromIndex = activeStageIndex(from)
        val toIndex = activeStageIndex(to)
        return if (fromIndex <= toIndex) {
            activeStages.subList(fromIndex, toIndex + 1)
        } else {
            activeStages.subList(toIndex, fromIndex + 1)
        }
    }

    fun participatesInGate(stageId: StageId): Boolean {
        return stageId.requiresArtifact() && isActive(stageId)
    }

    fun nextActiveStage(current: StageId): StageId? {
        return orderedStages
            .dropWhile { it != current }
            .drop(1)
            .firstOrNull { isActive(it) }
    }

    fun previousActiveStage(current: StageId): StageId? {
        return orderedStages
            .takeWhile { it != current }
            .lastOrNull { isActive(it) }
    }

    fun resolveCurrentStage(preferred: StageId): StageId {
        if (isActive(preferred)) {
            return preferred
        }
        val preferredIndex = StageId.entries.indexOf(preferred)
        return activeStages.firstOrNull { StageId.entries.indexOf(it) > preferredIndex }
            ?: activeStages.lastOrNull { StageId.entries.indexOf(it) < preferredIndex }
            ?: firstActiveStage
    }

    fun initialStageStates(enteredAt: String): Map<StageId, StageState> {
        val states = linkedMapOf<StageId, StageState>()
        StageId.entries.forEach { stageId ->
            val active = isActive(stageId)
            states[stageId] = if (stageId == firstActiveStage) {
                StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = enteredAt,
                    completedAt = null,
                )
            } else {
                StageState(
                    active = active,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }
        return states
    }
}

object WorkflowTemplates {
    private val defaultDefinitions: Map<WorkflowTemplate, TemplateDefinition> = mapOf(
        WorkflowTemplate.FULL_SPEC to TemplateDefinition(
            template = WorkflowTemplate.FULL_SPEC,
            stagePlan = listOf(
                StagePlanItem(StageId.REQUIREMENTS),
                StagePlanItem(StageId.DESIGN),
                StagePlanItem(StageId.TASKS),
                StagePlanItem(StageId.IMPLEMENT),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
        WorkflowTemplate.QUICK_TASK to TemplateDefinition(
            template = WorkflowTemplate.QUICK_TASK,
            stagePlan = listOf(
                StagePlanItem(StageId.TASKS),
                StagePlanItem(StageId.IMPLEMENT),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
        WorkflowTemplate.DESIGN_REVIEW to TemplateDefinition(
            template = WorkflowTemplate.DESIGN_REVIEW,
            stagePlan = listOf(
                StagePlanItem(StageId.DESIGN),
                StagePlanItem(StageId.TASKS),
                StagePlanItem(StageId.IMPLEMENT, optional = true, defaultEnabled = true),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
        WorkflowTemplate.DIRECT_IMPLEMENT to TemplateDefinition(
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            stagePlan = listOf(
                StagePlanItem(StageId.IMPLEMENT),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
    )

    fun definitionOf(template: WorkflowTemplate): TemplateDefinition = defaultDefinitions.getValue(template)

    fun defaultDefinitions(): Map<WorkflowTemplate, TemplateDefinition> = defaultDefinitions.toMap()
}

enum class GateStatus {
    PASS,
    WARNING,
    ERROR,
}

data class Violation(
    val ruleId: String,
    val severity: GateStatus,
    val fileName: String,
    val line: Int,
    val message: String,
    val fixHint: String? = null,
)

data class GateResult(
    val status: GateStatus,
    val violations: List<Violation>,
    val ruleResults: List<RuleEvaluationResult> = emptyList(),
) {
    companion object {
        fun fromViolations(
            violations: List<Violation>,
            ruleResults: List<RuleEvaluationResult> = emptyList(),
        ): GateResult {
            val status = when {
                violations.any { it.severity == GateStatus.ERROR } -> GateStatus.ERROR
                violations.any { it.severity == GateStatus.WARNING } -> GateStatus.WARNING
                else -> GateStatus.PASS
            }
            val sortedViolations = violations.sortedWith(compareBy(Violation::fileName, Violation::line, Violation::ruleId))
            return GateResult(
                status = status,
                violations = sortedViolations,
                ruleResults = ruleResults.sortedBy(RuleEvaluationResult::ruleId),
            )
        }

        fun fromRuleResults(ruleResults: List<RuleEvaluationResult>): GateResult {
            return fromViolations(
                violations = ruleResults.flatMap(RuleEvaluationResult::violations),
                ruleResults = ruleResults,
            )
        }
    }
}

enum class StageTransitionType {
    ADVANCE,
    JUMP,
    ROLLBACK,
}

data class StageTransitionRequest(
    val workflowId: String,
    val transitionType: StageTransitionType,
    val fromStage: StageId,
    val targetStage: StageId,
    val evaluatedStages: List<StageId>,
    val stagePlan: WorkflowStagePlan,
    val workflow: SpecWorkflow,
)

fun interface SpecStageGateEvaluator {
    fun evaluate(request: StageTransitionRequest): GateResult
}

data class StageTransitionResult(
    val workflow: SpecWorkflow,
    val transitionType: StageTransitionType,
    val fromStage: StageId,
    val targetStage: StageId,
    val gateResult: GateResult,
    val warningConfirmed: Boolean,
    val beforeSnapshotId: String? = null,
    val afterSnapshotId: String? = null,
)

enum class TemplateSwitchArtifactStrategy {
    REUSE_EXISTING,
    GENERATE_SKELETON,
    BLOCK_SWITCH,
}

data class TemplateSwitchArtifactImpact(
    val stageId: StageId,
    val fileName: String,
    val exists: Boolean,
    val strategy: TemplateSwitchArtifactStrategy,
)

data class TemplateSwitchPreview(
    val previewId: String,
    val workflowId: String,
    val fromTemplate: WorkflowTemplate,
    val toTemplate: WorkflowTemplate,
    val currentStage: StageId,
    val resultingStage: StageId,
    val addedActiveStages: List<StageId>,
    val deactivatedStages: List<StageId>,
    val gateAddedStages: List<StageId>,
    val gateRemovedStages: List<StageId>,
    val artifactImpacts: List<TemplateSwitchArtifactImpact>,
) {
    val currentStageChanged: Boolean
        get() = currentStage != resultingStage

    val missingRequiredArtifacts: List<TemplateSwitchArtifactImpact>
        get() = artifactImpacts.filterNot { it.exists }
}

data class TemplateSwitchApplyResult(
    val switchId: String,
    val previewId: String,
    val workflow: SpecWorkflow,
    val generatedArtifacts: List<String>,
    val beforeSnapshotId: String? = null,
    val afterSnapshotId: String? = null,
)

data class TemplateSwitchRollbackResult(
    val switchId: String,
    val workflow: SpecWorkflow,
    val restoredFromSnapshotId: String,
    val beforeSnapshotId: String? = null,
    val afterSnapshotId: String? = null,
)

enum class TaskPriority {
    P0,
    P1,
    P2,
}

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    CANCELLED,
    ;

    fun canTransitionTo(next: TaskStatus): Boolean {
        return when (this) {
            PENDING -> next == IN_PROGRESS || next == CANCELLED
            IN_PROGRESS -> next == COMPLETED || next == BLOCKED || next == CANCELLED
            BLOCKED -> next == IN_PROGRESS || next == CANCELLED
            COMPLETED -> false
            CANCELLED -> false
        }
    }
}

enum class VerificationConclusion {
    PASS,
    WARN,
    FAIL,
}

data class TaskVerificationResult(
    val conclusion: VerificationConclusion,
    val runId: String,
    val summary: String,
    val at: String,
)

data class StructuredTask(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val dependsOn: List<String> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val verificationResult: TaskVerificationResult? = null,
)

sealed class WorkflowDomainError(message: String) : IllegalStateException(message)

class InvalidStageTransitionError(from: StageId, to: StageId) :
    WorkflowDomainError("Invalid stage transition: $from -> $to")

class InactiveWorkflowStageError(stageId: StageId) :
    WorkflowDomainError("Stage $stageId is not active for the current workflow")

class StageTransitionPolicyError(message: String) :
    WorkflowDomainError(message)

class StageWarningConfirmationRequiredError(from: StageId, to: StageId) :
    WorkflowDomainError("Stage transition $from -> $to requires explicit warning confirmation")

class StageTransitionBlockedByGateError(
    from: StageId,
    to: StageId,
    val gateResult: GateResult,
) : WorkflowDomainError("Stage transition $from -> $to blocked by gate status ${gateResult.status}")

class InvalidTaskStateTransitionError(taskId: String, from: TaskStatus, to: TaskStatus) :
    WorkflowDomainError("Invalid task state transition for $taskId: $from -> $to")

class DuplicateTaskIdError(taskId: String) :
    WorkflowDomainError("Duplicate task id detected: $taskId")

class MissingTaskDependencyError(taskId: String, missingDependencyId: String) :
    WorkflowDomainError("Task $taskId depends on missing task id: $missingDependencyId")

class MissingTemplateSwitchPreviewError(previewId: String) :
    WorkflowDomainError("Template switch preview not found: $previewId")

class StaleTemplateSwitchPreviewError(previewId: String, workflowId: String) :
    WorkflowDomainError("Template switch preview $previewId is stale for workflow $workflowId")

class MissingTemplateSwitchHistoryError(workflowId: String, switchId: String) :
    WorkflowDomainError("Template switch $switchId not found for workflow $workflowId")
