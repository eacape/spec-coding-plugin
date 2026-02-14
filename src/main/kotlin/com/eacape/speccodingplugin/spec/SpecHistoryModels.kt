package com.eacape.speccodingplugin.spec

data class SpecDocumentHistoryEntry(
    val snapshotId: String,
    val phase: SpecPhase,
    val createdAt: Long,
)
