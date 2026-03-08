package com.eacape.speccodingplugin.spec

/**
 * Task 00 architecture contract:
 * - defines layering boundaries for the spec subsystem
 * - tracks dependency decisions (Markdown AST / YAML / security / ULID)
 * - provides import guardrails enforced by tests
 */
object SpecArchitectureContract {

    enum class Layer {
        DOMAIN,
        APPLICATION,
        INFRASTRUCTURE,
    }

    enum class AdoptionStatus {
        ADOPTED,
        PLANNED,
    }

    data class DependencyDecision(
        val key: String,
        val capability: String,
        val selection: String,
        val rationale: String,
        val status: AdoptionStatus,
    )

    data class SourceRule(
        val fileName: String,
        val layer: Layer,
        val blockedImportPrefixes: Set<String> = emptySet(),
    )

    private val globalBlockedImportPrefixes = setOf(
        "com.eacape.speccodingplugin.ui.",
        "com.eacape.speccodingplugin.window.",
    )

    private val domainBlockedImportPrefixes = setOf(
        "com.intellij.",
        "com.eacape.speccodingplugin.core.",
        "com.eacape.speccodingplugin.hook.",
        "com.eacape.speccodingplugin.llm.",
        "kotlinx.coroutines.",
    )

    private val infrastructureBlockedImportPrefixes = setOf(
        "com.eacape.speccodingplugin.core.",
        "com.eacape.speccodingplugin.hook.",
        "com.eacape.speccodingplugin.llm.",
    )

    val dependencyDecisions: List<DependencyDecision> = listOf(
        DependencyDecision(
            key = "workflow-id",
            capability = "workflow id generation with sortable and low-collision format",
            selection = "built-in ULID style generator `WorkflowIdGenerator`",
            rationale = "avoid extra dependencies and keep offline deterministic behavior.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "yaml-codec",
            capability = "project config and spec-task yaml encoding/decoding",
            selection = "SnakeYAML with `SpecYamlCodec` safety restrictions",
            rationale = "allow only scalar/sequence/mapping and stable serialization.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "project-config",
            capability = "project-level config loading, validation, and default merge",
            selection = "SpecProjectConfigService + typed domain config models",
            rationale = "Task 09 requires schema-versioned parsing for template/gate/rule strategies.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "config-pin",
            capability = "workflow-bound config hash pinning and snapshot archival",
            selection = "SpecProjectConfigService deterministic SHA-256 pin + workflow `.history/config` snapshots",
            rationale = "Task 10 requires config hash binding to workflow metadata and immutable snapshot traceability.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "markdown-ast",
            capability = "markdown structural parsing and stable source locations",
            selection = "org.intellij.markdown AST + `SpecMarkdownAstParser`",
            rationale = "stable line mapping and code fence extraction with CRLF/LF consistency.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "security",
            capability = "storage and audit security hardening",
            selection = "atomic writes + lock manager + append-only YAML audit stream",
            rationale = "Task 04/05/08 establish baseline hardening; Task 51 extends rule coverage.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "artifact-service",
            capability = "artifact locate/read/write and template-driven skeleton bootstrap",
            selection = "SpecArtifactService + AtomicFileIO + workflow lock",
            rationale = "Task 11 requires reusable artifact operations and deterministic missing-artifact scaffolding.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "workflow-snapshot",
            capability = "before/after operation snapshots and delta baseline references",
            selection = "SpecStorage snapshots in `.history/snapshots` + baseline pointers in `.history/baselines`",
            rationale = "Task 12 requires key operation snapshots and reusable delta baseline references.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "workflow-metadata",
            capability = "workflow metadata persistence and retrieval for create/list/open operations",
            selection = "workflow.yaml stage metadata (`template/currentStage/stageStates`) + storage query APIs",
            rationale = "Task 13 requires durable template and stage-state records with list/open retrieval.",
            status = AdoptionStatus.ADOPTED,
        ),
    )

    val sourceRules: List<SourceRule> = listOf(
        SourceRule(
            fileName = "AtomicFileIO.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecAuditLogCodec.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecArchitectureContract.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecArtifactService.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecArchiveModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecDeltaModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecDeltaService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecEngine.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecGenerator.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecHistoryModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecMarkdownAstParser.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecMarkdownSanitizer.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecProjectConfigModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecProjectConfigService.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecStorage.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecFileLockManager.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecWorkspaceInitializer.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecYamlCodec.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecValidator.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "WorkflowDomainModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "WorkflowIdGenerator.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
    )

    fun ruleFor(fileName: String): SourceRule? {
        return sourceRules.firstOrNull { it.fileName == fileName }
    }

    fun blockedImportPrefixesFor(fileName: String): Set<String> {
        val custom = ruleFor(fileName)?.blockedImportPrefixes.orEmpty()
        return globalBlockedImportPrefixes + custom
    }
}
