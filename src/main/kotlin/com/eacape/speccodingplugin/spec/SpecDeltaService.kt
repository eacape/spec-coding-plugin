package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecDeltaService(private val project: Project) {
    private val specEngine: SpecEngine
        get() = SpecEngine.getInstance(project)

    fun compareByWorkflowId(
        baselineWorkflowId: String,
        targetWorkflowId: String,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadWorkflow(baselineWorkflowId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
            )
        }
    }

    companion object {
        fun getInstance(project: Project): SpecDeltaService = project.service()
    }
}

object SpecDeltaCalculator {
    fun compareWorkflows(
        baselineWorkflow: SpecWorkflow,
        targetWorkflow: SpecWorkflow,
    ): SpecWorkflowDelta {
        val phaseDeltas = SpecPhase.entries.map { phase ->
            val baselineDocument = baselineWorkflow.documents[phase]
            val targetDocument = targetWorkflow.documents[phase]

            SpecPhaseDelta(
                phase = phase,
                status = resolveStatus(baselineDocument, targetDocument),
                baselineDocument = baselineDocument,
                targetDocument = targetDocument,
            )
        }

        return SpecWorkflowDelta(
            baselineWorkflowId = baselineWorkflow.id,
            targetWorkflowId = targetWorkflow.id,
            phaseDeltas = phaseDeltas,
        )
    }

    private fun resolveStatus(
        baselineDocument: SpecDocument?,
        targetDocument: SpecDocument?,
    ): SpecDeltaStatus {
        return when {
            baselineDocument == null && targetDocument != null -> SpecDeltaStatus.ADDED
            baselineDocument != null && targetDocument == null -> SpecDeltaStatus.REMOVED
            baselineDocument != null && targetDocument != null -> {
                if (normalizeContent(baselineDocument.content) == normalizeContent(targetDocument.content)) {
                    SpecDeltaStatus.UNCHANGED
                } else {
                    SpecDeltaStatus.MODIFIED
                }
            }

            else -> SpecDeltaStatus.UNCHANGED
        }
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .trim()
    }
}
