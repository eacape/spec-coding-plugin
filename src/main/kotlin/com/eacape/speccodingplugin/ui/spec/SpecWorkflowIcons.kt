package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.TaskStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal enum class SpecWorkflowActionIcon(
    val debugId: String,
    val icon: Icon,
) {
    ADVANCE("advance", AllIcons.General.ArrowRight),
    NEXT_STAGE(
        "nextStage",
        IconLoader.getIcon("/icons/spec-workflow-next-stage.svg", SpecWorkflowActionIcon::class.java),
    ),
    EXECUTE("execute", AllIcons.Actions.Execute),
    RETRY("refresh", AllIcons.General.InlineRefresh),
    COMPLETE("complete", AllIcons.General.GreenCheckmark),
    PAUSE("pause", AllIcons.General.InspectionsPause),
    RESUME("resume", AllIcons.Actions.Resume),
    OPEN_DOCUMENT("openDocument", AllIcons.Actions.MenuOpen),
    HISTORY("history", AllIcons.Vcs.HistoryInline),
    SAVE("save", AllIcons.General.OpenDisk),
    EDIT("edit", AllIcons.Actions.Edit),
    RELATED_FILES_EDIT(
        "relatedFilesEdit",
        IconLoader.getIcon("/icons/spec-task-related-files-edit.svg", SpecWorkflowActionIcon::class.java),
    ),
    ADD("add", AllIcons.General.Add),
    BACK("back", AllIcons.Actions.Back),
    FORWARD("forward", AllIcons.Actions.Forward),
    CLONE("clone", AllIcons.Actions.Copy),
    OVERFLOW("overflow", AllIcons.General.GearPlain),
    CANCEL("close", AllIcons.Actions.Close),
    BRANCH("branch", AllIcons.Vcs.Branch),
    OPEN_TOOL_WINDOW("openToolWindow", AllIcons.General.OpenInToolWindow),
}

internal object SpecWorkflowIcons {
    val Advance: Icon = SpecWorkflowActionIcon.ADVANCE.icon
    val NextStage: Icon = SpecWorkflowActionIcon.NEXT_STAGE.icon
    val Execute: Icon = SpecWorkflowActionIcon.EXECUTE.icon
    val Refresh: Icon = SpecWorkflowActionIcon.RETRY.icon
    val Complete: Icon = SpecWorkflowActionIcon.COMPLETE.icon
    val Pause: Icon = SpecWorkflowActionIcon.PAUSE.icon
    val Resume: Icon = SpecWorkflowActionIcon.RESUME.icon
    val OpenDocument: Icon = SpecWorkflowActionIcon.OPEN_DOCUMENT.icon
    val History: Icon = SpecWorkflowActionIcon.HISTORY.icon
    val Save: Icon = SpecWorkflowActionIcon.SAVE.icon
    val Edit: Icon = SpecWorkflowActionIcon.EDIT.icon
    val RelatedFilesEdit: Icon = SpecWorkflowActionIcon.RELATED_FILES_EDIT.icon
    val Add: Icon = SpecWorkflowActionIcon.ADD.icon
    val Back: Icon = SpecWorkflowActionIcon.BACK.icon
    val Forward: Icon = SpecWorkflowActionIcon.FORWARD.icon
    val Clone: Icon = SpecWorkflowActionIcon.CLONE.icon
    val Overflow: Icon = SpecWorkflowActionIcon.OVERFLOW.icon
    val Close: Icon = SpecWorkflowActionIcon.CANCEL.icon
    val Branch: Icon = SpecWorkflowActionIcon.BRANCH.icon
    val OpenToolWindow: Icon = SpecWorkflowActionIcon.OPEN_TOOL_WINDOW.icon

    fun icon(action: SpecWorkflowActionIcon): Icon = action.icon

    fun workbenchAction(kind: SpecWorkflowWorkbenchActionKind): Icon {
        return when (kind) {
            SpecWorkflowWorkbenchActionKind.ADVANCE -> NextStage
            SpecWorkflowWorkbenchActionKind.JUMP -> Advance

            SpecWorkflowWorkbenchActionKind.ROLLBACK -> Back
            SpecWorkflowWorkbenchActionKind.START_TASK,
            SpecWorkflowWorkbenchActionKind.RUN_VERIFY,
            SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN,
            -> Execute

            SpecWorkflowWorkbenchActionKind.RESUME_TASK -> Refresh
            SpecWorkflowWorkbenchActionKind.STOP_TASK_EXECUTION -> Close
            SpecWorkflowWorkbenchActionKind.COMPLETE_TASK,
            SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW,
            -> Complete

            SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION -> OpenDocument
            SpecWorkflowWorkbenchActionKind.SHOW_DELTA -> History
            SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW -> Save
        }
    }

    fun taskPrimaryAction(displayStatus: TaskStatus): Icon {
        return when (displayStatus) {
            TaskStatus.PENDING -> Execute
            TaskStatus.BLOCKED -> Refresh
            TaskStatus.IN_PROGRESS,
            TaskStatus.COMPLETED,
            -> Complete

            TaskStatus.CANCELLED -> Close
        }
    }

    fun debugId(icon: Icon?): String {
        return when (icon) {
            null -> ""
            else -> SpecWorkflowActionIcon.entries
                .firstOrNull { action -> action.icon == icon }
                ?.debugId
                ?: icon::class.java.name
        }
    }
}
