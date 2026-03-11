package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecDeltaService(private val project: Project) {
    private var _specEngineOverride: SpecEngine? = null
    private var _storageOverride: SpecStorage? = null
    private var _artifactServiceOverride: SpecArtifactService? = null
    private var _workspaceCandidateFilesProviderOverride: (() -> List<String>)? = null

    private val specEngine: SpecEngine
        get() = _specEngineOverride ?: SpecEngine.getInstance(project)

    private val storage: SpecStorage
        get() = _storageOverride ?: SpecStorage.getInstance(project)

    private val artifactService: SpecArtifactService by lazy {
        _artifactServiceOverride ?: SpecArtifactService(project)
    }

    private val workspaceCandidateFilesProvider: () -> List<String>
        get() = _workspaceCandidateFilesProviderOverride ?: {
            runCatching {
                SpecRelatedFilesService.getInstance(project).snapshotWorkspaceCandidatePaths()
            }.getOrDefault(emptyList())
        }

    internal constructor(
        project: Project,
        specEngine: SpecEngine,
        storage: SpecStorage,
        artifactService: SpecArtifactService = SpecArtifactService(project),
        workspaceCandidateFilesProvider: () -> List<String> = { emptyList() },
    ) : this(project) {
        _specEngineOverride = specEngine
        _storageOverride = storage
        _artifactServiceOverride = artifactService
        _workspaceCandidateFilesProviderOverride = workspaceCandidateFilesProvider
    }

    fun compareByWorkflowId(
        baselineWorkflowId: String,
        targetWorkflowId: String,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadWorkflow(baselineWorkflowId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            val delta = SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
                baselineVerificationContent = artifactService.readArtifact(baselineWorkflowId, StageId.VERIFY),
                targetVerificationContent = artifactService.readArtifact(targetWorkflowId, StageId.VERIFY),
                workspaceCandidateFiles = workspaceCandidateFilesProvider(),
            )
            appendBaselineSelectionAudit(
                targetWorkflowId = targetWorkflowId,
                baselineKind = "WORKFLOW",
                baselineWorkflowId = baselineWorkflowId,
                delta = delta,
            )
            delta
        }
    }

    fun compareBySnapshot(
        workflowId: String,
        snapshotId: String,
        targetWorkflowId: String = workflowId,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadWorkflowSnapshot(workflowId, snapshotId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            val delta = SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
                baselineVerificationContent = storage.loadWorkflowSnapshotArtifact(
                    workflowId = workflowId,
                    snapshotId = snapshotId,
                    stageId = StageId.VERIFY,
                ).getOrThrow(),
                targetVerificationContent = artifactService.readArtifact(targetWorkflowId, StageId.VERIFY),
                workspaceCandidateFiles = workspaceCandidateFilesProvider(),
            )
            appendBaselineSelectionAudit(
                targetWorkflowId = targetWorkflowId,
                baselineKind = "SNAPSHOT",
                baselineWorkflowId = workflowId,
                snapshotId = snapshotId,
                delta = delta,
            )
            delta
        }
    }

    fun compareByDeltaBaseline(
        workflowId: String,
        baselineId: String,
        targetWorkflowId: String = workflowId,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadDeltaBaselineWorkflow(workflowId, baselineId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            val delta = SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
                baselineVerificationContent = storage.loadDeltaBaselineArtifact(
                    workflowId = workflowId,
                    baselineId = baselineId,
                    stageId = StageId.VERIFY,
                ).getOrThrow(),
                targetVerificationContent = artifactService.readArtifact(targetWorkflowId, StageId.VERIFY),
                workspaceCandidateFiles = workspaceCandidateFilesProvider(),
            )
            appendBaselineSelectionAudit(
                targetWorkflowId = targetWorkflowId,
                baselineKind = "PINNED_BASELINE",
                baselineWorkflowId = workflowId,
                baselineId = baselineId,
                delta = delta,
            )
            delta
        }
    }

    private fun appendBaselineSelectionAudit(
        targetWorkflowId: String,
        baselineKind: String,
        baselineWorkflowId: String,
        delta: SpecWorkflowDelta,
        snapshotId: String? = null,
        baselineId: String? = null,
    ) {
        val details = linkedMapOf(
            "action" to "COMPARE",
            "baselineKind" to baselineKind,
            "baselineWorkflowId" to baselineWorkflowId,
            "targetWorkflowId" to targetWorkflowId,
            "artifactChangeCount" to delta.artifactDeltas.count { artifact -> artifact.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "taskChangeCount" to delta.taskSummary.changes.size.toString(),
            "relatedFileChangeCount" to delta.relatedFilesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "verificationChanged" to delta.verificationSummary.hasChanges().toString(),
        )
        snapshotId?.let { normalizedSnapshotId ->
            details["snapshotId"] = normalizedSnapshotId
        }
        baselineId?.let { normalizedBaselineId ->
            details["baselineId"] = normalizedBaselineId
        }
        storage.appendAuditEvent(
            workflowId = targetWorkflowId,
            eventType = SpecAuditEventType.DELTA_BASELINE_SELECTED,
            details = details,
        ).getOrThrow()
    }

    companion object {
        fun getInstance(project: Project): SpecDeltaService = project.service()
    }
}

object SpecDeltaCalculator {
    fun compareWorkflows(
        baselineWorkflow: SpecWorkflow,
        targetWorkflow: SpecWorkflow,
        baselineVerificationContent: String? = null,
        targetVerificationContent: String? = null,
        workspaceCandidateFiles: List<String> = emptyList(),
    ): SpecWorkflowDelta {
        val artifactDeltas = buildList {
            add(
                compareArtifact(
                    artifact = SpecDeltaArtifact.REQUIREMENTS,
                    baselineDocument = baselineWorkflow.documents[SpecPhase.SPECIFY],
                    targetDocument = targetWorkflow.documents[SpecPhase.SPECIFY],
                ),
            )
            add(
                compareArtifact(
                    artifact = SpecDeltaArtifact.DESIGN,
                    baselineDocument = baselineWorkflow.documents[SpecPhase.DESIGN],
                    targetDocument = targetWorkflow.documents[SpecPhase.DESIGN],
                ),
            )
            add(
                compareArtifact(
                    artifact = SpecDeltaArtifact.TASKS,
                    baselineDocument = baselineWorkflow.documents[SpecPhase.IMPLEMENT],
                    targetDocument = targetWorkflow.documents[SpecPhase.IMPLEMENT],
                ),
            )

            if (
                baselineVerificationContent != null ||
                targetVerificationContent != null ||
                baselineWorkflow.verifyEnabled ||
                targetWorkflow.verifyEnabled
            ) {
                add(
                    compareArtifact(
                        artifact = SpecDeltaArtifact.VERIFICATION,
                        baselineContent = baselineVerificationContent,
                        targetContent = targetVerificationContent,
                    ),
                )
            }
        }

        val phaseDeltas = artifactDeltas
            .mapNotNull { artifactDelta ->
                artifactDelta.artifact.phase?.let { phase ->
                    SpecPhaseDelta(
                        phase = phase,
                        status = artifactDelta.status,
                        baselineDocument = artifactDelta.baselineDocument,
                        targetDocument = artifactDelta.targetDocument,
                        addedLineCount = artifactDelta.addedLineCount,
                        removedLineCount = artifactDelta.removedLineCount,
                        unifiedDiff = artifactDelta.unifiedDiff,
                    )
                }
            }

        val baselineTasks = parseTasks(baselineWorkflow.documents[SpecPhase.IMPLEMENT]?.content)
        val targetTasks = parseTasks(targetWorkflow.documents[SpecPhase.IMPLEMENT]?.content)

        return SpecWorkflowDelta(
            baselineWorkflowId = baselineWorkflow.id,
            targetWorkflowId = targetWorkflow.id,
            phaseDeltas = phaseDeltas,
            artifactDeltas = artifactDeltas,
            taskSummary = buildTaskSummary(baselineTasks, targetTasks),
            relatedFilesSummary = buildRelatedFilesSummary(
                baselineTasks = baselineTasks,
                targetTasks = targetTasks,
                workspaceCandidateFiles = workspaceCandidateFiles,
            ),
            verificationSummary = buildVerificationSummary(
                baselineTasks = baselineTasks,
                targetTasks = targetTasks,
                baselineVerificationContent = baselineVerificationContent,
                targetVerificationContent = targetVerificationContent,
            ),
        )
    }

    private fun compareArtifact(
        artifact: SpecDeltaArtifact,
        baselineDocument: SpecDocument? = null,
        targetDocument: SpecDocument? = null,
        baselineContent: String? = baselineDocument?.content,
        targetContent: String? = targetDocument?.content,
    ): SpecArtifactDelta {
        val status = resolveStatus(baselineContent, targetContent)
        val diffStats = computeLineDiffStats(baselineContent, targetContent)
        return SpecArtifactDelta(
            artifact = artifact,
            status = status,
            baselineContent = baselineContent,
            targetContent = targetContent,
            baselineDocument = baselineDocument,
            targetDocument = targetDocument,
            addedLineCount = diffStats.addedLines,
            removedLineCount = diffStats.removedLines,
            unifiedDiff = if (status == SpecDeltaStatus.UNCHANGED) {
                ""
            } else {
                buildUnifiedDiff(baselineContent, targetContent)
            },
        )
    }

    private fun resolveStatus(
        baselineContent: String?,
        targetContent: String?,
    ): SpecDeltaStatus {
        return when {
            baselineContent == null && targetContent != null -> SpecDeltaStatus.ADDED
            baselineContent != null && targetContent == null -> SpecDeltaStatus.REMOVED
            baselineContent != null && targetContent != null -> {
                if (normalizeContent(baselineContent) == normalizeContent(targetContent)) {
                    SpecDeltaStatus.UNCHANGED
                } else {
                    SpecDeltaStatus.MODIFIED
                }
            }

            else -> SpecDeltaStatus.UNCHANGED
        }
    }

    private fun buildTaskSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
    ): SpecTaskDeltaSummary {
        val changes = mutableListOf<SpecTaskDelta>()
        val addedTaskIds = mutableListOf<String>()
        val removedTaskIds = mutableListOf<String>()
        val completedTaskIds = mutableListOf<String>()
        val cancelledTaskIds = mutableListOf<String>()
        val statusChangedTaskIds = mutableListOf<String>()
        val metadataChangedTaskIds = mutableListOf<String>()

        (baselineTasks.keys + targetTasks.keys)
            .toSortedSet()
            .forEach { taskId ->
                val baselineTask = baselineTasks[taskId]
                val targetTask = targetTasks[taskId]
                val changedFields = mutableListOf<String>()

                when {
                    baselineTask == null && targetTask != null -> {
                        addedTaskIds += taskId
                        changes += SpecTaskDelta(
                            taskId = taskId,
                            title = targetTask.title,
                            baselineTask = null,
                            targetTask = targetTask,
                            changedFields = emptyList(),
                        )
                    }

                    baselineTask != null && targetTask == null -> {
                        removedTaskIds += taskId
                        changes += SpecTaskDelta(
                            taskId = taskId,
                            title = baselineTask.title,
                            baselineTask = baselineTask,
                            targetTask = null,
                            changedFields = emptyList(),
                        )
                    }

                    baselineTask != null && targetTask != null -> {
                        if (baselineTask.title != targetTask.title) {
                            changedFields += "title"
                        }
                        if (baselineTask.status != targetTask.status) {
                            changedFields += "status"
                        }
                        if (baselineTask.priority != targetTask.priority) {
                            changedFields += "priority"
                        }
                        if (baselineTask.dependsOn != targetTask.dependsOn) {
                            changedFields += "dependsOn"
                        }
                        if (baselineTask.relatedFiles != targetTask.relatedFiles) {
                            changedFields += "relatedFiles"
                        }
                        if (baselineTask.verificationResult != targetTask.verificationResult) {
                            changedFields += "verificationResult"
                        }
                        if (changedFields.isNotEmpty()) {
                            changes += SpecTaskDelta(
                                taskId = taskId,
                                title = targetTask.title,
                                baselineTask = baselineTask,
                                targetTask = targetTask,
                                changedFields = changedFields.toList(),
                            )
                        }
                        if (baselineTask.status != targetTask.status) {
                            statusChangedTaskIds += taskId
                        }
                        if (changedFields.any { field -> field != "status" }) {
                            metadataChangedTaskIds += taskId
                        }
                    }
                }

                if (baselineTask?.status != TaskStatus.COMPLETED && targetTask?.status == TaskStatus.COMPLETED) {
                    completedTaskIds += taskId
                }
                if (baselineTask?.status != TaskStatus.CANCELLED && targetTask?.status == TaskStatus.CANCELLED) {
                    cancelledTaskIds += taskId
                }
            }

        return SpecTaskDeltaSummary(
            changes = changes.sortedBy(SpecTaskDelta::taskId),
            addedTaskIds = addedTaskIds.sorted(),
            removedTaskIds = removedTaskIds.sorted(),
            completedTaskIds = completedTaskIds.sorted(),
            cancelledTaskIds = cancelledTaskIds.sorted(),
            statusChangedTaskIds = statusChangedTaskIds.sorted(),
            metadataChangedTaskIds = metadataChangedTaskIds.sorted(),
        )
    }

    private fun buildRelatedFilesSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
        workspaceCandidateFiles: List<String>,
    ): SpecRelatedFilesDeltaSummary {
        val baselineByFile = indexRelatedFiles(baselineTasks.values)
        val targetByFile = indexRelatedFiles(targetTasks.values)
        val normalizedWorkspaceFiles = workspaceCandidateFiles
            .mapNotNull(::normalizePath)
            .distinct()
            .sorted()
        val workspaceSet = normalizedWorkspaceFiles.toSet()

        val files = (baselineByFile.keys + targetByFile.keys + workspaceSet)
            .toSortedSet()
            .map { path ->
                val baselineTaskIds = baselineByFile[path].orEmpty()
                val targetTaskIds = targetByFile[path].orEmpty()
                val presentInWorkspace = workspaceSet.contains(path)
                val status = when {
                    baselineTaskIds.isEmpty() && targetTaskIds.isNotEmpty() -> SpecDeltaStatus.ADDED
                    baselineTaskIds.isNotEmpty() && targetTaskIds.isEmpty() -> SpecDeltaStatus.REMOVED
                    baselineTaskIds != targetTaskIds || presentInWorkspace -> SpecDeltaStatus.MODIFIED
                    else -> SpecDeltaStatus.UNCHANGED
                }
                SpecRelatedFileDelta(
                    path = path,
                    status = status,
                    baselineTaskIds = baselineTaskIds,
                    targetTaskIds = targetTaskIds,
                    presentInWorkspace = presentInWorkspace,
                )
            }

        return SpecRelatedFilesDeltaSummary(
            files = files,
            workspaceCandidateFiles = normalizedWorkspaceFiles,
        )
    }

    private fun buildVerificationSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
        baselineVerificationContent: String?,
        targetVerificationContent: String?,
    ): SpecVerificationDeltaSummary {
        val taskResultChanges = (baselineTasks.keys + targetTasks.keys)
            .toSortedSet()
            .map { taskId ->
                SpecTaskVerificationDelta(
                    taskId = taskId,
                    baselineResult = baselineTasks[taskId]?.verificationResult,
                    targetResult = targetTasks[taskId]?.verificationResult,
                )
            }
            .filter { change -> change.status != SpecDeltaStatus.UNCHANGED }

        return SpecVerificationDeltaSummary(
            baselineArtifact = parseVerificationArtifactSummary(baselineVerificationContent),
            targetArtifact = parseVerificationArtifactSummary(targetVerificationContent),
            taskResultChanges = taskResultChanges,
        )
    }

    private fun parseTasks(markdown: String?): Map<String, StructuredTask> {
        if (markdown.isNullOrBlank()) {
            return emptyMap()
        }
        return SpecTaskMarkdownParser.parse(markdown).tasks
            .mapNotNull { entry -> entry.toStructuredTaskOrNull() }
            .sortedBy(StructuredTask::id)
            .associateBy(StructuredTask::id)
    }

    private fun indexRelatedFiles(tasks: Collection<StructuredTask>): Map<String, List<String>> {
        val fileToTasks = linkedMapOf<String, MutableSet<String>>()
        tasks.forEach { task ->
            task.relatedFiles.forEach { rawPath ->
                val normalizedPath = normalizePath(rawPath) ?: return@forEach
                fileToTasks.getOrPut(normalizedPath) { linkedSetOf() }.add(task.id)
            }
        }
        return fileToTasks.mapValues { (_, taskIds) -> taskIds.toList().sorted() }
    }

    private fun parseVerificationArtifactSummary(content: String?): SpecVerificationArtifactSummary {
        if (content.isNullOrBlank()) {
            return SpecVerificationArtifactSummary(documentAvailable = false)
        }
        val normalized = normalizeContent(content)
        val lines = normalized.lines()
        val resultHeaderIndex = lines.indexOfFirst { line -> line.trim() == "## Result" }
        if (resultHeaderIndex < 0) {
            return SpecVerificationArtifactSummary(
                documentAvailable = true,
                summary = normalized.lineSequence().firstOrNull().orEmpty(),
            )
        }

        val yamlBlock = lines
            .drop(resultHeaderIndex + 1)
            .dropWhile(String::isBlank)
            .takeWhile { line ->
                val trimmed = line.trim()
                !(trimmed.startsWith("# ") || trimmed.startsWith("## "))
            }
            .joinToString("\n")
            .trim()

        if (yamlBlock.isBlank()) {
            return SpecVerificationArtifactSummary(documentAvailable = true)
        }

        val result = runCatching { SpecYamlCodec.decodeMap(yamlBlock) }.getOrDefault(emptyMap())
        return SpecVerificationArtifactSummary(
            documentAvailable = true,
            conclusion = parseVerificationConclusion(result["conclusion"]),
            runId = result["runId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            executedAt = result["at"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            summary = result["summary"]?.toString()?.trim().orEmpty(),
        )
    }

    private fun parseVerificationConclusion(raw: Any?): VerificationConclusion? {
        val normalized = raw?.toString()?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return VerificationConclusion.entries.firstOrNull { conclusion -> conclusion.name == normalized }
    }

    private data class LineDiffStats(
        val addedLines: Int,
        val removedLines: Int,
    )

    private fun computeLineDiffStats(
        baselineContent: String?,
        targetContent: String?,
    ): LineDiffStats {
        val baselineLines = normalizeLines(baselineContent)
        val targetLines = normalizeLines(targetContent)
        if (baselineLines.isEmpty() && targetLines.isEmpty()) {
            return LineDiffStats(0, 0)
        }
        val lcs = lcsLength(baselineLines, targetLines)
        return LineDiffStats(
            addedLines = (targetLines.size - lcs).coerceAtLeast(0),
            removedLines = (baselineLines.size - lcs).coerceAtLeast(0),
        )
    }

    private fun buildUnifiedDiff(
        baselineContent: String?,
        targetContent: String?,
    ): String {
        val baselineLines = normalizeLines(baselineContent)
        val targetLines = normalizeLines(targetContent)
        if (baselineLines.isEmpty() && targetLines.isEmpty()) {
            return ""
        }
        if (baselineLines.isEmpty()) {
            return targetLines.joinToString("\n") { line -> "+ $line" }
        }
        if (targetLines.isEmpty()) {
            return baselineLines.joinToString("\n") { line -> "- $line" }
        }

        val rows = baselineLines.size
        val cols = targetLines.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (row in 1..rows) {
            for (col in 1..cols) {
                dp[row][col] = if (baselineLines[row - 1] == targetLines[col - 1]) {
                    dp[row - 1][col - 1] + 1
                } else {
                    maxOf(dp[row - 1][col], dp[row][col - 1])
                }
            }
        }

        val operations = mutableListOf<String>()
        var row = rows
        var col = cols
        while (row > 0 || col > 0) {
            when {
                row > 0 && col > 0 && baselineLines[row - 1] == targetLines[col - 1] -> {
                    operations += "  ${baselineLines[row - 1]}"
                    row -= 1
                    col -= 1
                }

                col > 0 && (row == 0 || dp[row][col - 1] >= dp[row - 1][col]) -> {
                    operations += "+ ${targetLines[col - 1]}"
                    col -= 1
                }

                else -> {
                    operations += "- ${baselineLines[row - 1]}"
                    row -= 1
                }
            }
        }
        return operations
            .asReversed()
            .joinToString("\n")
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun normalizeLines(content: String?): List<String> {
        if (content.isNullOrBlank()) {
            return emptyList()
        }
        val normalized = normalizeContent(content)
        if (normalized.isBlank()) {
            return emptyList()
        }
        return normalized.split('\n')
    }

    private fun lcsLength(
        baselineLines: List<String>,
        targetLines: List<String>,
    ): Int {
        val rows = baselineLines.size
        val cols = targetLines.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (row in 1..rows) {
            for (col in 1..cols) {
                dp[row][col] = if (baselineLines[row - 1] == targetLines[col - 1]) {
                    dp[row - 1][col - 1] + 1
                } else {
                    maxOf(dp[row - 1][col], dp[row][col - 1])
                }
            }
        }
        return dp[rows][cols]
    }

    private fun normalizePath(rawPath: String): String? {
        val normalized = rawPath.trim()
            .replace('\\', '/')
            .trim('/')
        return normalized.takeIf { it.isNotEmpty() }
    }
}
