package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.*
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Spec 文档生成器
 * 使用 LLM 生成各阶段的 Spec 文档
 */
class SpecGenerator(
    private val llmRouter: LlmRouter
) {
    private val logger = thisLogger()

    /**
     * 生成 Spec 文档
     */
    suspend fun generate(request: SpecGenerationRequest): SpecGenerationResult = withContext(Dispatchers.IO) {
        try {
            logger.info("Generating ${request.phase} document")

            // 构建 Prompt
            val prompt = buildPrompt(request)

            // 调用 LLM
            val llmRequest = LlmRequest(
                messages = listOf(
                    LlmMessage(LlmRole.SYSTEM, getSystemPrompt(request.phase)),
                    LlmMessage(LlmRole.USER, prompt)
                ),
                model = request.options.model,
                temperature = request.options.temperature,
                maxTokens = request.options.maxTokens,
                metadata = buildRequestMetadata(request.options),
                workingDirectory = request.options.workingDirectory,
            )

            val response = runCatching {
                requestLlmResponse(
                    providerId = request.options.providerId,
                    llmRequest = llmRequest,
                    requestTag = "${request.phase} document generation",
                )
            }

            if (response.isFailure) {
                return@withContext SpecGenerationResult.Failure(
                    error = "LLM 调用失败",
                    details = response.exceptionOrNull()?.message
                )
            }

            val llmResponse = response.getOrThrow()
            val content = normalizeGeneratedDocument(
                phase = request.phase,
                rawContent = llmResponse.content,
            )

            // 创建文档
            val document = SpecDocument(
                id = "${System.currentTimeMillis()}-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document"
                )
            )

            // 验证文档
            if (request.options.validateOutput) {
                val validation = SpecValidator.validate(document)
                val validatedDocument = document.copy(validationResult = validation)

                if (!validation.valid) {
                    return@withContext SpecGenerationResult.ValidationFailed(
                        document = validatedDocument,
                        validation = validation
                    )
                }

                return@withContext SpecGenerationResult.Success(validatedDocument)
            }

            SpecGenerationResult.Success(document)
        } catch (e: Exception) {
            logger.warn("Failed to generate ${request.phase} document", e)
            SpecGenerationResult.Failure(
                error = "生成失败: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }

    /**
     * 在正式生成前草拟澄清问题，供用户确认需求/研发细节。
     */
    suspend fun draftClarification(request: SpecGenerationRequest): Result<SpecClarificationDraft> = withContext(Dispatchers.IO) {
        runCatching {
            logger.info("Drafting clarification questions for ${request.phase}")
            val prompt = buildClarificationPrompt(request)
            val budget = request.options.clarificationQuestionBudget
                .coerceIn(1, MAX_CLARIFICATION_QUESTION_BUDGET)

            val llmRequest = LlmRequest(
                messages = listOf(
                    LlmMessage(LlmRole.SYSTEM, getClarificationSystemPrompt(request.phase)),
                    LlmMessage(LlmRole.USER, prompt),
                ),
                model = request.options.model,
                temperature = request.options.temperature,
                maxTokens = request.options.maxTokens,
                metadata = buildRequestMetadata(request.options) + ("specClarification" to "true"),
                workingDirectory = request.options.workingDirectory,
            )

            val response = requestLlmResponse(
                providerId = request.options.providerId,
                llmRequest = llmRequest,
                requestTag = "${request.phase} clarification draft",
            )
            parseClarificationDraft(
                phase = request.phase,
                rawContent = SpecMarkdownSanitizer.sanitize(response.content),
                maxQuestions = budget,
            )
        }.onFailure { error ->
            logger.warn("Failed to draft clarification for ${request.phase}", error)
        }
    }

    /**
     * 构建生成 Prompt
     */
    private fun buildPrompt(request: SpecGenerationRequest): String {
        return when (request.phase) {
            SpecPhase.SPECIFY -> buildSpecifyPrompt(request)
            SpecPhase.DESIGN -> buildDesignPrompt(request)
            SpecPhase.IMPLEMENT -> buildImplementPrompt(request)
        }
    }

    private fun buildClarificationPrompt(request: SpecGenerationRequest): String {
        val budget = request.options.clarificationQuestionBudget
            .coerceIn(1, MAX_CLARIFICATION_QUESTION_BUDGET)

        return buildString {
            appendLine("你需要在生成 ${request.phase.displayName} 阶段文档前先做澄清。")
            appendLine("请提出最关键的澄清问题，用于确认需求或研发细节。")
            appendLine("问题数量上限：$budget。")
            appendLine()
            appendLine("## 当前阶段")
            appendLine(request.phase.displayName)
            appendLine()
            appendLine("## 用户输入")
            appendLine("```")
            appendLine(request.input.ifBlank { "(本阶段没有额外补充输入)" })
            appendLine("```")
            appendLine()
            appendLine("## 已有文档上下文")
            appendLine("```")
            appendLine(request.previousDocument?.content?.ifBlank { "(空)" } ?: "(无)")
            appendLine("```")
            val confirmedContext = request.options.confirmedContext
                ?.replace("\r\n", "\n")
                ?.replace('\r', '\n')
                ?.trim()
                .orEmpty()
            if (confirmedContext.isNotBlank()) {
                appendLine()
                appendLine("## 用户已确认的细节草稿")
                appendLine("```")
                appendLine(confirmedContext)
                appendLine("```")
            }
            appendLine()
            appendLine("请严格使用以下 Markdown 输出：")
            appendLine("## 需要确认的问题")
            appendLine("1. ...")
            appendLine("2. ...")
            appendLine()
            appendLine("## 已有信息摘要")
            appendLine("- ...")
            appendLine()
            appendLine("要求：优先关注范围边界、验收标准、技术约束、数据口径、依赖、风险。")
        }
    }

    /**
     * 构建 Specify 阶段 Prompt
     */
    private fun buildSpecifyPrompt(request: SpecGenerationRequest): String {
        return buildString {
            appendLine("请将以下自然语言需求转换为结构化的需求文档（requirements.md）：")
            appendLine()
            appendLine("```")
            appendLine(request.input)
            appendLine("```")
            appendConfirmedContext(request.options.confirmedContext)
            appendLine()
            appendLine("要求：")
            appendLine("1. 提取功能需求（Functional Requirements）")
            appendLine("2. 提取非功能需求（Non-Functional Requirements）")
            appendLine("3. 编写用户故事（User Stories）格式：As a... I want... So that...")
            appendLine("4. 定义验收标准（Acceptance Criteria）")
            appendLine("5. 识别约束条件（Constraints）")
            appendLine("6. 使用 Markdown 格式，结构清晰")

            if (request.options.includeExamples) {
                appendLine()
                appendLine("参考格式：")
                appendLine(getSpecifyTemplate())
            }
        }
    }

    /**
     * 构建 Design 阶段 Prompt
     */
    private fun buildDesignPrompt(request: SpecGenerationRequest): String {
        return buildString {
            appendLine("请基于以下需求文档设计技术方案（design.md）：")
            appendLine()
            appendLine("## 需求文档")
            appendLine("```")
            appendLine(request.previousDocument?.content ?: request.input)
            appendLine("```")
            appendConfirmedContext(request.options.confirmedContext)
            appendLine()
            appendLine("要求：")
            appendLine("0. 只输出最终 design.md 正文，不要输出思考过程、工具日志、路径信息或 JSON。")
            appendLine("1. 必须包含二级标题：## 架构设计、## 技术选型、## 数据模型。")
            appendLine("2. 在“架构设计”中说明核心模块、职责与关键数据流。")
            appendLine("3. 在“技术选型”中给出技术方案与取舍理由。")
            appendLine("4. 在“数据模型”中描述核心实体、字段关系与约束。")
            appendLine("5. 可补充 ## API 设计 与 ## 非功能设计（性能、安全、可扩展性）。")
            appendLine("6. 使用 Markdown；若包含 Mermaid，只作为章节内代码块，不要替代正文结构。")

            if (request.options.includeExamples) {
                appendLine()
                appendLine("参考格式：")
                appendLine(getDesignTemplate())
            }
        }
    }

    /**
     * 构建 Implement 阶段 Prompt
     */
    private fun buildImplementPrompt(request: SpecGenerationRequest): String {
        return buildString {
            appendLine("请基于以下设计文档拆解实现任务（tasks.md）：")
            appendLine()
            appendLine("## 设计文档")
            appendLine("```")
            appendLine(request.previousDocument?.content ?: request.input)
            appendLine("```")
            appendConfirmedContext(request.options.confirmedContext)
            appendLine()
            appendLine("要求：")
            appendLine("0. 只输出最终 tasks.md 正文，不要输出思考过程、工具日志、文件路径、JSON 字符串或转义字符。")
            appendLine("1. 将设计拆解为具体的开发任务")
            appendLine("2. 每个任务应该是可独立完成的")
            appendLine("3. 标记任务优先级（P0/P1/P2）")
            appendLine("4. 估算任务工时")
            appendLine("5. 定义任务依赖关系")
            appendLine("6. 包含测试计划")
            appendLine("7. 使用 Markdown Checkbox 格式")
            appendLine("8. 必须包含二级标题：## 任务列表 与 ## 实现步骤")

            if (request.options.includeExamples) {
                appendLine()
                appendLine("参考格式：")
                appendLine(getImplementTemplate())
            }
        }
    }

    private fun normalizeGeneratedDocument(
        phase: SpecPhase,
        rawContent: String,
    ): String {
        if (rawContent.isBlank()) return ""

        var normalized = rawContent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        normalized = extractLikelyPayloadFromCodeFence(normalized, phase)
        normalized = extractJsonContentFieldIfPresent(normalized)
        normalized = decodeEscapedTextIfNeeded(normalized)
        normalized = SpecMarkdownSanitizer.sanitize(normalized).ifBlank { normalized.trim() }
        normalized = trimToLikelyDocumentStart(phase, normalized)
        if (phase == SpecPhase.DESIGN) {
            normalized = ensureDesignStructure(normalized)
        }
        if (phase == SpecPhase.IMPLEMENT) {
            normalized = ensureImplementStructure(normalized)
        }
        return normalized.trim()
    }

    private fun extractLikelyPayloadFromCodeFence(
        content: String,
        phase: SpecPhase,
    ): String {
        val normalizedContent = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val trimmed = normalizedContent.trim()

        val fullyWrapped = FULL_CODE_FENCE_REGEX.matchEntire(trimmed)
        if (fullyWrapped != null) {
            return fullyWrapped.groupValues[1].trim()
        }

        val blocks = CODE_FENCE_REGEX.findAll(normalizedContent)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (blocks.isEmpty()) return content

        val outsideFenceText = CODE_FENCE_REGEX.replace(normalizedContent, "").trim()
        if (outsideFenceText.isNotBlank()) {
            // Keep the full markdown when code fences are only a part of the document.
            return content
        }

        val preferred = blocks.firstOrNull { block ->
            val decoded = decodeEscapedTextIfNeeded(block)
            decoded.lineSequence().any { line -> isLikelyPhaseDocumentLine(phase, line) }
        }
        if (preferred != null) {
            return preferred
        }

        return when (phase) {
            // For requirements/design, keeping full markdown is safer than picking one random code block.
            SpecPhase.SPECIFY, SpecPhase.DESIGN -> content
            SpecPhase.IMPLEMENT -> blocks.maxByOrNull { it.length } ?: content
        }
    }

    private fun extractJsonContentFieldIfPresent(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"content\"")) {
            return content
        }
        return runCatching {
            val root = jsonParser.parseToJsonElement(trimmed) as? JsonObject ?: return@runCatching content
            val value = (root["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isBlank()) content else value
        }.getOrElse { content }
    }

    private fun decodeEscapedTextIfNeeded(content: String): String {
        val trimmed = content.trim()
        val unwrapped = if (
            trimmed.length >= 2 &&
            trimmed.startsWith("\"") &&
            trimmed.endsWith("\"")
        ) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }

        val escapedNewlineCount = ESCAPED_NEWLINE_REGEX.findAll(unwrapped).count()
        val realNewlineCount = unwrapped.count { it == '\n' }
        if (escapedNewlineCount < 2 || escapedNewlineCount <= realNewlineCount) {
            return content
        }

        return unwrapped
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun trimToLikelyDocumentStart(phase: SpecPhase, content: String): String {
        val lines = content.lines()
        val startIndex = lines.indexOfFirst { line -> isLikelyPhaseDocumentLine(phase, line) }
        if (startIndex <= 0 || startIndex > MAX_LEADING_NOISE_LINES) {
            return content
        }
        return lines.drop(startIndex).joinToString("\n")
    }

    private fun isLikelyPhaseDocumentLine(phase: SpecPhase, line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (MARKDOWN_HEADING_REGEX.matches(trimmed)) return true
        if (CHECKBOX_ITEM_REGEX.matches(trimmed)) return true
        if (ORDERED_ITEM_REGEX.matches(trimmed)) return true

        val normalized = trimmed.lowercase()
        return when (phase) {
            SpecPhase.SPECIFY -> normalized.contains("功能需求") ||
                normalized.contains("非功能需求") ||
                normalized.contains("用户故事") ||
                normalized.contains("requirements")
            SpecPhase.DESIGN -> normalized.contains("架构设计") ||
                normalized.contains("architecture design") ||
                normalized.contains("技术选型") ||
                normalized.contains("technology stack") ||
                normalized.contains("数据模型") ||
                normalized.contains("data model") ||
                normalized.contains("api 设计") ||
                normalized.contains("api design")
            SpecPhase.IMPLEMENT -> normalized.contains("任务列表") ||
                normalized.contains("实现步骤") ||
                normalized.contains("task list") ||
                normalized.contains("implementation steps") ||
                normalized.startsWith("task ")
        }
    }

    private fun ensureDesignStructure(content: String): String {
        var normalized = content.trim()
        if (normalized.isBlank()) {
            return DESIGN_SKELETON
        }

        val hasArchitecture = containsAnyMarker(normalized, DESIGN_ARCHITECTURE_MARKERS)
        val hasTechStack = containsAnyMarker(normalized, DESIGN_TECH_STACK_MARKERS)
        val hasDataModel = containsAnyMarker(normalized, DESIGN_DATA_MODEL_MARKERS)

        if (!hasArchitecture) {
            normalized = buildString {
                appendLine("## 架构设计")
                appendLine()
                appendLine(normalized)
            }.trim()
        }

        if (!hasTechStack) {
            normalized = buildString {
                appendLine(normalized)
                appendLine()
                appendLine("## 技术选型")
                appendLine()
                appendLine("- 核心技术栈：待补充")
                appendLine("- 选型理由：待补充")
            }.trim()
        }

        if (!hasDataModel) {
            normalized = buildString {
                appendLine(normalized)
                appendLine()
                appendLine("## 数据模型")
                appendLine()
                appendLine("- 核心实体：待补充")
                appendLine("- 实体关系与约束：待补充")
            }.trim()
        }

        return normalized
    }

    private fun ensureImplementStructure(content: String): String {
        var normalized = content.trim()
        if (normalized.isBlank()) {
            return IMPLEMENT_SKELETON
        }

        val hasTaskList = containsAnyMarker(normalized, IMPLEMENT_TASK_LIST_MARKERS)
        val hasImplementationSteps = containsAnyMarker(normalized, IMPLEMENT_STEPS_MARKERS)

        if (!hasTaskList) {
            normalized = buildString {
                appendLine("## 任务列表")
                appendLine()
                appendLine(normalized)
            }.trim()
        }

        if (!hasImplementationSteps) {
            normalized = buildString {
                appendLine(normalized)
                appendLine()
                appendLine("## 实现步骤")
                appendLine()
                appendLine("1. 按任务列表优先级执行并在完成后勾选。")
                appendLine("2. 每完成一项后运行对应测试并记录结果。")
                appendLine("3. 回归验证后更新文档并同步状态。")
            }.trim()
        }

        return normalized
    }

    private fun containsAnyMarker(content: String, markers: List<String>): Boolean {
        return markers.any { marker -> content.contains(marker, ignoreCase = true) }
    }

    private fun StringBuilder.appendConfirmedContext(confirmedContext: String?) {
        val normalized = confirmedContext
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) {
            return
        }

        appendLine()
        appendLine("## 已确认的补充信息（优先级高于原输入）")
        appendLine("```")
        appendLine(normalized)
        appendLine("```")
    }

    private fun buildRequestMetadata(options: GenerationOptions): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            val requestId = options.requestId?.trim().orEmpty()
            if (requestId.isNotBlank()) {
                put("requestId", requestId)
            }
            put(METADATA_SPEC_WORKFLOW_KEY, "true")
            val workingDirectory = options.workingDirectory
                ?.trim()
                ?.ifBlank { null }
            if (workingDirectory != null) {
                put(LlmRequestContext.WORKING_DIRECTORY_METADATA_KEY, workingDirectory)
            }
            val operationMode = options.operationMode
                ?.trim()
                ?.ifBlank { null }
            if (operationMode != null) {
                put(LlmRequestContext.OPERATION_MODE_METADATA_KEY, operationMode)
            }
        }
    }

    private suspend fun requestLlmResponse(
        providerId: String?,
        llmRequest: LlmRequest,
        requestTag: String,
    ): LlmResponse {
        val primary = runCatching { llmRouter.generate(providerId = providerId, request = llmRequest) }
        val primaryResponse = primary.getOrNull()
        if (primaryResponse != null && primaryResponse.content.isNotBlank()) {
            return primaryResponse
        }
        val primaryError = primary.exceptionOrNull()

        primaryError?.let { error ->
            logger.warn("Primary LLM request failed for $requestTag, fallback to stream mode", error)
        } ?: logger.info("Primary LLM request returned blank content for $requestTag, fallback to stream mode")

        val streamAttempt = runCatching {
            val streamedChunks = StringBuilder()
            val streamResponse = llmRouter.stream(providerId = providerId, request = llmRequest) { chunk ->
                if (chunk.delta.isNotEmpty()) {
                    streamedChunks.append(chunk.delta)
                }
            }
            val mergedContent = streamedChunks.toString().ifBlank { streamResponse.content }
            streamResponse.copy(content = mergedContent)
        }
        val streamResponse = streamAttempt.getOrNull()
        if (streamResponse != null) {
            if (streamResponse.content.isBlank() && primaryError != null) {
                val primaryMessage = primaryError.message.orEmpty().trim()
                if (isMeaningfulErrorMessage(primaryMessage)) {
                    throw primaryError
                }
            }
            return streamResponse
        }

        val streamError = streamAttempt.exceptionOrNull()
        if (primaryError != null && streamError != null) {
            val primaryMessage = primaryError.message.orEmpty().trim()
            val streamMessage = streamError.message.orEmpty().trim()
            if (isMeaningfulErrorMessage(primaryMessage) && !isMeaningfulErrorMessage(streamMessage)) {
                throw primaryError
            }
            if (isMeaningfulErrorMessage(primaryMessage) && isMeaningfulErrorMessage(streamMessage) && primaryMessage != streamMessage) {
                val merged = RuntimeException(
                    "Primary request failed: $primaryMessage; stream fallback failed: $streamMessage",
                    streamError,
                )
                merged.addSuppressed(primaryError)
                throw merged
            }
        }

        throw streamError ?: primaryError ?: IllegalStateException("LLM request failed for $requestTag")
    }

    private fun isMeaningfulErrorMessage(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.lowercase() in PLACEHOLDER_ERROR_MESSAGES) {
            return false
        }
        if (normalized.length <= 3 && PLACEHOLDER_SYMBOLS_REGEX.matches(normalized)) {
            return false
        }
        return ERROR_TEXT_CONTENT_REGEX.containsMatchIn(normalized)
    }

    /**
     * 获取系统 Prompt
     */
    private fun getSystemPrompt(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> """
                你是一个专业的需求分析师。你的任务是将自然语言需求转换为结构化的需求文档。
                你需要：
                - 准确理解用户意图
                - 识别功能需求和非功能需求
                - 编写清晰的用户故事
                - 定义明确的验收标准
                - 使用专业的需求工程术语
            """.trimIndent()

            SpecPhase.DESIGN -> """
                你是一个资深的系统架构师。你的任务是基于需求文档设计技术方案。
                你需要：
                - 设计合理的系统架构
                - 选择合适的技术栈
                - 设计清晰的数据模型
                - 定义完整的 API 接口
                - 考虑性能、安全、可扩展性等非功能需求
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                你是一个经验丰富的技术负责人。你的任务是将设计方案拆解为可执行的任务。
                你需要：
                - 将设计拆解为具体的开发任务
                - 合理安排任务优先级
                - 估算任务工时
                - 识别任务依赖关系
                - 制定测试计划
            """.trimIndent()
        }
    }

    private fun getClarificationSystemPrompt(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> """
                你是资深需求分析师，负责在需求文档生成前做澄清。
                你的输出必须是高价值、可回答的问题，避免空泛提问。
            """.trimIndent()

            SpecPhase.DESIGN -> """
                你是系统架构师，负责在设计文档生成前确认研发细节。
                你的问题应聚焦架构约束、数据一致性、扩展性与边界条件。
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                你是技术负责人，负责在任务拆解前确认实施细节。
                你的问题应聚焦交付范围、优先级、依赖、验收与测试策略。
            """.trimIndent()
        }
    }

    internal fun parseClarificationDraft(
        phase: SpecPhase,
        rawContent: String,
        maxQuestions: Int = DEFAULT_CLARIFICATION_QUESTION_BUDGET,
    ): SpecClarificationDraft {
        return SpecClarificationDraft(
            phase = phase,
            questions = extractClarificationQuestions(rawContent, maxQuestions),
            rawContent = rawContent.trim(),
        )
    }

    internal fun extractClarificationQuestions(rawContent: String, maxQuestions: Int): List<String> {
        if (rawContent.isBlank()) {
            return emptyList()
        }
        val budget = maxQuestions.coerceIn(1, MAX_CLARIFICATION_QUESTION_BUDGET)
        val lines = rawContent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .toList()

        val sectionQuestions = extractQuestionsFromSection(lines)
        if (sectionQuestions.isNotEmpty()) {
            return sectionQuestions.take(budget)
        }

        return lines.asSequence()
            .mapNotNull { normalizeQuestionLine(it) }
            .distinct()
            .take(budget)
            .toList()
    }

    private fun extractQuestionsFromSection(lines: List<String>): List<String> {
        var inQuestionSection = false
        val questions = mutableListOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                continue
            }
            if (isQuestionSectionHeader(line)) {
                inQuestionSection = true
                continue
            }
            if (inQuestionSection && isSectionBoundary(line)) {
                inQuestionSection = false
                continue
            }
            if (!inQuestionSection) {
                continue
            }
            normalizeQuestionLine(line, allowQuestionLikeLine = true)?.let { questions += it }
        }

        return questions.distinct()
    }

    private fun normalizeQuestionLine(
        rawLine: String,
        allowQuestionLikeLine: Boolean = false,
    ): String? {
        val line = rawLine
            .trim()
            .removePrefix("> ")
            .trim()
        if (line.isBlank() || line.startsWith("```")) {
            return null
        }

        val numbered = NUMBERED_QUESTION_REGEX.find(line)?.groupValues?.get(1)
        if (!numbered.isNullOrBlank()) {
            return normalizeQuestionText(numbered)
        }

        val bulleted = BULLET_QUESTION_REGEX.find(line)?.groupValues?.get(1)
        if (!bulleted.isNullOrBlank()) {
            return normalizeQuestionText(bulleted)
        }

        val labeled = QUESTION_LABEL_REGEX.find(line)?.groupValues?.get(1)
        if (!labeled.isNullOrBlank()) {
            return normalizeQuestionText(labeled)
        }

        if (line.endsWith("?") || line.endsWith("？")) {
            return normalizeQuestionText(line)
        }

        if (allowQuestionLikeLine && QUESTION_LIKE_LINE_REGEX.containsMatchIn(line)) {
            return normalizeQuestionText(line.trimEnd('。', '；', ';', '：', ':'))
        }

        return null
    }

    private fun normalizeQuestionText(rawText: String): String? {
        val normalized = rawText
            .trim()
            .removePrefix("[ ]")
            .removePrefix("[x]")
            .removePrefix("[X]")
            .trim()
            .trim('`')
            .trim()
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun isQuestionSectionHeader(line: String): Boolean {
        val normalized = normalizeSectionHeader(line) ?: return false
        return QUESTION_SECTION_KEYWORDS.any { normalized.contains(it) }
    }

    private fun isSectionBoundary(line: String): Boolean {
        if (MARKDOWN_HEADING_REGEX.matches(line)) {
            return !isQuestionSectionHeader(line)
        }
        val normalized = normalizeSectionHeader(line) ?: return false
        return NON_QUESTION_SECTION_KEYWORDS.any { normalized.contains(it) }
    }

    private fun normalizeSectionHeader(rawLine: String): String? {
        val normalized = rawLine
            .trim()
            .removePrefix("> ")
            .trim()
            .let { MARKDOWN_HEADING_PREFIX_REGEX.replace(it, "") }
            .trim()
            .trimEnd(':', '：')
            .lowercase()
        if (normalized.isBlank()) {
            return null
        }
        if (normalized.length > MAX_SECTION_HEADER_LENGTH) {
            return null
        }
        if (normalized.startsWith("-") || normalized.startsWith("*") || normalized.startsWith("•")) {
            return null
        }
        return normalized
    }

    /**
     * 获取 Specify 阶段模板
     */
    private fun getSpecifyTemplate(): String {
        return """
            # 需求文档

            ## 功能需求

            ### FR-1: 用户登录
            - 用户可以使用邮箱和密码登录系统
            - 支持记住登录状态

            ## 非功能需求

            ### NFR-1: 性能
            - 登录响应时间 < 2 秒

            ## 用户故事

            ### US-1: 用户登录
            **As a** 注册用户
            **I want** 使用邮箱和密码登录
            **So that** 我可以访问个人账户

            **验收标准**:
            - [ ] 输入正确的邮箱和密码可以成功登录
            - [ ] 输入错误的密码显示错误提示
            - [ ] 可以选择记住登录状态
        """.trimIndent()
    }

    /**
     * 获取 Design 阶段模板
     */
    private fun getDesignTemplate(): String {
        return """
            # 设计文档

            ## 架构设计

            采用三层架构：
            - 表示层：Web UI
            - 业务层：业务逻辑处理
            - 数据层：数据持久化

            ## 技术选型

            - 后端：Kotlin + Spring Boot
            - 前端：React + TypeScript
            - 数据库：PostgreSQL

            ## 数据模型

            ```kotlin
            data class User(
                val id: String,
                val email: String,
                val passwordHash: String
            )
            ```

            ## API 设计

            ### POST /api/auth/login
            请求：
            ```json
            {
              "email": "user@example.com",
              "password": "password123"
            }
            ```
        """.trimIndent()
    }

    /**
     * 获取 Implement 阶段模板
     */
    private fun getImplementTemplate(): String {
        return """
            # 实现任务

            ## 任务列表

            ### Phase 1: 基础功能（P0）

            - [ ] Task 1: 创建 User 数据模型（2h）
            - [ ] Task 2: 实现用户注册 API（4h）
            - [ ] Task 3: 实现用户登录 API（4h）
            - [ ] Task 4: 实现 JWT Token 生成（2h）

            ### Phase 2: 前端集成（P1）

            - [ ] Task 5: 创建登录页面（4h）
            - [ ] Task 6: 集成登录 API（2h）

            ## 任务依赖

            - Task 2 依赖 Task 1
            - Task 3 依赖 Task 1, Task 4
            - Task 6 依赖 Task 3, Task 5

            ## 测试计划

            - [ ] 单元测试：User 模型测试
            - [ ] 集成测试：登录 API 测试
            - [ ] E2E 测试：完整登录流程测试
        """.trimIndent()
    }

    companion object {
        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private val CODE_FENCE_REGEX = Regex("```(?:[a-zA-Z0-9_-]+)?\\n([\\s\\S]*?)```")
        private val FULL_CODE_FENCE_REGEX = Regex("^```(?:[a-zA-Z0-9_-]+)?\\n([\\s\\S]*?)```$", setOf(RegexOption.DOT_MATCHES_ALL))
        private val ESCAPED_NEWLINE_REGEX = Regex("""\\n|\\r\\n""")
        private val MARKDOWN_HEADING_REGEX = Regex("""^\s{0,3}#{1,6}\s+\S+""")
        private val CHECKBOX_ITEM_REGEX = Regex("""^\s*-\s*\[[ xX]\]\s+\S+""")
        private val ORDERED_ITEM_REGEX = Regex("""^\s*\d+\.\s+\S+""")
        private const val MAX_LEADING_NOISE_LINES = 24
        private val DESIGN_ARCHITECTURE_MARKERS = listOf("## 架构设计", "架构设计", "系统架构", "Architecture Design", "Architecture")
        private val DESIGN_TECH_STACK_MARKERS = listOf("## 技术选型", "技术选型", "技术方案", "Technology Stack")
        private val DESIGN_DATA_MODEL_MARKERS = listOf("## 数据模型", "数据模型", "实体模型", "Data Model")
        private val DESIGN_SKELETON = """
            ## 架构设计
            
            - 核心模块：待补充
            - 数据流与边界：待补充
            
            ## 技术选型
            
            - 核心技术栈：待补充
            - 选型理由：待补充
            
            ## 数据模型
            
            - 核心实体：待补充
            - 实体关系与约束：待补充
        """.trimIndent()
        private val IMPLEMENT_TASK_LIST_MARKERS = listOf("## 任务列表", "任务列表", "Task List")
        private val IMPLEMENT_STEPS_MARKERS = listOf("## 实现步骤", "实现步骤", "Implementation Steps")
        private val IMPLEMENT_SKELETON = """
            ## 任务列表
            
            - [ ] Task 1: 明确本阶段交付范围与接口边界
            - [ ] Task 2: 完成核心功能实现并补充对应测试
            - [ ] Task 3: 执行回归验证并更新文档
            
            ## 实现步骤
            
            1. 按任务列表优先级执行并在完成后勾选。
            2. 每完成一项后运行对应测试并记录结果。
            3. 回归验证后更新文档并同步状态。
        """.trimIndent()
        private const val DEFAULT_CLARIFICATION_QUESTION_BUDGET = 5
        private const val MAX_CLARIFICATION_QUESTION_BUDGET = 8
        private const val MAX_SECTION_HEADER_LENGTH = 80
        private val NUMBERED_QUESTION_REGEX =
            Regex("""^(?:(?:[0-9０-９]+[.)）])|(?:[0-9０-９]+[、.:：-])|(?:[（(][0-9０-９]+[)）]))\s*(.+)$""")
        private val BULLET_QUESTION_REGEX = Regex("""^(?:[-*+]|[•·●▪◦])\s+(?:\[[ xX]\]\s*)?(.+)$""")
        private val QUESTION_LABEL_REGEX =
            Regex("""^(?:(?:q|question)\s*[0-9０-９]*[.:：-]|问题\s*[0-9０-９]*[.:：-])\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val MARKDOWN_HEADING_PREFIX_REGEX = Regex("""^#{1,6}\s*""")
        private val QUESTION_LIKE_LINE_REGEX = Regex(
            """^(?:是否|需不需要|需否|能否|可否|要不要|有没有|哪些|哪种|哪类|何时|何种|如何|why\b|what\b|which\b|who\b|when\b|where\b|how\b|is\b|are\b|can\b|could\b|would\b|should\b|do\b|does\b|did\b|will\b)""",
            RegexOption.IGNORE_CASE,
        )
        private val QUESTION_SECTION_KEYWORDS = setOf(
            "需要确认的问题",
            "需要澄清的问题",
            "澄清问题",
            "待确认问题",
            "待澄清问题",
            "clarification questions",
            "questions to clarify",
            "questions for clarification",
            "open questions",
        )
        private val NON_QUESTION_SECTION_KEYWORDS = setOf(
            "已有信息摘要",
            "信息摘要",
            "背景信息",
            "约束",
            "假设",
            "总结",
            "下一步",
            "existing context",
            "context summary",
            "summary",
            "constraints",
            "assumptions",
            "known information",
        )
        private val PLACEHOLDER_ERROR_MESSAGES = setOf("-", "--", "—", "...", "…", "null", "none", "unknown")
        private val PLACEHOLDER_SYMBOLS_REGEX = Regex("""^[\p{Punct}\s]+$""")
        private val ERROR_TEXT_CONTENT_REGEX = Regex("""[A-Za-z0-9\p{IsHan}]""")
        private const val METADATA_SPEC_WORKFLOW_KEY = "specWorkflow"
    }
}
