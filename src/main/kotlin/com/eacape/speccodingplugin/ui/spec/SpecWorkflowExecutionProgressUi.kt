package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import java.time.Duration
import java.time.Instant

internal data class SpecWorkflowExecutionProgressPresentation(
    val phase: ExecutionLivePhase,
    val phaseLabel: String,
    val chipLabel: String,
    val elapsedText: String,
    val lastActivityText: String,
    val detailText: String,
)

internal object SpecWorkflowExecutionProgressUi {
    fun resolve(
        task: StructuredTask,
        liveProgress: TaskExecutionLiveProgress?,
        now: Instant = Instant.now(),
    ): SpecWorkflowExecutionProgressPresentation? {
        val snapshot = resolveSnapshot(task, liveProgress) ?: return null
        return SpecWorkflowExecutionProgressPresentation(
            phase = snapshot.phase,
            phaseLabel = phaseLabel(snapshot.phase),
            chipLabel = chipLabel(snapshot.phase),
            elapsedText = formatDuration(now, snapshot.startedAt),
            lastActivityText = formatAge(now, snapshot.lastUpdatedAt),
            detailText = snapshot.detailText,
        )
    }

    private fun resolveSnapshot(
        task: StructuredTask,
        liveProgress: TaskExecutionLiveProgress?,
    ): ProgressSnapshot? {
        val normalizedLiveProgress = liveProgress?.takeIf { it.phase != ExecutionLivePhase.TERMINAL }
        if (normalizedLiveProgress != null) {
            return ProgressSnapshot(
                phase = normalizedLiveProgress.phase,
                startedAt = normalizedLiveProgress.startedAt,
                lastUpdatedAt = normalizedLiveProgress.lastUpdatedAt,
                detailText = normalizedLiveProgress.lastDetail
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: defaultDetail(normalizedLiveProgress.phase),
            )
        }

        val activeRun = task.activeExecutionRun ?: return null
        if (activeRun.status.isTerminal()) {
            return null
        }
        val phase = when (activeRun.status) {
            TaskExecutionRunStatus.QUEUED -> ExecutionLivePhase.QUEUED
            TaskExecutionRunStatus.RUNNING -> ExecutionLivePhase.REQUEST_DISPATCHED
            TaskExecutionRunStatus.WAITING_CONFIRMATION -> ExecutionLivePhase.WAITING_CONFIRMATION
            TaskExecutionRunStatus.FAILED,
            TaskExecutionRunStatus.CANCELLED,
            TaskExecutionRunStatus.SUCCEEDED,
            -> return null
        }
        val startedAt = parseInstant(activeRun.startedAt)
        return ProgressSnapshot(
            phase = phase,
            startedAt = startedAt,
            lastUpdatedAt = parseInstant(activeRun.finishedAt ?: activeRun.startedAt),
            detailText = activeRun.summary?.trim()?.takeIf(String::isNotBlank) ?: defaultDetail(phase),
        )
    }

    private fun phaseLabel(phase: ExecutionLivePhase): String {
        return when (phase) {
            ExecutionLivePhase.QUEUED ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.queued")

            ExecutionLivePhase.PREPARING_CONTEXT ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.preparingContext")

            ExecutionLivePhase.REQUEST_DISPATCHED ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.requestDispatched")

            ExecutionLivePhase.STREAMING ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.streaming")

            ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.waitingConfirmation")

            ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.cancelling")

            ExecutionLivePhase.TERMINAL ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.terminal")
        }
    }

    private fun chipLabel(phase: ExecutionLivePhase): String {
        return when (phase) {
            ExecutionLivePhase.QUEUED ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.queued")

            ExecutionLivePhase.PREPARING_CONTEXT ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.preparingContext")

            ExecutionLivePhase.REQUEST_DISPATCHED,
            ExecutionLivePhase.STREAMING,
            -> SpecCodingBundle.message("spec.toolwindow.execution.chip.running")

            ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.waitingConfirmation")

            ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.cancelling")

            ExecutionLivePhase.TERMINAL ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.terminal")
        }
    }

    private fun defaultDetail(phase: ExecutionLivePhase): String {
        return when (phase) {
            ExecutionLivePhase.QUEUED ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.queued")

            ExecutionLivePhase.PREPARING_CONTEXT ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.preparingContext")

            ExecutionLivePhase.REQUEST_DISPATCHED ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.requestDispatched")

            ExecutionLivePhase.STREAMING ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.streaming")

            ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.waitingConfirmation")

            ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.cancelling")

            ExecutionLivePhase.TERMINAL ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.terminal")
        }
    }

    private fun formatDuration(
        now: Instant,
        startedAt: Instant,
    ): String {
        val duration = Duration.between(startedAt, now).coerceAtLeast(Duration.ZERO)
        val totalSeconds = duration.seconds
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun formatAge(
        now: Instant,
        at: Instant,
    ): String {
        val duration = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        val totalSeconds = duration.seconds
        return when {
            totalSeconds >= 86_400 -> "${totalSeconds / 86_400}d ago"
            totalSeconds >= 3_600 -> "${totalSeconds / 3_600}h ago"
            totalSeconds >= 60 -> "${totalSeconds / 60}m ago"
            else -> "${totalSeconds}s ago"
        }
    }

    private fun parseInstant(value: String?): Instant {
        val normalized = value?.trim().orEmpty()
        return runCatching { Instant.parse(normalized) }.getOrElse { Instant.now() }
    }

    private data class ProgressSnapshot(
        val phase: ExecutionLivePhase,
        val startedAt: Instant,
        val lastUpdatedAt: Instant,
        val detailText: String,
    )
}
