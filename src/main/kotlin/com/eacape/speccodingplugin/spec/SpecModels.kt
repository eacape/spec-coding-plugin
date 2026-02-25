package com.eacape.speccodingplugin.spec

/**
 * Spec 工作流阶段枚举
 */
enum class SpecPhase(
    val displayName: String,
    val description: String,
    val outputFileName: String
) {
    /**
     * Specify 阶段 - 需求规格化
     * 输入: 自然语言描述
     * 输出: requirements.md
     */
    SPECIFY(
        displayName = "Specify",
        description = "将自然语言需求转换为结构化的需求文档",
        outputFileName = "requirements.md"
    ),

    /**
     * Design 阶段 - 技术方案设计
     * 输入: requirements.md
     * 输出: design.md
     */
    DESIGN(
        displayName = "Design",
        description = "基于需求文档设计技术方案和架构",
        outputFileName = "design.md"
    ),

    /**
     * Implement 阶段 - 任务拆解与实现
     * 输入: design.md
     * 输出: tasks.md + 代码
     */
    IMPLEMENT(
        displayName = "Implement",
        description = "将设计方案拆解为具体任务并生成代码",
        outputFileName = "tasks.md"
    );

    /**
     * 获取下一个阶段
     */
    fun next(): SpecPhase? {
        return when (this) {
            SPECIFY -> DESIGN
            DESIGN -> IMPLEMENT
            IMPLEMENT -> null
        }
    }

    /**
     * 获取上一个阶段
     */
    fun previous(): SpecPhase? {
        return when (this) {
            SPECIFY -> null
            DESIGN -> SPECIFY
            IMPLEMENT -> DESIGN
        }
    }

    /**
     * 是否是第一个阶段
     */
    fun isFirst(): Boolean = this == SPECIFY

    /**
     * 是否是最后一个阶段
     */
    fun isLast(): Boolean = this == IMPLEMENT
}

/**
 * Spec 文档数据模型
 */
data class SpecDocument(
    val id: String,
    val phase: SpecPhase,
    val content: String,
    val metadata: SpecMetadata,
    val validationResult: ValidationResult? = null
)

/**
 * Spec 元数据
 */
data class SpecMetadata(
    val title: String,
    val description: String,
    val author: String = System.getProperty("user.name") ?: "Unknown",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val version: String = "1.0.0",
    val tags: List<String> = emptyList()
)

/**
 * 验证结果
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
) {
    /**
     * 是否有错误
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /**
     * 是否有警告
     */
    fun hasWarnings(): Boolean = warnings.isNotEmpty()

    /**
     * 获取摘要
     */
    fun getSummary(): String {
        return buildString {
            if (valid) {
                appendLine("✓ 验证通过")
            } else {
                appendLine("✗ 验证失败")
            }

            if (errors.isNotEmpty()) {
                appendLine("\n错误 (${errors.size}):")
                errors.forEach { appendLine("  - $it") }
            }

            if (warnings.isNotEmpty()) {
                appendLine("\n警告 (${warnings.size}):")
                warnings.forEach { appendLine("  - $it") }
            }

            if (suggestions.isNotEmpty()) {
                appendLine("\n建议 (${suggestions.size}):")
                suggestions.forEach { appendLine("  - $it") }
            }
        }
    }
}

/**
 * Spec 工作流需求意图
 */
enum class SpecChangeIntent {
    /**
     * 全量需求，从零开始
     */
    FULL,

    /**
     * 增量需求，基于已有规格演进
     */
    INCREMENTAL,
}

/**
 * Spec 工作流状态
 */
data class SpecWorkflow(
    val id: String,
    val currentPhase: SpecPhase,
    val documents: Map<SpecPhase, SpecDocument>,
    val status: WorkflowStatus,
    val title: String = "",
    val description: String = "",
    val changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
    val baselineWorkflowId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取当前阶段的文档
     */
    fun getCurrentDocument(): SpecDocument? {
        return documents[currentPhase]
    }

    /**
     * 获取指定阶段的文档
     */
    fun getDocument(phase: SpecPhase): SpecDocument? {
        return documents[phase]
    }

    /**
     * 是否可以进入下一阶段
     */
    fun canProceedToNext(): Boolean {
        // 当前阶段必须有文档且验证通过
        val currentDoc = getCurrentDocument() ?: return false
        val validation = SpecValidator.validate(currentDoc)
        return validation.valid && currentPhase.next() != null
    }

    /**
     * 是否可以返回上一阶段
     */
    fun canGoBack(): Boolean {
        return currentPhase.previous() != null
    }

    /**
     * 是否完成
     */
    fun isCompleted(): Boolean {
        return status == WorkflowStatus.COMPLETED
    }

    /**
     * 是否是增量需求工作流
     */
    fun isIncrementalWorkflow(): Boolean {
        return changeIntent == SpecChangeIntent.INCREMENTAL && !baselineWorkflowId.isNullOrBlank()
    }
}

/**
 * 工作流状态枚举
 */
enum class WorkflowStatus {
    /**
     * 进行中
     */
    IN_PROGRESS,

    /**
     * 已暂停
     */
    PAUSED,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 已失败
     */
    FAILED
}

/**
 * Spec 生成请求
 */
data class SpecGenerationRequest(
    val phase: SpecPhase,
    val input: String,
    val previousDocument: SpecDocument? = null,
    val options: GenerationOptions = GenerationOptions()
)

/**
 * 生成选项
 */
data class GenerationOptions(
    val providerId: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val requestId: String? = null,
    val includeExamples: Boolean = true,
    val validateOutput: Boolean = true,
    val confirmedContext: String? = null,
    val clarificationQuestionBudget: Int = 5,
    val workingDirectory: String? = null,
    val operationMode: String? = null,
)

/**
 * 规格澄清草稿（由 AI 反提问生成）
 */
data class SpecClarificationDraft(
    val phase: SpecPhase,
    val questions: List<String>,
    val rawContent: String,
) {
    fun hasQuestions(): Boolean = questions.isNotEmpty()
}

/**
 * Spec 生成结果
 */
sealed class SpecGenerationResult {
    data class Success(val document: SpecDocument) : SpecGenerationResult()
    data class Failure(val error: String, val details: String? = null) : SpecGenerationResult()
    data class ValidationFailed(val document: SpecDocument, val validation: ValidationResult) : SpecGenerationResult()
}
