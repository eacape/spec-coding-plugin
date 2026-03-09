package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageTransitionBlockedByGateError
import com.eacape.speccodingplugin.spec.StageWarningConfirmationRequiredError
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.spec.SpecToolWindowControlListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedEvent
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.ToolWindowManager
import java.util.Locale

internal object SpecWorkflowActionSupport {
    private const val TOOL_WINDOW_ID = "Spec Code"
    private const val MAX_GATE_VIOLATIONS = 5

    fun showCreateWorkflow(project: Project) {
        openSpecTab(project) {
            project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
                .onCreateWorkflowRequested()
        }
    }

    fun showWorkflow(project: Project, workflowId: String) {
        val normalizedWorkflowId = workflowId.trim()
        if (normalizedWorkflowId.isEmpty()) return
        openSpecTab(project) {
            project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
                .onSelectWorkflowRequested(normalizedWorkflowId)
            project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC)
                .onWorkflowChanged(
                    SpecWorkflowChangedEvent(
                        workflowId = normalizedWorkflowId,
                        reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
                    ),
                )
        }
    }

    fun <T> runBackground(
        project: Project,
        title: String,
        task: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = { error ->
            showErrorDialog(
                project = project,
                title = title,
                message = describeFailure(error),
            )
        },
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, false) {
                private var outcome: Result<T>? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    outcome = runCatching(task)
                }

                override fun onSuccess() {
                    outcome
                        ?.onSuccess(onSuccess)
                        ?.onFailure(onFailure)
                }
            },
        )
    }

    fun chooseWorkflow(
        project: Project,
        workflows: List<WorkflowMeta>,
        title: String,
        onChosen: (WorkflowMeta) -> Unit,
    ) {
        showSelectionPopup(
            project = project,
            title = title,
            items = workflows,
            text = ::workflowLabel,
            onChosen = onChosen,
        )
    }

    fun chooseStage(
        project: Project,
        stages: List<StageId>,
        title: String,
        workflowMeta: WorkflowMeta,
        onChosen: (StageId) -> Unit,
    ) {
        showSelectionPopup(
            project = project,
            title = title,
            items = stages,
            text = { stage -> stageChoiceLabel(stage, workflowMeta) },
            onChosen = onChosen,
        )
    }

    fun confirmWarnings(project: Project, gateResult: GateResult): Boolean {
        return Messages.showYesNoDialog(
            project,
            gateSummary(gateResult),
            SpecCodingBundle.message("spec.action.warning.title"),
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    fun showGateBlocked(project: Project, gateResult: GateResult) {
        showErrorDialog(
            project = project,
            title = SpecCodingBundle.message("spec.action.gate.blocked.title"),
            message = gateSummary(gateResult),
        )
    }

    fun notifySuccess(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(
                SpecCodingBundle.message("spec.action.notification.title"),
                message,
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    fun showInfo(project: Project, title: String, message: String) {
        Messages.showInfoMessage(project, message, title)
    }

    fun jumpTargets(meta: WorkflowMeta): List<StageId> {
        return StageId.entries.filter { stage ->
            stage.ordinal > meta.currentStage.ordinal && meta.stageStates[stage]?.active == true
        }
    }

    fun rollbackTargets(meta: WorkflowMeta): List<StageId> {
        return StageId.entries.filter { stage ->
            stage.ordinal < meta.currentStage.ordinal &&
                meta.stageStates[stage]?.active == true &&
                meta.stageStates[stage]?.status == StageProgress.DONE
        }
    }

    fun workflowLabel(meta: WorkflowMeta): String {
        val title = meta.title?.trim().orEmpty().ifBlank { meta.workflowId }
        return SpecCodingBundle.message(
            "spec.action.workflow.choice",
            title,
            meta.workflowId,
            stageLabel(meta.currentStage),
        )
    }

    fun stageChoiceLabel(stage: StageId, meta: WorkflowMeta): String {
        val state = meta.stageStates[stage]
        val statusText = when {
            stage == meta.currentStage -> SpecCodingBundle.message("spec.action.stage.state.current")
            state?.status == StageProgress.DONE -> SpecCodingBundle.message("spec.action.stage.state.done")
            state?.status == StageProgress.IN_PROGRESS -> SpecCodingBundle.message("spec.action.stage.state.inProgress")
            else -> SpecCodingBundle.message("spec.action.stage.state.pending")
        }
        return SpecCodingBundle.message(
            "spec.action.stage.choice",
            stageLabel(stage),
            statusText,
        )
    }

    fun stageLabel(stageId: StageId): String {
        return stageId.name
            .lowercase(Locale.ROOT)
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
    }

    fun gateSummary(gateResult: GateResult, limit: Int = MAX_GATE_VIOLATIONS): String {
        if (gateResult.violations.isEmpty()) {
            return gateResult.aggregation.summary
        }
        val lines = mutableListOf<String>()
        lines += gateResult.aggregation.summary
        lines += ""
        gateResult.violations.take(limit).forEach { violation ->
            lines += formatViolation(violation)
            violation.fixHint
                ?.takeIf { it.isNotBlank() }
                ?.let { fixHint -> lines += SpecCodingBundle.message("spec.action.gate.fix", fixHint) }
        }
        val remaining = gateResult.violations.size - limit
        if (remaining > 0) {
            lines += SpecCodingBundle.message("spec.action.gate.more", remaining)
        }
        return lines.joinToString("\n").trim()
    }

    fun describeFailure(error: Throwable): String {
        return when (error) {
            is StageTransitionBlockedByGateError -> gateSummary(error.gateResult)
            is StageWarningConfirmationRequiredError -> gateSummary(error.gateResult)
            else -> error.message ?: SpecCodingBundle.message("common.unknown")
        }
    }

    private fun formatViolation(violation: Violation): String {
        val severity = when (violation.severity) {
            GateStatus.ERROR -> SpecCodingBundle.message("spec.action.gate.severity.error")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.action.gate.severity.warning")
            GateStatus.PASS -> SpecCodingBundle.message("spec.action.gate.severity.pass")
        }
        return SpecCodingBundle.message(
            "spec.action.gate.violation",
            severity,
            violation.ruleId,
            violation.fileName,
            violation.line,
            violation.message,
        )
    }

    private fun openSpecTab(project: Project, afterOpen: () -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            val specTitle = SpecCodingBundle.message("spec.tab.title")
            toolWindow.contentManager.contents
                .firstOrNull { it.displayName == specTitle }
                ?.let(toolWindow.contentManager::setSelectedContent)
            toolWindow.activate(null)
            ApplicationManager.getApplication().invokeLater(afterOpen)
        }
    }

    private fun showErrorDialog(project: Project, title: String, message: String) {
        Messages.showErrorDialog(project, message, title)
    }

    private fun <T> showSelectionPopup(
        project: Project,
        title: String,
        items: List<T>,
        text: (T) -> String,
        onChosen: (T) -> Unit,
    ) {
        when (items.size) {
            0 -> return
            1 -> {
                onChosen(items.first())
                return
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<T>(title, items) {
                override fun getTextFor(value: T): String = text(value)

                override fun isSpeedSearchEnabled(): Boolean = true

                override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) {
                        ApplicationManager.getApplication().invokeLater {
                            onChosen(selectedValue)
                        }
                    }
                    return PopupStep.FINAL_CHOICE
                }
            },
        )
        popup.showCenteredInCurrentWindow(project)
    }
}
