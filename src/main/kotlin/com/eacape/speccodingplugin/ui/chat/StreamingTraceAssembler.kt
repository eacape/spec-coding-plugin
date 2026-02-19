package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent

internal class StreamingTraceAssembler {

    data class TraceItem(
        val kind: ExecutionTimelineParser.Kind,
        val status: ExecutionTimelineParser.Status,
        val detail: String,
        val fileAction: WorkflowQuickActionParser.FileAction? = null,
        val isVerbose: Boolean = kind == ExecutionTimelineParser.Kind.THINK ||
            kind == ExecutionTimelineParser.Kind.TOOL ||
            kind == ExecutionTimelineParser.Kind.OUTPUT,
    )

    data class TraceSnapshot(
        val items: List<TraceItem>,
    ) {
        val hasTrace: Boolean
            get() = items.isNotEmpty()
    }

    private val structuredItems = linkedMapOf<String, ExecutionTimelineParser.TimelineItem>()

    fun clear() {
        structuredItems.clear()
    }

    fun onStructuredEvent(event: ChatStreamEvent) {
        mergeItem(ExecutionTimelineParser.fromStructuredEvent(event), structuredItems)
    }

    fun markRunningItemsDone() {
        if (structuredItems.isEmpty()) return
        structuredItems.entries.toList().forEach { (key, item) ->
            if (item.status == ExecutionTimelineParser.Status.RUNNING) {
                structuredItems[key] = item.copy(status = ExecutionTimelineParser.Status.DONE)
            }
        }
    }

    fun snapshot(content: String): TraceSnapshot {
        val merged = linkedMapOf<String, ExecutionTimelineParser.TimelineItem>()

        ExecutionTimelineParser.parse(content).forEach { item ->
            mergeItem(item, merged)
        }
        structuredItems.values.forEach { item ->
            mergeItem(item, merged)
        }

        return TraceSnapshot(
            items = merged.values.map { item ->
                TraceItem(
                    kind = item.kind,
                    status = item.status,
                    detail = item.detail,
                    fileAction = extractFileAction(item),
                )
            }
        )
    }

    private fun extractFileAction(item: ExecutionTimelineParser.TimelineItem): WorkflowQuickActionParser.FileAction? {
        if (item.kind != ExecutionTimelineParser.Kind.EDIT) return null
        val direct = WorkflowQuickActionParser.parse(item.detail).files.firstOrNull()
        if (direct != null) return direct
        return WorkflowQuickActionParser.parse("`${item.detail}`").files.firstOrNull()
    }

    private fun mergeItem(
        item: ExecutionTimelineParser.TimelineItem,
        sink: MutableMap<String, ExecutionTimelineParser.TimelineItem>,
    ) {
        val key = logicalKey(item)
        val existing = sink[key]
        if (existing == null) {
            sink[key] = item
            return
        }

        sink[key] = when {
            statusPriority(item.status) > statusPriority(existing.status) -> item
            statusPriority(item.status) < statusPriority(existing.status) -> existing
            item.detail.length >= existing.detail.length -> item
            else -> existing
        }
    }

    private fun logicalKey(item: ExecutionTimelineParser.TimelineItem): String {
        val normalized = item.detail
            .lowercase()
            .replace(TASK_PROGRESS_PREFIX_REGEX, "")
            .replace(MERGE_NOISE_REGEX, "")
            .trim()
            .ifBlank { "_" }
        return "${item.kind.name}:$normalized"
    }

    private fun statusPriority(status: ExecutionTimelineParser.Status): Int {
        return when (status) {
            ExecutionTimelineParser.Status.ERROR -> 3
            ExecutionTimelineParser.Status.DONE -> 2
            ExecutionTimelineParser.Status.RUNNING -> 1
            ExecutionTimelineParser.Status.INFO -> 0
        }
    }

    companion object {
        private val TASK_PROGRESS_PREFIX_REGEX = Regex("""^\d+\s*/\s*\d+\s*""")
        private val MERGE_NOISE_REGEX =
            Regex("""\b(running|done|completed|finished|success|failed|error|进行中|已完成|完成|失败|错误)\b""")
    }
}
