package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecValidator
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal enum class SpecWorkflowWorkbenchActionKind {
    ADVANCE,
    JUMP,
    ROLLBACK,
    START_TASK,
    COMPLETE_TASK,
}

internal enum class SpecWorkflowWorkbenchDocumentMode {
    PREVIEW,
    READ_ONLY,
}

internal data class SpecWorkflowWorkbenchAction(
    val kind: SpecWorkflowWorkbenchActionKind,
    val label: String,
    val enabled: Boolean,
    val targetStage: StageId? = null,
    val taskId: String? = null,
)

internal data class SpecWorkflowStageCompletionCheck(
    val id: String,
    val label: String,
    val completed: Boolean,
    val blockerMessage: String? = null,
)

internal data class SpecWorkflowStageProgressView(
    val stepIndex: Int,
    val totalSteps: Int,
    val stageStatus: StageProgress,
    val completedCheckCount: Int,
    val totalCheckCount: Int,
    val completionChecks: List<SpecWorkflowStageCompletionCheck>,
)

internal data class SpecWorkflowStageArtifactBinding(
    val stageId: StageId,
    val title: String,
    val fileName: String?,
    val documentPhase: SpecPhase?,
    val mode: SpecWorkflowWorkbenchDocumentMode,
    val fallbackEditable: Boolean,
    val available: Boolean = false,
    val previewContent: String? = null,
)

internal data class SpecWorkflowImplementationFocus(
    val taskId: String,
    val title: String,
    val status: TaskStatus,
)

internal data class SpecWorkflowStageWorkbenchState(
    val currentStage: StageId,
    val focusedStage: StageId,
    val progress: SpecWorkflowStageProgressView,
    val primaryAction: SpecWorkflowWorkbenchAction?,
    val overflowActions: List<SpecWorkflowWorkbenchAction>,
    val blockers: List<String>,
    val artifactBinding: SpecWorkflowStageArtifactBinding,
    val implementationFocus: SpecWorkflowImplementationFocus? = null,
    val visibleSections: Set<SpecWorkflowWorkspaceSectionId>,
)

internal object SpecWorkflowStageWorkbenchBuilder {
    fun build(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask> = emptyList(),
        verifyDeltaState: SpecWorkflowVerifyDeltaState? = null,
        gateResult: GateResult? = null,
        focusedStage: StageId? = null,
    ): SpecWorkflowStageWorkbenchState {
        val orderedStages = overviewState.stageStepper.stages.map { it.stageId }
        val resolvedFocusedStage = focusedStage
            ?.takeIf { requestedStage -> requestedStage in orderedStages }
            ?: overviewState.currentStage
        val focusedStep = overviewState.stageStepper.stages
            .firstOrNull { it.stageId == resolvedFocusedStage }
            ?: overviewState.stageStepper.stages.firstOrNull()
            ?: SpecWorkflowStageStepState(
                stageId = overviewState.currentStage,
                active = true,
                current = true,
                progress = StageProgress.IN_PROGRESS,
            )
        val activeStages = overviewState.activeStages.ifEmpty { orderedStages }
        val resolvedStepIndex = activeStages.indexOf(resolvedFocusedStage)
            .takeIf { it >= 0 }
            ?: orderedStages.indexOf(resolvedFocusedStage).coerceAtLeast(0)
        val completionChecks = buildCompletionChecks(
            workflow = workflow,
            overviewState = overviewState,
            focusedStage = resolvedFocusedStage,
            tasks = tasks,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
        )
        val implementationFocus = buildImplementationFocus(
            focusedStage = resolvedFocusedStage,
            tasks = tasks,
        )
        val blockers = buildBlockers(
            overviewState = overviewState,
            focusedStage = resolvedFocusedStage,
            gateResult = gateResult,
            completionChecks = completionChecks,
        )

        return SpecWorkflowStageWorkbenchState(
            currentStage = overviewState.currentStage,
            focusedStage = resolvedFocusedStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = resolvedStepIndex + 1,
                totalSteps = activeStages.size.coerceAtLeast(1),
                stageStatus = focusedStep.progress,
                completedCheckCount = completionChecks.count(SpecWorkflowStageCompletionCheck::completed),
                totalCheckCount = completionChecks.size,
                completionChecks = completionChecks,
            ),
            primaryAction = buildPrimaryAction(
                overviewState = overviewState,
                focusedStage = resolvedFocusedStage,
                tasks = tasks,
                implementationFocus = implementationFocus,
                blockers = blockers,
            ),
            overflowActions = buildOverflowActions(overviewState, resolvedFocusedStage),
            blockers = blockers,
            artifactBinding = buildArtifactBinding(workflow, resolvedFocusedStage),
            implementationFocus = implementationFocus,
            visibleSections = SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = resolvedFocusedStage,
                status = overviewState.status,
            ),
        )
    }

    private fun buildPrimaryAction(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        implementationFocus: SpecWorkflowImplementationFocus?,
        blockers: List<String>,
    ): SpecWorkflowWorkbenchAction? {
        if (overviewState.currentStage == StageId.ARCHIVE || overviewState.status == WorkflowStatus.COMPLETED) {
            return null
        }
        if (focusedStage == overviewState.currentStage && focusedStage == StageId.IMPLEMENT) {
            buildImplementPrimaryAction(
                overviewState = overviewState,
                tasks = tasks,
                implementationFocus = implementationFocus,
            )?.let { return it }
        }
        return when {
            focusedStage == overviewState.currentStage -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
                label = overviewState.nextStage?.let { nextStage ->
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.primary.advance",
                        SpecWorkflowOverviewPresenter.stageLabel(nextStage),
                    )
                } ?: SpecCodingBundle.message("spec.action.advance.text"),
                enabled = overviewState.stageStepper.canAdvance && blockers.isEmpty(),
                targetStage = overviewState.nextStage,
            )

            else -> null
        }
    }

    private fun buildImplementPrimaryAction(
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        implementationFocus: SpecWorkflowImplementationFocus?,
    ): SpecWorkflowWorkbenchAction? {
        val relatedFilesConfirmed = tasks
            .filter { it.status == TaskStatus.COMPLETED }
            .all { it.relatedFiles.isNotEmpty() }
        return when (implementationFocus?.status) {
            TaskStatus.IN_PROGRESS -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.COMPLETE_TASK,
                label = SpecCodingBundle.message(
                    "spec.toolwindow.overview.primary.implement.completeTask",
                    implementationFocus.taskId,
                ),
                enabled = true,
                taskId = implementationFocus.taskId,
            )

            TaskStatus.PENDING -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.START_TASK,
                label = SpecCodingBundle.message(
                    "spec.toolwindow.overview.primary.implement.startTask",
                    implementationFocus.taskId,
                ),
                enabled = true,
                taskId = implementationFocus.taskId,
            )

            TaskStatus.BLOCKED -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.START_TASK,
                label = SpecCodingBundle.message(
                    "spec.toolwindow.overview.primary.implement.resumeTask",
                    implementationFocus.taskId,
                ),
                enabled = true,
                taskId = implementationFocus.taskId,
            )

            else -> {
                val allWorkSettled = tasks.isNotEmpty() && tasks.all(::isImplementationTaskSettled)
                if (!allWorkSettled) {
                    return null
                }
                SpecWorkflowWorkbenchAction(
                    kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
                    label = overviewState.nextStage?.let { nextStage ->
                        SpecCodingBundle.message(
                            "spec.toolwindow.overview.primary.implement.continueCheck.withTarget",
                            SpecWorkflowOverviewPresenter.stageLabel(nextStage),
                        )
                    } ?: SpecCodingBundle.message("spec.toolwindow.overview.primary.implement.continueCheck"),
                    enabled = overviewState.stageStepper.canAdvance && relatedFilesConfirmed,
                    targetStage = overviewState.nextStage,
                )
            }
        }
    }

    private fun buildOverflowActions(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
    ): List<SpecWorkflowWorkbenchAction> {
        val jumpTarget = focusedStage.takeIf { stageId -> stageId in overviewState.stageStepper.jumpTargets }
        val rollbackTarget = focusedStage.takeIf { stageId -> stageId in overviewState.stageStepper.rollbackTargets }
        return buildList {
            if (overviewState.stageStepper.jumpTargets.isNotEmpty()) {
                add(
                    SpecWorkflowWorkbenchAction(
                        kind = SpecWorkflowWorkbenchActionKind.JUMP,
                        label = jumpTarget?.let { target ->
                            SpecCodingBundle.message(
                                "spec.toolwindow.overview.more.jumpTo",
                                SpecWorkflowOverviewPresenter.stageLabel(target),
                            )
                        } ?: SpecCodingBundle.message("spec.action.jump.text"),
                        enabled = true,
                        targetStage = jumpTarget,
                    ),
                )
            }
            if (overviewState.stageStepper.rollbackTargets.isNotEmpty()) {
                add(
                    SpecWorkflowWorkbenchAction(
                        kind = SpecWorkflowWorkbenchActionKind.ROLLBACK,
                        label = rollbackTarget?.let { target ->
                            SpecCodingBundle.message(
                                "spec.toolwindow.overview.more.rollbackTo",
                                SpecWorkflowOverviewPresenter.stageLabel(target),
                            )
                        } ?: SpecCodingBundle.message("spec.action.rollback.text"),
                        enabled = true,
                        targetStage = rollbackTarget,
                    ),
                )
            }
        }
    }

    private fun buildBlockers(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        gateResult: GateResult?,
        completionChecks: List<SpecWorkflowStageCompletionCheck>,
    ): List<String> {
        val focusedStep = overviewState.stageStepper.stages.firstOrNull { it.stageId == focusedStage }
        if (focusedStep?.active == false) {
            return listOf(SpecCodingBundle.message("spec.toolwindow.overview.blockers.inactive"))
        }
        if (focusedStage != overviewState.currentStage) {
            return emptyList()
        }
        if (gateResult?.status == GateStatus.ERROR) {
            return limitBlockers(
                buildGateBlockers(
                    gateResult = gateResult,
                    overviewState = overviewState,
                ),
            )
        }
        val checkBlockers = completionChecks
            .filterNot(SpecWorkflowStageCompletionCheck::completed)
            .mapNotNull(SpecWorkflowStageCompletionCheck::blockerMessage)
        return limitBlockers(checkBlockers)
    }

    private fun buildGateBlockers(
        gateResult: GateResult,
        overviewState: SpecWorkflowOverviewState,
    ): List<String> {
        val gateBlockers = gateResult.aggregation.errorViolations
            .map { violation ->
                if (violation.fileName.isNotBlank() && violation.line > 0) {
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.blockers.gate.withLocation",
                        violation.ruleId,
                        violation.message,
                        violation.fileName,
                        violation.line,
                    )
                } else {
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.blockers.gate",
                        violation.ruleId,
                        violation.message,
                    )
                }
            }
        if (gateBlockers.isNotEmpty()) {
            return gateBlockers
        }
        return listOfNotNull(overviewState.gateSummary?.takeIf { it.isNotBlank() })
    }

    private fun limitBlockers(blockers: List<String>): List<String> {
        if (blockers.isEmpty()) {
            return emptyList()
        }
        val visible = blockers.take(BLOCKER_LIMIT).toMutableList()
        val remaining = blockers.size - visible.size
        if (remaining > 0) {
            visible += SpecCodingBundle.message("spec.toolwindow.overview.blockers.more", remaining)
        }
        return visible
    }

    private fun buildArtifactBinding(
        workflow: SpecWorkflow,
        focusedStage: StageId,
    ): SpecWorkflowStageArtifactBinding {
        val documentPhase = when (focusedStage) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            -> SpecPhase.IMPLEMENT

            StageId.VERIFY,
            StageId.ARCHIVE,
            -> null
        }
        val fileName = when (focusedStage) {
            StageId.IMPLEMENT -> StageId.TASKS.artifactFileName
            StageId.ARCHIVE -> StageId.VERIFY.artifactFileName
            else -> focusedStage.artifactFileName
        }
        val mode = if (documentPhase != null) {
            SpecWorkflowWorkbenchDocumentMode.PREVIEW
        } else {
            SpecWorkflowWorkbenchDocumentMode.READ_ONLY
        }
        val title = when (focusedStage) {
            StageId.IMPLEMENT -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
            StageId.ARCHIVE -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
            else -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
        }

        return SpecWorkflowStageArtifactBinding(
            stageId = focusedStage,
            title = title,
            fileName = fileName,
            documentPhase = documentPhase,
            mode = mode,
            fallbackEditable = documentPhase != null && workflow.documents.containsKey(documentPhase),
        )
    }

    private fun buildCompletionChecks(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
        gateResult: GateResult?,
    ): List<SpecWorkflowStageCompletionCheck> {
        val taskIds = tasks.map(StructuredTask::id).toSet()
        val completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED }
        val tasksDocument = workflow.documents[SpecPhase.IMPLEMENT]
        val requirementsDocument = workflow.documents[SpecPhase.SPECIFY]
        val designDocument = workflow.documents[SpecPhase.DESIGN]
        val gateReady = isGateReady(
            overviewState = overviewState,
            focusedStage = focusedStage,
            gateResult = gateResult,
        )
        val gateUnavailableBlocker = gateUnavailableBlocker(
            overviewState = overviewState,
            focusedStage = focusedStage,
            gateResult = gateResult,
        )
        return when (focusedStage) {
            StageId.REQUIREMENTS -> {
                val validation = requirementsDocument?.let { document ->
                    document.validationResult ?: SpecValidator.validate(document)
                }
                val hasSections = requirementsDocument?.content?.let(::hasRequirementsSections) == true
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.requirements.document"),
                        completed = requirementsDocument != null,
                        blockerMessage = if (requirementsDocument == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.document")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-sections",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.requirements.sections"),
                        completed = hasSections,
                        blockerMessage = if (requirementsDocument != null && !hasSections) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.sections")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-validation",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.requirements.validation"),
                        completed = validation?.hasErrors() == false,
                        blockerMessage = if (requirementsDocument != null && validation?.hasErrors() == true) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.validation")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.DESIGN -> {
                val validation = designDocument?.let { document ->
                    document.validationResult ?: SpecValidator.validate(document)
                }
                val hasSections = designDocument?.content?.let(::hasDesignSections) == true
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "design-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.design.document"),
                        completed = designDocument != null,
                        blockerMessage = if (designDocument == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.document")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-sections",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.design.sections"),
                        completed = hasSections,
                        blockerMessage = if (designDocument != null && !hasSections) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.sections")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-validation",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.design.validation"),
                        completed = validation?.hasErrors() == false,
                        blockerMessage = if (designDocument != null && validation?.hasErrors() == true) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.validation")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.TASKS -> {
                val hasValidDependencies = tasks.isNotEmpty() && tasks.all { task ->
                    task.dependsOn.all { dependencyId -> dependencyId != task.id && dependencyId in taskIds }
                }
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.tasks.document"),
                        completed = tasksDocument != null,
                        blockerMessage = if (tasksDocument == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.document")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-structured",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.tasks.structured"),
                        completed = tasks.isNotEmpty(),
                        blockerMessage = if (tasksDocument != null && tasks.isEmpty()) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.structured")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-dependencies",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.tasks.dependencies"),
                        completed = hasValidDependencies,
                        blockerMessage = if (tasks.isNotEmpty() && !hasValidDependencies) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.dependencies")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.IMPLEMENT -> {
                val hasExecutionInFlight = tasks.any { it.status == TaskStatus.IN_PROGRESS }
                val allWorkSettled = tasks.isNotEmpty() && tasks.all(::isImplementationTaskSettled)
                val relatedFilesConfirmed = completedTasks.isEmpty() || completedTasks.all { task ->
                    task.relatedFiles.isNotEmpty()
                }
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-task-source",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.implement.taskSource"),
                        completed = tasksDocument != null && tasks.isNotEmpty(),
                        blockerMessage = if (tasksDocument == null || tasks.isEmpty()) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-progress",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.implement.progress"),
                        completed = hasExecutionInFlight || allWorkSettled,
                        blockerMessage = if (tasks.isNotEmpty() && !hasExecutionInFlight && !allWorkSettled) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-related-files",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.implement.relatedFiles"),
                        completed = relatedFilesConfirmed,
                        blockerMessage = if (!relatedFilesConfirmed) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.VERIFY -> {
                val latestRun = verifyDeltaState?.verificationHistory?.firstOrNull()
                val verifyEnabled = verifyDeltaState?.verifyEnabled == true ||
                    workflow.stageStates[StageId.VERIFY]?.active == true
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-enabled",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.enabled"),
                        completed = verifyEnabled,
                        blockerMessage = if (!verifyEnabled) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.enabled")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-plan",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.plan"),
                        completed = verifyEnabled,
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-run",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.run"),
                        completed = latestRun != null,
                        blockerMessage = if (verifyEnabled && latestRun == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.run")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.document"),
                        completed = verifyDeltaState?.verificationDocumentAvailable == true,
                        blockerMessage = if (verifyEnabled && verifyDeltaState?.verificationDocumentAvailable != true) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.document")
                        } else {
                            null
                        },
                    ),
                )
            }

            StageId.ARCHIVE -> {
                val activePreArchiveStages = overviewState.activeStages.filter { stageId -> stageId != StageId.ARCHIVE }
                val verifySatisfied = verifyDeltaState?.verifyEnabled != true ||
                    verifyDeltaState.verificationDocumentAvailable ||
                    verifyDeltaState.verificationHistory.isNotEmpty()
                val previousStagesComplete = activePreArchiveStages.all { stageId ->
                    workflow.stageStates[stageId]?.status == StageProgress.DONE || workflow.currentStage == StageId.ARCHIVE
                }
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-stages",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.stages"),
                        completed = previousStagesComplete,
                        blockerMessage = if (!previousStagesComplete) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.archive.stages")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-verification",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.verification"),
                        completed = verifySatisfied,
                        blockerMessage = if (!verifySatisfied) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.archive.verification")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-ready",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.ready"),
                        completed = workflow.currentStage == StageId.ARCHIVE || workflow.status == WorkflowStatus.COMPLETED,
                    ),
                )
            }
        }
    }

    private fun buildImplementationFocus(
        focusedStage: StageId,
        tasks: List<StructuredTask>,
    ): SpecWorkflowImplementationFocus? {
        if (focusedStage != StageId.IMPLEMENT || tasks.isEmpty()) {
            return null
        }
        val tasksById = tasks.associateBy(StructuredTask::id)
        val focusedTask = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
            ?: tasks.firstOrNull { task ->
                task.status == TaskStatus.PENDING && dependenciesCompleted(task, tasksById)
            }
            ?: tasks.firstOrNull { task ->
                task.status == TaskStatus.BLOCKED && dependenciesCompleted(task, tasksById)
            }
            ?: return null
        return SpecWorkflowImplementationFocus(
            taskId = focusedTask.id,
            title = focusedTask.title,
            status = focusedTask.status,
        )
    }

    private fun isGateReady(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        gateResult: GateResult?,
    ): Boolean {
        return when {
            focusedStage == overviewState.currentStage -> gateResult != null && gateResult.status != GateStatus.ERROR
            focusedStage.ordinal < overviewState.currentStage.ordinal ->
                overviewState.stageStepper.stages.firstOrNull { it.stageId == focusedStage }?.progress == StageProgress.DONE

            else -> false
        }
    }

    private fun gateUnavailableBlocker(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        gateResult: GateResult?,
    ): String? {
        return if (
            focusedStage == overviewState.currentStage &&
            overviewState.stageStepper.canAdvance &&
            gateResult == null
        ) {
            SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.gateUnavailable")
        } else {
            null
        }
    }

    private fun hasRequirementsSections(content: String): Boolean {
        return REQUIRED_REQUIREMENTS_SECTIONS.all { markers -> containsAnyMarker(content, markers) }
    }

    private fun hasDesignSections(content: String): Boolean {
        return REQUIRED_DESIGN_SECTIONS.all { markers -> containsAnyMarker(content, markers) }
    }

    private fun containsAnyMarker(content: String, markers: List<String>): Boolean {
        return markers.any { marker -> content.contains(marker, ignoreCase = true) }
    }

    private fun isImplementationTaskSettled(task: StructuredTask): Boolean {
        return task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED
    }

    private fun dependenciesCompleted(
        task: StructuredTask,
        tasksById: Map<String, StructuredTask>,
    ): Boolean {
        return task.dependsOn.all { dependencyId ->
            tasksById[dependencyId]?.status == TaskStatus.COMPLETED
        }
    }

    private val REQUIRED_REQUIREMENTS_SECTIONS = listOf(
        listOf("## 功能需求", "功能需求", "Functional Requirements"),
        listOf("## 非功能需求", "非功能需求", "Non-Functional Requirements"),
        listOf("## 用户故事", "用户故事", "User Stories"),
    )

    private val REQUIRED_DESIGN_SECTIONS = listOf(
        listOf("## 架构设计", "架构设计", "系统架构", "Architecture Design", "Architecture"),
        listOf("## 技术选型", "技术选型", "技术方案", "Technology Stack"),
        listOf("## 数据模型", "数据模型", "实体模型", "Data Model"),
    )

    private const val BLOCKER_LIMIT = 3
}
