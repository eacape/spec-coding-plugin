package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.time.Instant

@Service(Service.Level.PROJECT)
class SpecVerificationService(private val project: Project) {
    private var _storageOverride: SpecStorage? = null
    private var _projectConfigServiceOverride: SpecProjectConfigService? = null
    private var _processRunnerOverride: SpecProcessRunner? = null
    private var _planIdGeneratorOverride: WorkflowIdGenerator? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val projectConfigService: SpecProjectConfigService by lazy {
        _projectConfigServiceOverride ?: SpecProjectConfigService(project)
    }
    private val processRunner: SpecProcessRunner by lazy { _processRunnerOverride ?: SpecProcessRunner() }
    private val planIdGenerator: WorkflowIdGenerator by lazy {
        _planIdGeneratorOverride ?: WorkflowIdGenerator(prefix = VERIFY_PLAN_ID_PREFIX)
    }
    private val pendingPlans = mutableMapOf<String, PendingVerifyPlan>()

    internal constructor(
        project: Project,
        storage: SpecStorage,
        projectConfigService: SpecProjectConfigService = SpecProjectConfigService(project),
        processRunner: SpecProcessRunner = SpecProcessRunner(),
        planIdGenerator: WorkflowIdGenerator = WorkflowIdGenerator(prefix = VERIFY_PLAN_ID_PREFIX),
    ) : this(project) {
        _storageOverride = storage
        _projectConfigServiceOverride = projectConfigService
        _processRunnerOverride = processRunner
        _planIdGeneratorOverride = planIdGenerator
    }

    fun preview(workflowId: String): VerifyPlan {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        if (!isVerifyEnabled(workflow)) {
            throw VerifyStageDisabledError(workflowId)
        }

        val effectiveConfig = resolveEffectiveConfig(workflow)
        val projectRoot = resolveProjectRoot()
        val requests = effectiveConfig.config.verify.commands.map { command ->
            processRunner.prepare(projectRoot = projectRoot, verifyConfig = effectiveConfig.config.verify, command = command)
        }
        val plan = VerifyPlan(
            planId = planIdGenerator.nextId(),
            workflowId = workflowId,
            currentStage = workflow.currentStage,
            generatedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            commands = requests.map { request ->
                VerifyPlanCommand(
                    commandId = request.commandId,
                    displayName = request.displayName,
                    command = request.command,
                    workingDirectory = request.workingDirectory,
                    timeoutMs = request.timeoutMs,
                    outputLimitChars = request.outputLimitChars,
                    redactionPatterns = request.redactionPatterns,
                )
            },
            policy = effectiveConfig.policy,
        )

        pendingPlans.entries.removeIf { (_, pending) -> pending.plan.workflowId == workflowId }
        pendingPlans[plan.planId] = PendingVerifyPlan(
            plan = plan,
            requests = requests,
            currentStage = workflow.currentStage,
            verifyEnabled = isVerifyEnabled(workflow),
            configPinHash = workflow.configPinHash?.trim()?.takeIf { it.isNotEmpty() },
        )
        return plan
    }

    internal fun resolvePlan(workflowId: String, planId: String): VerifyPlan {
        return resolvePendingPlan(workflowId, planId).plan
    }

    internal fun resolveExecutionRequests(workflowId: String, planId: String): List<VerifyCommandExecutionRequest> {
        return resolvePendingPlan(workflowId, planId).requests
    }

    private fun resolvePendingPlan(workflowId: String, planId: String): PendingVerifyPlan {
        val pending = pendingPlans[planId] ?: throw MissingVerifyPlanError(planId)
        if (pending.plan.workflowId != workflowId) {
            throw StaleVerifyPlanError(planId, workflowId)
        }

        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val configPinHash = workflow.configPinHash?.trim()?.takeIf { it.isNotEmpty() }
        if (
            pending.currentStage != workflow.currentStage ||
            pending.verifyEnabled != isVerifyEnabled(workflow) ||
            pending.configPinHash != configPinHash
        ) {
            pendingPlans.remove(planId)
            throw StaleVerifyPlanError(planId, workflowId)
        }
        return pending
    }

    private fun resolveEffectiveConfig(workflow: SpecWorkflow): EffectiveVerifyConfig {
        val currentConfig = projectConfigService.load()
        val currentConfigPin = projectConfigService.createConfigPin(currentConfig)
        val workflowConfigPinHash = workflow.configPinHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val confirmationReasons = mutableListOf(DEFAULT_CONFIRMATION_REASON)

        if (workflowConfigPinHash == null) {
            return EffectiveVerifyConfig(
                config = currentConfig,
                policy = VerifyPlanPolicy(
                    configSource = VerifyPlanConfigSource.PROJECT_CONFIG,
                    workflowConfigPinHash = null,
                    effectiveConfigHash = currentConfigPin.hash,
                    usesPinnedSnapshot = false,
                    confirmationRequired = true,
                    confirmationReasons = confirmationReasons,
                ),
            )
        }

        if (workflowConfigPinHash == currentConfigPin.hash) {
            return EffectiveVerifyConfig(
                config = currentConfig,
                policy = VerifyPlanPolicy(
                    configSource = VerifyPlanConfigSource.WORKFLOW_PINNED,
                    workflowConfigPinHash = workflowConfigPinHash,
                    effectiveConfigHash = workflowConfigPinHash,
                    usesPinnedSnapshot = false,
                    confirmationRequired = true,
                    confirmationReasons = confirmationReasons,
                ),
            )
        }

        val pinnedSnapshot = storage.loadConfigPinSnapshot(workflow.id, workflowConfigPinHash).getOrThrow()
        confirmationReasons += PINNED_SNAPSHOT_CONFIRMATION_REASON
        return EffectiveVerifyConfig(
            config = projectConfigService.parseSnapshotYaml(pinnedSnapshot.snapshotYaml),
            policy = VerifyPlanPolicy(
                configSource = VerifyPlanConfigSource.WORKFLOW_PINNED,
                workflowConfigPinHash = workflowConfigPinHash,
                effectiveConfigHash = workflowConfigPinHash,
                usesPinnedSnapshot = true,
                confirmationRequired = true,
                confirmationReasons = confirmationReasons,
            ),
        )
    }

    private fun isVerifyEnabled(workflow: SpecWorkflow): Boolean {
        return workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true
    }

    private fun resolveProjectRoot(): Path {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
        return Path.of(basePath).toAbsolutePath().normalize()
    }

    private data class EffectiveVerifyConfig(
        val config: SpecProjectConfig,
        val policy: VerifyPlanPolicy,
    )

    private data class PendingVerifyPlan(
        val plan: VerifyPlan,
        val requests: List<VerifyCommandExecutionRequest>,
        val currentStage: StageId,
        val verifyEnabled: Boolean,
        val configPinHash: String?,
    )

    companion object {
        private const val VERIFY_PLAN_ID_PREFIX = "verify-plan"
        private const val DEFAULT_CONFIRMATION_REASON = "Review verify commands before execution."
        private const val PINNED_SNAPSHOT_CONFIRMATION_REASON =
            "Current project config differs from the workflow pin; using the pinned config snapshot for this plan."
    }
}
