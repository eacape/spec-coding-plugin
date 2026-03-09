package com.eacape.speccodingplugin.spec

data class SpecProjectConfig(
    val schemaVersion: Int,
    val defaultTemplate: WorkflowTemplate,
    val templates: Map<WorkflowTemplate, SpecTemplatePolicy>,
    val gate: SpecGatePolicy,
    val rules: Map<String, SpecRulePolicy>,
) {
    fun policyFor(template: WorkflowTemplate): SpecTemplatePolicy = templates.getValue(template)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

data class SpecConfigPin(
    val hash: String,
    val snapshotYaml: String,
)

data class SpecTemplatePolicy(
    val definition: TemplateDefinition,
    val verifyEnabledByDefault: Boolean,
    val implementEnabledByDefault: Boolean,
) {
    fun defaultStagePlan(): WorkflowStagePlan {
        return definition.buildStagePlan(defaultActivationOptions())
    }

    fun defaultActiveStages(): List<StageId> {
        return defaultStagePlan().activeStages
    }

    private fun defaultActivationOptions(): StageActivationOptions {
        val overrides = linkedMapOf<StageId, Boolean>()
        definition.stagePlan
            .firstOrNull { item -> item.id == StageId.VERIFY && item.optional }
            ?.let { overrides[StageId.VERIFY] = verifyEnabledByDefault }
        definition.stagePlan
            .firstOrNull { item -> item.id == StageId.IMPLEMENT && item.optional }
            ?.let { overrides[StageId.IMPLEMENT] = implementEnabledByDefault }
        return StageActivationOptions(stageOverrides = overrides)
    }
}

data class SpecGatePolicy(
    val allowWarningAdvance: Boolean = true,
    val requireWarningConfirmation: Boolean = true,
    val allowJump: Boolean = true,
    val jumpRequiresMinimalGate: Boolean = true,
    val allowRollback: Boolean = true,
)

data class SpecRulePolicy(
    val enabled: Boolean = true,
    val severity: GateStatus = GateStatus.ERROR,
)
