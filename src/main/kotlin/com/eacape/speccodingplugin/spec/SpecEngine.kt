package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookTriggerContext
import com.eacape.speccodingplugin.llm.LlmRouter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Spec 工作流引擎
 * 负责管理 Spec 工作流的状态机和阶段流转
 */
@Service(Service.Level.PROJECT)
class SpecEngine(private val project: Project) {
    private val logger = thisLogger()
    private val workflowIdGenerator = WorkflowIdGenerator()
    private val templateSwitchPreviewIdGenerator = WorkflowIdGenerator(prefix = "preview")
    private val templateSwitchIdGenerator = WorkflowIdGenerator(prefix = "switch")
    private val projectConfigDelegate: SpecProjectConfigService by lazy { SpecProjectConfigService(project) }
    private val artifactServiceDelegate: SpecArtifactService by lazy { SpecArtifactService(project) }
    private val gateRuleEngine: SpecGateRuleEngine by lazy {
        SpecGateRuleEngine(
            artifactService = artifactServiceDelegate,
            rules = listOf(
                RequiredArtifactRule(),
                DocumentValidationRule(),
            ),
        )
    }

    // Overridable by test constructor; lazy to avoid service lookups during construction
    private var _storageOverride: SpecStorage? = null
    private var _generationOverride: (suspend (SpecGenerationRequest) -> SpecGenerationResult)? = null
    private var _clarificationOverride: (suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>)? = null
    private var _stageGateEvaluatorOverride: SpecStageGateEvaluator? = null

    private val storageDelegate: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult by lazy {
        _generationOverride ?: SpecGenerator(LlmRouter.getInstance())::generate
    }
    private val clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft> by lazy {
        _clarificationOverride ?: SpecGenerator(LlmRouter.getInstance())::draftClarification
    }
    private val stageGateEvaluator: SpecStageGateEvaluator by lazy {
        _stageGateEvaluatorOverride ?: SpecStageGateEvaluator { request ->
            evaluateStageGate(request)
        }
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>,
    ) : this(project) {
        this._storageOverride = storage
        this._generationOverride = generationHandler
        this._clarificationOverride = clarificationHandler
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>,
        stageGateEvaluator: SpecStageGateEvaluator,
    ) : this(project, storage, generationHandler, clarificationHandler) {
        this._stageGateEvaluatorOverride = stageGateEvaluator
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
    ) : this(
        project = project,
        storage = storage,
        generationHandler = generationHandler,
        clarificationHandler = {
            Result.success(
                SpecClarificationDraft(
                    phase = it.phase,
                    questions = emptyList(),
                    rawContent = "",
                )
            )
        },
    )

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        stageGateEvaluator: SpecStageGateEvaluator,
    ) : this(
        project = project,
        storage = storage,
        generationHandler = generationHandler,
        clarificationHandler = {
            Result.success(
                SpecClarificationDraft(
                    phase = it.phase,
                    questions = emptyList(),
                    rawContent = "",
                )
            )
        },
        stageGateEvaluator = stageGateEvaluator,
    )

    // 当前活跃的工作流
    private val activeWorkflows = mutableMapOf<String, SpecWorkflow>()
    private val pendingTemplateSwitchPreviews = mutableMapOf<String, TemplateSwitchPreview>()

    /**
     * 创建新的工作流
     */
    fun createWorkflow(
        title: String,
        description: String,
        changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
        baselineWorkflowId: String? = null,
    ): Result<SpecWorkflow> {
        return runCatching {
            val projectConfig = projectConfigDelegate.load()
            val configPin = projectConfigDelegate.createConfigPin(projectConfig)
            val template = projectConfig.defaultTemplate
            val templatePolicy = projectConfig.policyFor(template)
            logger.debug(
                "Loaded spec project config: schemaVersion=${projectConfig.schemaVersion}, defaultTemplate=${projectConfig.defaultTemplate}",
            )
            val normalizedBaselineWorkflowId = baselineWorkflowId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (changeIntent == SpecChangeIntent.INCREMENTAL && normalizedBaselineWorkflowId != null) {
                val baselineId = normalizedBaselineWorkflowId
                val baselineExists = activeWorkflows.containsKey(baselineId) ||
                    storageDelegate.loadWorkflow(baselineId).isSuccess
                require(baselineExists) {
                    "Baseline workflow not found: $baselineId"
                }
            }
            val workflowId = generateWorkflowId()
            val createdAt = System.currentTimeMillis()
            val stageMetadata = initializeStageMetadata(
                templatePolicy = templatePolicy,
                timestampMillis = createdAt,
            )
            val workflow = SpecWorkflow(
                id = workflowId,
                currentPhase = initialPhaseForStage(stageMetadata.currentStage),
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = title,
                description = description,
                changeIntent = changeIntent,
                template = template,
                stageStates = stageMetadata.stageStates,
                currentStage = stageMetadata.currentStage,
                verifyEnabled = stageMetadata.verifyEnabled,
                baselineWorkflowId = if (changeIntent == SpecChangeIntent.INCREMENTAL) {
                    normalizedBaselineWorkflowId
                } else {
                    null
                },
                configPinHash = configPin.hash,
                createdAt = createdAt,
                updatedAt = createdAt,
            )

            storageDelegate.saveConfigPinSnapshot(workflowId, configPin).getOrThrow()
            storageDelegate.saveWorkflow(workflow).getOrThrow()
            val artifactWrites = artifactServiceDelegate.ensureMissingArtifacts(
                workflowId = workflowId,
                template = template,
                templatePolicy = templatePolicy,
            )
            activeWorkflows[workflowId] = workflow

            logger.info(
                "Created workflow: $workflowId, template=$template, " +
                    "scaffoldedArtifacts=${artifactWrites.count { it.created }}",
            )
            workflow
        }
    }

    /**
     * 加载工作流
     */
    fun loadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            // 先从内存缓存查找
            activeWorkflows[workflowId]?.let { return@runCatching it }

            // 从存储加载
            val workflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow
            workflow
        }
    }

    /**
     * 强制从存储重载工作流，覆盖内存缓存。
     */
    fun reloadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow
            workflow
        }
    }

    /**
     * 列出所有工作流
     */
    fun listWorkflows(): List<String> {
        return storageDelegate.listWorkflows()
    }

    fun listWorkflowMetadata(): List<WorkflowMeta> {
        return storageDelegate.listWorkflowMetadata()
    }

    fun openWorkflow(workflowId: String): Result<WorkflowSnapshot> {
        return runCatching {
            val snapshot = storageDelegate.openWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = snapshot.workflow
            snapshot
        }
    }

    fun previewTemplateSwitch(
        workflowId: String,
        toTemplate: WorkflowTemplate,
    ): Result<TemplateSwitchPreview> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow

            val projectConfig = projectConfigDelegate.load()
            val currentStagePlan = resolveStagePlan(workflow, projectConfig)
            val targetPolicy = projectConfig.policyFor(toTemplate)
            val targetStagePlan = targetPolicy.defaultStagePlan()
            val preview = TemplateSwitchPreview(
                previewId = templateSwitchPreviewIdGenerator.nextId(),
                workflowId = workflowId,
                fromTemplate = workflow.template,
                toTemplate = toTemplate,
                currentStage = workflow.currentStage,
                resultingStage = targetStagePlan.resolveCurrentStage(workflow.currentStage),
                addedActiveStages = targetStagePlan.activeStages.filterNot { currentStagePlan.isActive(it) },
                deactivatedStages = currentStagePlan.activeStages.filterNot { targetStagePlan.isActive(it) },
                gateAddedStages = targetStagePlan.gateArtifactStages.filterNot { currentStagePlan.participatesInGate(it) },
                gateRemovedStages = currentStagePlan.gateArtifactStages.filterNot { targetStagePlan.participatesInGate(it) },
                artifactImpacts = artifactServiceDelegate.previewRequiredArtifacts(
                    workflowId = workflowId,
                    template = toTemplate,
                    templatePolicy = targetPolicy,
                ),
            )
            pendingTemplateSwitchPreviews[preview.previewId] = preview
            logger.info(
                "Prepared template switch preview ${preview.previewId} for workflow=$workflowId " +
                    "${workflow.template} -> $toTemplate",
            )
            preview
        }
    }

    fun applyTemplateSwitch(
        workflowId: String,
        previewId: String,
    ): Result<TemplateSwitchApplyResult> {
        return runCatching {
            val preview = pendingTemplateSwitchPreviews[previewId]
                ?: throw MissingTemplateSwitchPreviewError(previewId)
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow

            if (
                preview.workflowId != workflowId ||
                preview.fromTemplate != workflow.template ||
                preview.currentStage != workflow.currentStage
            ) {
                pendingTemplateSwitchPreviews.remove(previewId)
                throw StaleTemplateSwitchPreviewError(previewId, workflowId)
            }

            val projectConfig = projectConfigDelegate.load()
            val targetPolicy = projectConfig.policyFor(preview.toTemplate)
            val currentImpacts = artifactServiceDelegate.previewRequiredArtifacts(
                workflowId = workflowId,
                template = preview.toTemplate,
                templatePolicy = targetPolicy,
            )
            val blockedImpact = currentImpacts.firstOrNull { impact ->
                impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH
            }
            if (blockedImpact != null) {
                throw StageTransitionPolicyError(
                    "Template switch blocked by missing non-scaffoldable artifact ${blockedImpact.fileName} for ${blockedImpact.stageId}",
                )
            }

            val generatedArtifacts = artifactServiceDelegate.ensureMissingArtifacts(
                workflowId = workflowId,
                template = preview.toTemplate,
                templatePolicy = targetPolicy,
            )
                .filter { write -> write.created }
                .map { write -> write.path.fileName.toString() }

            val targetStagePlan = targetPolicy.defaultStagePlan()
            val updatedAt = System.currentTimeMillis()
            val targetStage = targetStagePlan.resolveCurrentStage(workflow.currentStage)
            val stageMetadata = applyTemplateSwitchMetadata(
                workflow = workflow,
                targetStagePlan = targetStagePlan,
                targetStage = targetStage,
                timestampMillis = updatedAt,
            )
            val switchId = templateSwitchIdGenerator.nextId()
            val updatedWorkflow = workflow.copy(
                currentPhase = initialPhaseForStage(stageMetadata.currentStage),
                template = preview.toTemplate,
                stageStates = stageMetadata.stageStates,
                currentStage = stageMetadata.currentStage,
                verifyEnabled = stageMetadata.verifyEnabled,
                updatedAt = updatedAt,
            )

            val saveResult = storageDelegate.saveWorkflowTransition(
                workflow = updatedWorkflow,
                eventType = SpecAuditEventType.TEMPLATE_SWITCHED,
                details = buildTemplateSwitchAuditDetails(
                    preview = preview,
                    switchId = switchId,
                    generatedArtifacts = generatedArtifacts,
                ),
            ).getOrThrow()

            clearTemplateSwitchPreviews(workflowId)
            val reloadedWorkflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = reloadedWorkflow
            logger.info(
                "Workflow $workflowId applied template switch $switchId: ${preview.fromTemplate} -> ${preview.toTemplate}",
            )
            TemplateSwitchApplyResult(
                switchId = switchId,
                previewId = preview.previewId,
                workflow = reloadedWorkflow,
                generatedArtifacts = generatedArtifacts,
                beforeSnapshotId = saveResult.beforeSnapshotId,
                afterSnapshotId = saveResult.afterSnapshotId,
            )
        }
    }

    fun rollbackTemplateSwitch(
        workflowId: String,
        switchId: String,
    ): Result<TemplateSwitchRollbackResult> {
        return runCatching {
            val currentWorkflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val switchEvent = storageDelegate.listAuditEvents(workflowId).getOrThrow()
                .asReversed()
                .firstOrNull { event ->
                    event.eventType == SpecAuditEventType.TEMPLATE_SWITCHED &&
                        event.details["switchId"] == switchId
                }
                ?: throw MissingTemplateSwitchHistoryError(workflowId, switchId)

            val rollbackSnapshotId = switchEvent.details["beforeSnapshotId"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Template switch $switchId does not contain a rollback snapshot id")
            val snapshotWorkflow = storageDelegate.loadWorkflowSnapshot(workflowId, rollbackSnapshotId).getOrThrow()
            val restoredWorkflow = snapshotWorkflow.copy(
                id = workflowId,
                documents = currentWorkflow.documents,
                updatedAt = System.currentTimeMillis(),
            )

            val saveResult = storageDelegate.saveWorkflowTransition(
                workflow = restoredWorkflow,
                eventType = SpecAuditEventType.TEMPLATE_SWITCH_ROLLED_BACK,
                details = buildTemplateSwitchRollbackAuditDetails(
                    currentWorkflow = currentWorkflow,
                    restoredWorkflow = restoredWorkflow,
                    switchId = switchId,
                    rollbackSnapshotId = rollbackSnapshotId,
                    switchEvent = switchEvent,
                ),
            ).getOrThrow()

            clearTemplateSwitchPreviews(workflowId)
            val reloadedWorkflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = reloadedWorkflow
            logger.info(
                "Workflow $workflowId rolled back template switch $switchId to ${reloadedWorkflow.template}",
            )
            TemplateSwitchRollbackResult(
                switchId = switchId,
                workflow = reloadedWorkflow,
                restoredFromSnapshotId = rollbackSnapshotId,
                beforeSnapshotId = saveResult.beforeSnapshotId,
                afterSnapshotId = saveResult.afterSnapshotId,
            )
        }
    }

    fun listDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
    ): List<SpecDocumentHistoryEntry> {
        return storageDelegate.listDocumentHistory(workflowId, phase)
    }

    fun loadDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<SpecDocument> {
        return storageDelegate.loadDocumentSnapshot(workflowId, phase, snapshotId)
    }

    fun deleteDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<Unit> {
        return storageDelegate.deleteDocumentSnapshot(workflowId, phase, snapshotId)
    }

    fun pruneDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
        keepLatest: Int,
    ): Result<Int> {
        return storageDelegate.pruneDocumentHistory(workflowId, phase, keepLatest)
    }

    fun listWorkflowSnapshots(workflowId: String): List<SpecWorkflowSnapshotEntry> {
        return storageDelegate.listWorkflowSnapshots(workflowId)
    }

    fun loadWorkflowSnapshot(
        workflowId: String,
        snapshotId: String,
    ): Result<SpecWorkflow> {
        return storageDelegate.loadWorkflowSnapshot(workflowId, snapshotId)
    }

    fun pinDeltaBaseline(
        workflowId: String,
        snapshotId: String,
        label: String? = null,
    ): Result<SpecDeltaBaselineRef> {
        return storageDelegate.pinDeltaBaseline(workflowId, snapshotId, label)
    }

    fun listDeltaBaselines(workflowId: String): List<SpecDeltaBaselineRef> {
        return storageDelegate.listDeltaBaselines(workflowId)
    }

    fun loadDeltaBaselineWorkflow(
        workflowId: String,
        baselineId: String,
    ): Result<SpecWorkflow> {
        return storageDelegate.loadDeltaBaselineWorkflow(workflowId, baselineId)
    }

    /**
     * 生成当前阶段的文档
     */
    suspend fun generateCurrentPhase(
        workflowId: String,
        input: String,
    ): Flow<SpecGenerationProgress> {
        return generateCurrentPhase(
            workflowId = workflowId,
            input = input,
            options = GenerationOptions(),
        )
    }

    suspend fun draftCurrentPhaseClarification(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions(),
    ): Result<SpecClarificationDraft> {
        val workflow = activeWorkflows[workflowId]
            ?: storageDelegate.loadWorkflow(workflowId).getOrElse { return Result.failure(it) }
        activeWorkflows[workflowId] = workflow

        val previousPhase = workflow.currentPhase.previous()
        val previousDocument = previousPhase?.let { workflow.getDocument(it) }
        val effectiveOptions = enrichGenerationOptions(workflow, options)
        val request = SpecGenerationRequest(
            phase = workflow.currentPhase,
            input = input,
            previousDocument = previousDocument,
            options = effectiveOptions,
        )
        return clarificationHandler(request)
    }

    suspend fun generateCurrentPhase(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions()
    ): Flow<SpecGenerationProgress> = flow {
        val workflow = activeWorkflows[workflowId]
            ?: throw IllegalStateException("Workflow not found: $workflowId")

        emit(SpecGenerationProgress.Started(workflow.currentPhase))

        try {
            // 获取前一阶段的文档（如果有）
            val previousPhase = workflow.currentPhase.previous()
            val previousDocument = previousPhase?.let { workflow.getDocument(it) }
            val effectiveOptions = enrichGenerationOptions(workflow, options)

            // 构建生成请求
            val request = SpecGenerationRequest(
                phase = workflow.currentPhase,
                input = input,
                previousDocument = previousDocument,
                options = effectiveOptions
            )

            emit(SpecGenerationProgress.Generating(workflow.currentPhase, 0.3))

            // 生成文档
            val result = generationHandler(request)

            when (result) {
                is SpecGenerationResult.Success -> {
                    emit(SpecGenerationProgress.Generating(workflow.currentPhase, 0.7))

                    // 保存文档
                    storageDelegate.saveDocument(workflowId, result.document).getOrThrow()

                    // 更新工作流
                    val updatedWorkflow = workflow.copy(
                        documents = workflow.documents + (workflow.currentPhase to result.document),
                        clarificationRetryState = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    activeWorkflows[workflowId] = updatedWorkflow
                    storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

                    emit(SpecGenerationProgress.Completed(result.document))
                }

                is SpecGenerationResult.ValidationFailed -> {
                    // 保存文档（即使验证失败）
                    storageDelegate.saveDocument(workflowId, result.document).getOrThrow()

                    val updatedWorkflow = workflow.copy(
                        documents = workflow.documents + (workflow.currentPhase to result.document),
                        updatedAt = System.currentTimeMillis()
                    )
                    activeWorkflows[workflowId] = updatedWorkflow
                    storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

                    emit(SpecGenerationProgress.ValidationFailed(result.document, result.validation))
                }

                is SpecGenerationResult.Failure -> {
                    emit(SpecGenerationProgress.Failed(result.error, result.details))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate ${workflow.currentPhase} document", e)
            emit(SpecGenerationProgress.Failed(e.message ?: "Unknown error", e.stackTraceToString()))
        }
    }

    /**
     * 更新指定阶段文档内容（用于 Chat Spec 卡片内编辑）。
     * 可选 expectedRevision 用于并发冲突检测。
     */
    fun updateDocumentContent(
        workflowId: String,
        phase: SpecPhase,
        content: String,
        expectedRevision: Long? = null,
    ): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val existingDocument = workflow.getDocument(phase)
                ?: SpecDocument(
                    id = "$workflowId-${phase.name.lowercase()}",
                    phase = phase,
                    content = "",
                    metadata = SpecMetadata(
                        title = "${phase.displayName} Document",
                        description = "Manually edited ${phase.displayName} document",
                    ),
                    validationResult = null,
                )

            expectedRevision?.let { expected ->
                val actual = existingDocument.metadata.updatedAt
                if (actual != expected) {
                    throw DocumentRevisionConflictException(
                        workflowId = workflowId,
                        phase = phase,
                        expectedRevision = expected,
                        actualRevision = actual,
                    )
                }
            }

            val normalizedContent = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            require(normalizedContent.isNotBlank()) { "Document content cannot be blank" }

            val now = System.currentTimeMillis()
            val draftDocument = existingDocument.copy(
                content = normalizedContent,
                metadata = existingDocument.metadata.copy(updatedAt = now),
            )
            val validation = SpecValidator.validate(draftDocument)
            val updatedDocument = draftDocument.copy(validationResult = validation)

            storageDelegate.saveDocument(workflowId, updatedDocument).getOrThrow()

            val updatedWorkflow = workflow.copy(
                documents = workflow.documents + (phase to updatedDocument),
                updatedAt = now,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId updated ${phase.displayName} document")
            updatedWorkflow
        }
    }

    fun updateWorkflowMetadata(
        workflowId: String,
        title: String,
        description: String,
    ): Result<SpecWorkflow> {
        return runCatching {
            val normalizedTitle = title
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .trim()
            require(normalizedTitle.isNotBlank()) { "Workflow title cannot be blank" }

            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val now = System.currentTimeMillis()
            val updatedWorkflow = workflow.copy(
                title = normalizedTitle,
                description = description
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim(),
                updatedAt = now,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId updated metadata")
            updatedWorkflow
        }
    }

    fun saveClarificationRetryState(
        workflowId: String,
        state: ClarificationRetryState?,
    ): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val normalizedState = state?.normalize()
            if (workflow.clarificationRetryState == normalizedState) {
                activeWorkflows[workflowId] = workflow
                return@runCatching workflow
            }
            val updatedWorkflow = workflow.copy(
                clarificationRetryState = normalizedState,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()
            updatedWorkflow
        }
    }

    private fun ClarificationRetryState.normalize(): ClarificationRetryState? {
        val normalizedInput = input
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val normalizedContext = confirmedContext
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val normalizedQuestions = questionsMarkdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val normalizedStructuredQuestions = structuredQuestions
            .map {
                it.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedLastError = lastError
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (normalizedInput.isBlank() &&
            normalizedContext.isBlank() &&
            normalizedQuestions.isBlank() &&
            normalizedStructuredQuestions.isEmpty()
        ) {
            return null
        }
        return copy(
            input = normalizedInput,
            confirmedContext = normalizedContext,
            questionsMarkdown = normalizedQuestions,
            structuredQuestions = normalizedStructuredQuestions,
            clarificationRound = clarificationRound.coerceAtLeast(1),
            lastError = normalizedLastError,
        )
    }

    fun advanceWorkflow(
        workflowId: String,
        confirmWarnings: ((GateResult) -> Boolean)? = null,
    ): Result<StageTransitionResult> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val projectConfig = projectConfigDelegate.load()
            val stagePlan = resolveStagePlan(workflow, projectConfig)
            val targetStage = stagePlan.nextActiveStage(workflow.currentStage)
                ?: throw IllegalStateException("Already at the last active stage")
            performStageTransition(
                workflow = workflow,
                stagePlan = stagePlan,
                gatePolicy = projectConfig.gate,
                transitionType = StageTransitionType.ADVANCE,
                targetStage = targetStage,
                evaluatedStages = listOf(workflow.currentStage),
                confirmWarnings = confirmWarnings,
            )
        }
    }

    fun jumpToStage(
        workflowId: String,
        targetStage: StageId,
        confirmWarnings: ((GateResult) -> Boolean)? = null,
    ): Result<StageTransitionResult> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val projectConfig = projectConfigDelegate.load()
            val stagePlan = resolveStagePlan(workflow, projectConfig)

            if (!projectConfig.gate.allowJump) {
                throw StageTransitionPolicyError("Jump transition is disabled by gate policy")
            }
            if (!stagePlan.isActive(targetStage)) {
                throw InactiveWorkflowStageError(targetStage)
            }
            val currentIndex = stagePlan.activeStageIndex(workflow.currentStage)
            val targetIndex = stagePlan.activeStageIndex(targetStage)
            if (targetIndex <= currentIndex) {
                throw InvalidStageTransitionError(workflow.currentStage, targetStage)
            }
            val evaluatedStages = if (projectConfig.gate.jumpRequiresMinimalGate) {
                stagePlan.activeStagesBetween(workflow.currentStage, targetStage).dropLast(1)
            } else {
                listOf(workflow.currentStage)
            }
            performStageTransition(
                workflow = workflow,
                stagePlan = stagePlan,
                gatePolicy = projectConfig.gate,
                transitionType = StageTransitionType.JUMP,
                targetStage = targetStage,
                evaluatedStages = evaluatedStages,
                confirmWarnings = confirmWarnings,
            )
        }
    }

    fun rollbackToStage(
        workflowId: String,
        targetStage: StageId,
    ): Result<WorkflowMeta> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val projectConfig = projectConfigDelegate.load()
            val stagePlan = resolveStagePlan(workflow, projectConfig)

            if (!projectConfig.gate.allowRollback) {
                throw StageTransitionPolicyError("Rollback transition is disabled by gate policy")
            }
            if (!stagePlan.isActive(targetStage)) {
                throw InactiveWorkflowStageError(targetStage)
            }
            val currentIndex = stagePlan.activeStageIndex(workflow.currentStage)
            val targetIndex = stagePlan.activeStageIndex(targetStage)
            if (targetIndex > currentIndex) {
                throw InvalidStageTransitionError(workflow.currentStage, targetStage)
            }

            val targetState = workflow.stageStates[targetStage]
            if (targetStage != workflow.currentStage && targetState?.status != StageProgress.DONE) {
                throw StageTransitionPolicyError(
                    "Rollback target $targetStage must be a completed or current stage",
                )
            }

            if (targetStage == workflow.currentStage) {
                activeWorkflows[workflowId] = workflow
                return@runCatching workflow.toWorkflowMeta()
            }

            performStageTransition(
                workflow = workflow,
                stagePlan = stagePlan,
                gatePolicy = projectConfig.gate,
                transitionType = StageTransitionType.ROLLBACK,
                targetStage = targetStage,
                evaluatedStages = emptyList(),
                confirmWarnings = null,
            ).workflow.toWorkflowMeta()
        }
    }

    /**
     * 进入下一阶段
     */
    fun proceedToNextPhase(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val currentDocument = workflow.getCurrentDocument()
                ?: throw IllegalStateException(
                    "Cannot proceed to next phase. Current phase document is missing. " +
                        "Run /spec generate <input> first.",
                )
            val currentValidation = SpecValidator.validate(currentDocument)
            if (!currentValidation.valid) {
                val detail = currentValidation.errors
                    .take(3)
                    .joinToString("；")
                    .ifBlank { "unknown validation error" }
                throw IllegalStateException(
                    "Cannot proceed to next phase. Current phase validation failed: $detail",
                )
            }
            advanceWorkflow(workflowId).getOrThrow().workflow
        }
    }

    /**
     * 返回上一阶段
     */
    fun goBackToPreviousPhase(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val projectConfig = projectConfigDelegate.load()
            val stagePlan = resolveStagePlan(workflow, projectConfig)
            val targetStage = stagePlan.previousActiveStage(workflow.currentStage)
                ?: throw IllegalStateException("Already at the first active stage")
            rollbackToStage(workflowId, targetStage).getOrThrow()
            activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
        }
    }

    /**
     * 完成工作流
     */
    fun completeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.currentPhase != SpecPhase.IMPLEMENT) {
                throw IllegalStateException("Cannot complete workflow. Must be at Implement phase.")
            }

            val implementDoc = workflow.getDocument(SpecPhase.IMPLEMENT)
                ?: throw IllegalStateException("Implement phase document not found")

            if (implementDoc.validationResult?.valid != true) {
                throw IllegalStateException("Cannot complete workflow. Implement phase validation failed.")
            }

            val completedAt = System.currentTimeMillis()
            val completedStageStates = markCurrentStageCompleted(
                workflow = workflow,
                timestampMillis = completedAt,
            )
            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.COMPLETED,
                stageStates = completedStageStates,
                clarificationRetryState = null,
                updatedAt = completedAt
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId completed")
            updatedWorkflow
        }
    }

    /**
     * 暂停工作流
     */
    fun pauseWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId paused")
            updatedWorkflow
        }
    }

    /**
     * 恢复工作流
     */
    fun resumeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.status != WorkflowStatus.PAUSED) {
                throw IllegalStateException("Workflow is not paused")
            }

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId resumed")
            updatedWorkflow
        }
    }

    /**
     * 删除工作流
     */
    fun deleteWorkflow(workflowId: String): Result<Unit> {
        return runCatching {
            activeWorkflows.remove(workflowId)
            storageDelegate.deleteWorkflow(workflowId).getOrThrow()
            logger.info("Workflow $workflowId deleted")
        }
    }

    fun archiveWorkflow(workflowId: String): Result<SpecArchiveResult> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            if (workflow.status != WorkflowStatus.COMPLETED) {
                throw IllegalStateException("Only completed workflow can be archived")
            }

            val result = storageDelegate.archiveWorkflow(workflow).getOrThrow()
            activeWorkflows.remove(workflowId)
            logger.info("Workflow $workflowId archived to ${result.archivePath}")
            result
        }
    }

    private fun resolveStagePlan(
        workflow: SpecWorkflow,
        projectConfig: SpecProjectConfig,
    ): WorkflowStagePlan {
        val templatePolicy = projectConfig.policyFor(workflow.template)
        return if (workflow.stageStates.isNotEmpty()) {
            templatePolicy.definition.buildStagePlan(
                StageActivationOptions.fromStageStates(workflow.stageStates),
            )
        } else {
            templatePolicy.defaultStagePlan()
        }
    }

    private fun performStageTransition(
        workflow: SpecWorkflow,
        stagePlan: WorkflowStagePlan,
        gatePolicy: SpecGatePolicy,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        confirmWarnings: ((GateResult) -> Boolean)?,
    ): StageTransitionResult {
        if (!stagePlan.isActive(workflow.currentStage)) {
            throw InactiveWorkflowStageError(workflow.currentStage)
        }
        if (!stagePlan.isActive(targetStage)) {
            throw InactiveWorkflowStageError(targetStage)
        }

        val gateResult = if (transitionType == StageTransitionType.ROLLBACK) {
            GateResult.fromViolations(emptyList())
        } else {
            stageGateEvaluator.evaluate(
                StageTransitionRequest(
                    workflowId = workflow.id,
                    transitionType = transitionType,
                    fromStage = workflow.currentStage,
                    targetStage = targetStage,
                    evaluatedStages = evaluatedStages.distinct(),
                    stagePlan = stagePlan,
                    workflow = workflow,
                ),
            )
        }
        val warningConfirmed = resolveWarningDecision(
            gatePolicy = gatePolicy,
            gateResult = gateResult,
            fromStage = workflow.currentStage,
            targetStage = targetStage,
            confirmWarnings = confirmWarnings,
        )

        val updatedAt = System.currentTimeMillis()
        val stageMetadata = applyStageTransitionMetadata(
            workflow = workflow,
            stagePlan = stagePlan,
            targetStage = targetStage,
            timestampMillis = updatedAt,
        )
        val updatedWorkflow = workflow.copy(
            currentPhase = initialPhaseForStage(stageMetadata.currentStage),
            stageStates = stageMetadata.stageStates,
            currentStage = stageMetadata.currentStage,
            verifyEnabled = stageMetadata.verifyEnabled,
            clarificationRetryState = null,
            updatedAt = updatedAt,
        )

        val saveResult = storageDelegate.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = transitionAuditEventType(transitionType),
            details = buildStageTransitionAuditDetails(
                workflow = workflow,
                transitionType = transitionType,
                targetStage = targetStage,
                gateResult = gateResult,
                evaluatedStages = evaluatedStages,
                warningConfirmed = warningConfirmed,
            ),
        ).getOrThrow()
        activeWorkflows[workflow.id] = updatedWorkflow

        emitSpecStageChangedHook(
            workflowId = workflow.id,
            previousPhase = workflow.currentPhase,
            currentPhase = updatedWorkflow.currentPhase,
            previousStage = workflow.currentStage,
            currentStage = updatedWorkflow.currentStage,
        )
        logger.info(
            "Workflow ${workflow.id} transitioned ${transitionType.name.lowercase()} " +
                "${workflow.currentStage} -> ${updatedWorkflow.currentStage} (gate=${gateResult.status})",
        )
        return StageTransitionResult(
            workflow = updatedWorkflow,
            transitionType = transitionType,
            fromStage = workflow.currentStage,
            targetStage = updatedWorkflow.currentStage,
            gateResult = gateResult,
            warningConfirmed = warningConfirmed,
            beforeSnapshotId = saveResult.beforeSnapshotId,
            afterSnapshotId = saveResult.afterSnapshotId,
        )
    }

    private fun applyStageTransitionMetadata(
        workflow: SpecWorkflow,
        stagePlan: WorkflowStagePlan,
        targetStage: StageId,
        timestampMillis: Long,
    ): StageMetadataState {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val completedStages = stagePlan.activeStagesBefore(targetStage).toSet()
        val updatedStates = linkedMapOf<StageId, StageState>()

        StageId.entries.forEach { stageId ->
            val previous = workflow.stageStates[stageId] ?: StageState(
                active = stagePlan.isActive(stageId),
                status = StageProgress.NOT_STARTED,
            )
            updatedStates[stageId] = when {
                !stagePlan.isActive(stageId) -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )

                stageId == targetStage -> previous.copy(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = timestamp,
                    completedAt = null,
                )

                completedStages.contains(stageId) -> previous.copy(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = previous.enteredAt ?: timestamp,
                    completedAt = previous.completedAt ?: timestamp,
                )

                else -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }

        return StageMetadataState(
            currentStage = targetStage,
            verifyEnabled = stagePlan.isActive(StageId.VERIFY),
            stageStates = updatedStates,
        )
    }

    private fun applyTemplateSwitchMetadata(
        workflow: SpecWorkflow,
        targetStagePlan: WorkflowStagePlan,
        targetStage: StageId,
        timestampMillis: Long,
    ): StageMetadataState {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updatedStates = linkedMapOf<StageId, StageState>()

        StageId.entries.forEach { stageId ->
            val previous = workflow.stageStates[stageId] ?: StageState(
                active = false,
                status = StageProgress.NOT_STARTED,
            )
            updatedStates[stageId] = when {
                !targetStagePlan.isActive(stageId) -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )

                stageId == targetStage -> previous.copy(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = previous.enteredAt ?: timestamp,
                    completedAt = null,
                )

                previous.status == StageProgress.DONE -> previous.copy(active = true)

                else -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }

        return StageMetadataState(
            currentStage = targetStage,
            verifyEnabled = targetStagePlan.isActive(StageId.VERIFY),
            stageStates = updatedStates,
        )
    }

    private fun resolveWarningDecision(
        gatePolicy: SpecGatePolicy,
        gateResult: GateResult,
        fromStage: StageId,
        targetStage: StageId,
        confirmWarnings: ((GateResult) -> Boolean)?,
    ): Boolean {
        return when (gateResult.status) {
            GateStatus.ERROR -> throw StageTransitionBlockedByGateError(fromStage, targetStage, gateResult)
            GateStatus.WARNING -> {
                if (!gatePolicy.allowWarningAdvance) {
                    throw StageTransitionBlockedByGateError(fromStage, targetStage, gateResult)
                }
                if (!gatePolicy.requireWarningConfirmation) {
                    true
                } else {
                    val confirmed = confirmWarnings?.invoke(gateResult) == true
                    if (!confirmed) {
                        throw StageWarningConfirmationRequiredError(fromStage, targetStage)
                    }
                    true
                }
            }

            GateStatus.PASS -> false
        }
    }

    private fun buildStageTransitionAuditDetails(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        gateResult: GateResult,
        evaluatedStages: List<StageId>,
        warningConfirmed: Boolean,
    ): Map<String, String> {
        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "warningConfirmed" to warningConfirmed.toString(),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        return details
    }

    private fun buildTemplateSwitchAuditDetails(
        preview: TemplateSwitchPreview,
        switchId: String,
        generatedArtifacts: List<String>,
    ): Map<String, String> {
        val details = linkedMapOf(
            "switchId" to switchId,
            "previewId" to preview.previewId,
            "fromTemplate" to preview.fromTemplate.name,
            "toTemplate" to preview.toTemplate.name,
            "fromStage" to preview.currentStage.name,
            "toStage" to preview.resultingStage.name,
            "currentStageChanged" to preview.currentStageChanged.toString(),
        )
        if (preview.addedActiveStages.isNotEmpty()) {
            details["addedActiveStages"] = preview.addedActiveStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.deactivatedStages.isNotEmpty()) {
            details["deactivatedStages"] = preview.deactivatedStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.gateAddedStages.isNotEmpty()) {
            details["gateAddedStages"] = preview.gateAddedStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.gateRemovedStages.isNotEmpty()) {
            details["gateRemovedStages"] = preview.gateRemovedStages.joinToString(",") { stage -> stage.name }
        }
        if (generatedArtifacts.isNotEmpty()) {
            details["generatedArtifacts"] = generatedArtifacts.joinToString(",")
        }
        return details
    }

    private fun buildTemplateSwitchRollbackAuditDetails(
        currentWorkflow: SpecWorkflow,
        restoredWorkflow: SpecWorkflow,
        switchId: String,
        rollbackSnapshotId: String,
        switchEvent: SpecAuditEvent,
    ): Map<String, String> {
        val details = linkedMapOf(
            "switchId" to switchId,
            "restoredFromSnapshotId" to rollbackSnapshotId,
            "fromTemplate" to currentWorkflow.template.name,
            "toTemplate" to restoredWorkflow.template.name,
            "fromStage" to currentWorkflow.currentStage.name,
            "toStage" to restoredWorkflow.currentStage.name,
            "switchEventId" to switchEvent.eventId,
        )
        switchEvent.details["previewId"]?.let { previewId ->
            details["previewId"] = previewId
        }
        return details
    }

    private fun clearTemplateSwitchPreviews(workflowId: String) {
        pendingTemplateSwitchPreviews.entries.removeIf { (_, preview) ->
            preview.workflowId == workflowId
        }
    }

    private fun transitionAuditEventType(transitionType: StageTransitionType): SpecAuditEventType {
        return when (transitionType) {
            StageTransitionType.ADVANCE -> SpecAuditEventType.STAGE_ADVANCED
            StageTransitionType.JUMP -> SpecAuditEventType.STAGE_JUMPED
            StageTransitionType.ROLLBACK -> SpecAuditEventType.STAGE_ROLLED_BACK
        }
    }

    private fun evaluateStageGate(request: StageTransitionRequest): GateResult {
        return gateRuleEngine.evaluate(
            request = request,
            projectConfig = projectConfigDelegate.load(),
        )
    }

    private fun emitSpecStageChangedHook(
        workflowId: String,
        previousPhase: SpecPhase,
        currentPhase: SpecPhase,
        previousStage: StageId? = null,
        currentStage: StageId? = null,
    ) {
        runCatching {
            val metadata = linkedMapOf(
                "workflowId" to workflowId,
                "previousStage" to previousPhase.name,
                "currentStage" to currentPhase.name,
            )
            previousStage?.let { stage -> metadata["previousWorkflowStage"] = stage.name }
            currentStage?.let { stage -> metadata["currentWorkflowStage"] = stage.name }
            HookManager.getInstance(project).trigger(
                event = HookEvent.SPEC_STAGE_CHANGED,
                triggerContext = HookTriggerContext(
                    specStage = currentPhase.name,
                    metadata = metadata,
                ),
            )
        }.onFailure { error ->
            logger.warn(
                "Failed to emit SPEC_STAGE_CHANGED hook for workflow=$workflowId " +
                    "(${previousPhase.name} -> ${currentPhase.name})",
                error,
            )
        }
    }

    private data class StageMetadataState(
        val currentStage: StageId,
        val verifyEnabled: Boolean,
        val stageStates: Map<StageId, StageState>,
    )

    private fun initialPhaseForStage(stage: StageId): SpecPhase {
        return when (stage) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> SpecPhase.IMPLEMENT
        }
    }

    private fun initializeStageMetadata(
        templatePolicy: SpecTemplatePolicy,
        timestampMillis: Long,
    ): StageMetadataState {
        val stagePlan = templatePolicy.defaultStagePlan()
        val enteredAt = Instant.ofEpochMilli(timestampMillis).toString()
        return StageMetadataState(
            currentStage = stagePlan.firstActiveStage,
            verifyEnabled = stagePlan.isActive(StageId.VERIFY),
            stageStates = stagePlan.initialStageStates(enteredAt),
        )
    }

    private fun advanceStageMetadata(
        workflow: SpecWorkflow,
        toPhase: SpecPhase,
        timestampMillis: Long,
    ): StageMetadataState {
        val toStage = toPhase.toStageId()
        val beforeStage = workflow.currentStage
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()

        val previousState = updated[beforeStage] ?: StageState(
            active = true,
            status = StageProgress.NOT_STARTED,
        )
        updated[beforeStage] = previousState.copy(
            active = true,
            status = StageProgress.DONE,
            enteredAt = previousState.enteredAt ?: timestamp,
            completedAt = timestamp,
        )

        val nextState = updated[toStage] ?: StageState(
            active = true,
            status = StageProgress.NOT_STARTED,
        )
        updated[toStage] = nextState.copy(
            active = true,
            status = StageProgress.IN_PROGRESS,
            enteredAt = nextState.enteredAt ?: timestamp,
            completedAt = null,
        )
        return StageMetadataState(
            currentStage = toStage,
            verifyEnabled = workflow.verifyEnabled,
            stageStates = updated,
        )
    }

    private fun rollbackStageMetadata(
        workflow: SpecWorkflow,
        toPhase: SpecPhase,
        timestampMillis: Long,
    ): StageMetadataState {
        val toStage = toPhase.toStageId()
        val fromStage = workflow.currentStage
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()

        val fromState = updated[fromStage] ?: StageState(
            active = true,
            status = StageProgress.IN_PROGRESS,
        )
        updated[fromStage] = fromState.copy(
            active = fromState.active,
            status = StageProgress.NOT_STARTED,
            completedAt = null,
        )

        val targetState = updated[toStage] ?: StageState(
            active = true,
            status = StageProgress.NOT_STARTED,
        )
        updated[toStage] = targetState.copy(
            active = true,
            status = StageProgress.IN_PROGRESS,
            enteredAt = targetState.enteredAt ?: timestamp,
            completedAt = null,
        )
        return StageMetadataState(
            currentStage = toStage,
            verifyEnabled = workflow.verifyEnabled,
            stageStates = updated,
        )
    }

    private fun markCurrentStageCompleted(
        workflow: SpecWorkflow,
        timestampMillis: Long,
    ): Map<StageId, StageState> {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()
        val currentState = updated[workflow.currentStage] ?: StageState(
            active = true,
            status = StageProgress.IN_PROGRESS,
        )
        updated[workflow.currentStage] = currentState.copy(
            active = true,
            status = StageProgress.DONE,
            enteredAt = currentState.enteredAt ?: timestamp,
            completedAt = timestamp,
        )
        return updated
    }

    private fun enrichGenerationOptions(
        workflow: SpecWorkflow,
        options: GenerationOptions,
    ): GenerationOptions {
        val normalizedWorkingDirectory = options.workingDirectory
            ?.trim()
            ?.ifBlank { null }
            ?: project.basePath
                ?.trim()
                ?.ifBlank { null }
        val normalizedOperationMode = options.operationMode
            ?.trim()
            ?.ifBlank { null }
            ?: OperationMode.PLAN.name
        val enrichedOptions = options.copy(
            workingDirectory = normalizedWorkingDirectory,
            operationMode = normalizedOperationMode,
        )

        val baselineContext = buildIncrementalBaselineContext(workflow)
        val projectContext = buildIncrementalProjectContext(workflow)
        val existingContext = enrichedOptions.confirmedContext
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        val mergedContextSections = listOfNotNull(
            existingContext.takeIf { it.isNotBlank() },
            baselineContext?.takeIf { it.isNotBlank() },
            projectContext?.takeIf { it.isNotBlank() },
        )
        if (mergedContextSections.isEmpty()) {
            return enrichedOptions
        }
        val mergedContext = mergedContextSections.joinToString(separator = "\n\n---\n\n")
        return enrichedOptions.copy(confirmedContext = mergedContext)
    }

    private fun buildIncrementalBaselineContext(workflow: SpecWorkflow): String? {
        if (!workflow.isIncrementalWorkflow()) {
            return null
        }
        val baselineWorkflowId = workflow.baselineWorkflowId?.trim().orEmpty()
        if (baselineWorkflowId.isBlank()) {
            return null
        }
        val baseline = activeWorkflows[baselineWorkflowId]
            ?: storageDelegate.loadWorkflow(baselineWorkflowId).getOrElse { error ->
                logger.warn("Failed to load baseline workflow for incremental generation: $baselineWorkflowId", error)
                return null
            }
        activeWorkflows[baselineWorkflowId] = baseline

        fun baselineDoc(phase: SpecPhase): String {
            return baseline.getDocument(phase)?.content?.trim().takeUnless { it.isNullOrEmpty() } ?: "(无)"
        }

        return buildString {
            appendLine("## 增量需求基线上下文")
            appendLine("当前工作流是增量需求，请在输出中明确区分“新增 / 修改 / 保持不变”。")
            appendLine("基线工作流 ID: ${baseline.id}")
            appendLine("基线标题: ${baseline.title.ifBlank { baseline.id }}")
            if (workflow.description.isNotBlank()) {
                appendLine("当前工作流描述（变更目标）: ${workflow.description.trim()}")
            }
            appendLine()
            appendLine("## 基线 requirements.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.SPECIFY))
            appendLine("```")
            appendLine()
            appendLine("## 基线 design.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.DESIGN))
            appendLine("```")
            appendLine()
            appendLine("## 基线 tasks.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.IMPLEMENT))
            appendLine("```")
        }
    }

    private fun buildIncrementalProjectContext(workflow: SpecWorkflow): String? {
        if (!workflow.isIncrementalWorkflow() || workflow.currentPhase != SpecPhase.SPECIFY) {
            return null
        }
        val basePath = project.basePath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val root = runCatching { Path.of(basePath) }.getOrNull() ?: return null
        if (!Files.isDirectory(root)) {
            return null
        }

        val topLevelDirectories = listTopLevelEntries(root, directoriesOnly = true)
        val topLevelFiles = listTopLevelEntries(root, directoriesOnly = false)
        val keyFileSnippets = readKeyProjectFiles(root)
        val sourceSnapshot = buildSourceSnapshot(root)

        if (topLevelDirectories.isEmpty() && topLevelFiles.isEmpty() && keyFileSnippets.isEmpty() && sourceSnapshot.isBlank()) {
            return null
        }

        return buildString {
            appendLine("## 现有项目上下文（增量需求生成要求）")
            appendLine("生成 requirements.md 时，请结合当前代码结构和已有配置，优先复用现有模块与命名。")
            if (topLevelDirectories.isNotEmpty()) {
                appendLine("顶层目录: ${topLevelDirectories.joinToString(", ")}")
            }
            if (topLevelFiles.isNotEmpty()) {
                appendLine("顶层文件: ${topLevelFiles.joinToString(", ")}")
            }
            if (sourceSnapshot.isNotBlank()) {
                appendLine()
                appendLine("### 现有源码文件（节选）")
                appendLine(sourceSnapshot)
            }
            if (keyFileSnippets.isNotEmpty()) {
                appendLine()
                appendLine("### 关键项目文件（节选）")
                keyFileSnippets.forEach { snippet ->
                    appendLine("#### ${snippet.relativePath}")
                    appendLine("```")
                    appendLine(snippet.content)
                    appendLine("```")
                    appendLine()
                }
            }
        }.trimEnd()
    }

    private fun listTopLevelEntries(root: Path, directoriesOnly: Boolean): List<String> {
        return runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName?.toString().orEmpty()
                        if (name.isBlank() || shouldIgnoreProjectEntry(name)) {
                            return@filter false
                        }
                        if (directoriesOnly) {
                            Files.isDirectory(path)
                        } else {
                            Files.isRegularFile(path)
                        }
                    }
                    .map { it.fileName.toString() }
                    .sorted()
                    .limit(MAX_TOP_LEVEL_ENTRIES.toLong())
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    private fun readKeyProjectFiles(root: Path): List<ProjectContextSnippet> {
        return KEY_PROJECT_CONTEXT_FILES.mapNotNull { relativePath ->
            val path = root.resolve(relativePath)
            if (!Files.isRegularFile(path)) {
                return@mapNotNull null
            }
            val raw = runCatching {
                Files.readString(path, DEFAULT_PROJECT_CONTEXT_CHARSET)
            }.getOrElse { error ->
                logger.debug("Skip project context file due to read failure: $relativePath", error)
                return@mapNotNull null
            }
            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isBlank()) {
                return@mapNotNull null
            }
            ProjectContextSnippet(
                relativePath = relativePath,
                content = clipContextText(normalized, MAX_KEY_FILE_SNIPPET_LINES, MAX_KEY_FILE_SNIPPET_CHARS),
            )
        }
    }

    private fun buildSourceSnapshot(root: Path): String {
        val lines = mutableListOf<String>()
        for (relativeDir in SOURCE_CONTEXT_DIRS) {
            val dir = root.resolve(relativeDir)
            if (!Files.isDirectory(dir)) {
                continue
            }
            val files = runCatching {
                Files.walk(dir, SOURCE_SNAPSHOT_DEPTH).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .map { root.relativize(it).toString().replace('\\', '/') }
                        .sorted()
                        .limit(MAX_SOURCE_FILES_PER_DIR.toLong())
                        .toList()
                }
            }.getOrElse { error ->
                logger.debug("Skip source snapshot for $relativeDir", error)
                emptyList()
            }
            if (files.isEmpty()) {
                continue
            }
            lines += "- $relativeDir"
            files.forEach { path -> lines += "  - `$path`" }
        }
        return lines.joinToString("\n")
    }

    private fun clipContextText(content: String, maxLines: Int, maxChars: Int): String {
        val lines = content.lines()
        val clippedByLines = lines.size > maxLines
        val linesWithinBudget = lines.take(maxLines).joinToString("\n")
        val clippedByChars = linesWithinBudget.length > maxChars
        val clipped = if (clippedByChars) {
            linesWithinBudget.take(maxChars).trimEnd()
        } else {
            linesWithinBudget
        }
        return if (clippedByLines || clippedByChars) {
            "$clipped\n...(截断)"
        } else {
            clipped
        }
    }

    private fun shouldIgnoreProjectEntry(name: String): Boolean {
        return name in PROJECT_CONTEXT_IGNORED_ENTRIES
    }

    private data class ProjectContextSnippet(
        val relativePath: String,
        val content: String,
    )

    /**
     * 生成工作流 ID
     */
    private fun generateWorkflowId(): String {
        return workflowIdGenerator.nextId()
    }

    companion object {
        private val PROJECT_CONTEXT_IGNORED_ENTRIES = setOf(
            ".git",
            ".idea",
            ".gradle",
            ".spec-coding",
            "build",
            "out",
            "node_modules",
            "__pycache__",
            ".DS_Store",
            "Thumbs.db",
        )
        private val KEY_PROJECT_CONTEXT_FILES = listOf(
            "README.md",
            "README.zh-CN.md",
            "docs/spec-coding-plugin-plan.md",
            "docs/dev-checklist.md",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "src/main/resources/META-INF/plugin.xml",
            "package.json",
            "pom.xml",
            "pyproject.toml",
        )
        private val SOURCE_CONTEXT_DIRS = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/main/resources",
            "src/test/kotlin",
            "src/test/java",
        )
        private val DEFAULT_PROJECT_CONTEXT_CHARSET: Charset = Charsets.UTF_8
        private const val MAX_TOP_LEVEL_ENTRIES = 12
        private const val MAX_KEY_FILE_SNIPPET_LINES = 120
        private const val MAX_KEY_FILE_SNIPPET_CHARS = 4000
        private const val SOURCE_SNAPSHOT_DEPTH = 4
        private const val MAX_SOURCE_FILES_PER_DIR = 18

        fun getInstance(project: Project): SpecEngine = project.service()
    }
}

class DocumentRevisionConflictException(
    val workflowId: String,
    val phase: SpecPhase,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Document revision conflict: expected $expectedRevision but was $actualRevision " +
        "(workflow=$workflowId, phase=${phase.name})",
)

/**
 * Spec 生成进度
 */
sealed class SpecGenerationProgress {
    data class Started(val phase: SpecPhase) : SpecGenerationProgress()
    data class Generating(val phase: SpecPhase, val progress: Double) : SpecGenerationProgress()
    data class Completed(val document: SpecDocument) : SpecGenerationProgress()
    data class ValidationFailed(val document: SpecDocument, val validation: ValidationResult) : SpecGenerationProgress()
    data class Failed(val error: String, val details: String?) : SpecGenerationProgress()
}
