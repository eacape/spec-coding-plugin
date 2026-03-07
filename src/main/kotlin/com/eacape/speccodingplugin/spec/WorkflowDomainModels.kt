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
        return stagePlan
            .filter { item ->
                when (item.id) {
                    StageId.VERIFY -> if (item.optional) verifyEnabled ?: item.defaultEnabled else true
                    StageId.IMPLEMENT -> if (item.optional) implementEnabled ?: item.defaultEnabled else true
                    else -> if (item.optional) item.defaultEnabled else true
                }
            }
            .map { it.id }
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
) {
    companion object {
        fun fromViolations(violations: List<Violation>): GateResult {
            val status = when {
                violations.any { it.severity == GateStatus.ERROR } -> GateStatus.ERROR
                violations.any { it.severity == GateStatus.WARNING } -> GateStatus.WARNING
                else -> GateStatus.PASS
            }
            val sortedViolations = violations.sortedWith(compareBy(Violation::fileName, Violation::line, Violation::ruleId))
            return GateResult(status = status, violations = sortedViolations)
        }
    }
}

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

class InvalidTaskStateTransitionError(taskId: String, from: TaskStatus, to: TaskStatus) :
    WorkflowDomainError("Invalid task state transition for $taskId: $from -> $to")

class DuplicateTaskIdError(taskId: String) :
    WorkflowDomainError("Duplicate task id detected: $taskId")

class MissingTaskDependencyError(taskId: String, missingDependencyId: String) :
    WorkflowDomainError("Task $taskId depends on missing task id: $missingDependencyId")
