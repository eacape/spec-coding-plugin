package com.eacape.speccodingplugin.spec

enum class SpecDeltaStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    UNCHANGED,
}

data class SpecPhaseDelta(
    val phase: SpecPhase,
    val status: SpecDeltaStatus,
    val baselineDocument: SpecDocument?,
    val targetDocument: SpecDocument?,
)

data class SpecWorkflowDelta(
    val baselineWorkflowId: String,
    val targetWorkflowId: String,
    val phaseDeltas: List<SpecPhaseDelta>,
) {
    fun count(status: SpecDeltaStatus): Int {
        return phaseDeltas.count { it.status == status }
    }

    fun hasChanges(): Boolean {
        return phaseDeltas.any { it.status != SpecDeltaStatus.UNCHANGED }
    }
}
