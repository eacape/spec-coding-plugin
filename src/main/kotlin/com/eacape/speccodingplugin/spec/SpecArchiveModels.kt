package com.eacape.speccodingplugin.spec

import java.nio.file.Path

data class SpecArchiveResult(
    val workflowId: String,
    val archiveId: String,
    val archivePath: Path,
    val auditLogPath: Path,
)

enum class SpecAuditEventType {
    WORKFLOW_SAVED,
    DOCUMENT_SAVED,
    SNAPSHOT_DELETED,
    HISTORY_PRUNED,
    WORKFLOW_DELETED,
    WORKFLOW_ARCHIVED,
}
