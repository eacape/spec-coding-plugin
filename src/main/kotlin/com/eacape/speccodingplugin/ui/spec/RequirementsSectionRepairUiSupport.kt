package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairPreview
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairApplyResult
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairService
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Path

internal typealias RequirementsSectionRepairPreviewRunner = (
    title: String,
    task: () -> RequirementsSectionRepairPreview,
    onSuccess: (RequirementsSectionRepairPreview) -> Unit,
    onFailure: (Throwable) -> Unit,
) -> Unit

internal typealias RequirementsSectionRepairApplyRunner = (
    title: String,
    task: () -> RequirementsSectionRepairApplyResult,
    onSuccess: (RequirementsSectionRepairApplyResult) -> Unit,
    onFailure: (Throwable) -> Unit,
) -> Unit

internal object RequirementsSectionRepairUiSupport {

    fun previewAndApply(
        project: Project,
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
        confirmedContextOverride: String? = null,
        previewTitle: String = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.progress.preview"),
        applyTitle: String = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.progress.apply"),
        onPreviewCancelled: () -> Unit = {},
        onNoop: () -> Unit = {},
        onApplied: (RequirementsSectionRepairApplyResult) -> Unit = {},
        onFailure: (Throwable) -> Unit = { error ->
            Messages.showErrorDialog(project, SpecWorkflowActionSupport.describeFailure(error), previewTitle)
        },
        repairServiceFactory: () -> RequirementsSectionRepairService = { RequirementsSectionRepairService(project) },
        previewRunner: RequirementsSectionRepairPreviewRunner = { title, task, onSuccess, taskFailure ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = title,
                task = task,
                onSuccess = onSuccess,
                onFailure = taskFailure,
            )
        },
        applyRunner: RequirementsSectionRepairApplyRunner = { title, task, onSuccess, taskFailure ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = title,
                task = task,
                onSuccess = onSuccess,
                onFailure = taskFailure,
            )
        },
        previewDialogPresenter: (RequirementsSectionRepairPreview) -> Boolean = { preview ->
            RequirementsSectionRepairPreviewDialog(project, preview).showAndGet()
        },
        showInfo: (String, String) -> Unit = { title, message ->
            SpecWorkflowActionSupport.showInfo(project, title, message)
        },
        notifySuccess: (String) -> Unit = { message ->
            SpecWorkflowActionSupport.notifySuccess(project, message)
        },
        openRequirementsDocument: (Path) -> Unit = { path ->
            SpecWorkflowActionSupport.openFile(project, path)
        },
        selectWorkflow: (String) -> Unit = { workflowIdToSelect ->
            project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
                .onSelectWorkflowRequested(workflowIdToSelect)
        },
    ) {
        val repairService = repairServiceFactory()
        previewRunner(
            previewTitle,
            {
                repairService.previewRepair(
                    workflowId = workflowId,
                    requestedMissingSections = missingSections,
                    confirmedContextOverride = confirmedContextOverride,
                )
            },
            { preview ->
                if (!preview.changed) {
                    showInfo(
                        SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.title"),
                        SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.message"),
                    )
                    onNoop()
                } else if (!previewDialogPresenter(preview)) {
                    onPreviewCancelled()
                } else {
                    applyRunner(
                        applyTitle,
                        { repairService.applyPreview(preview) },
                        { result ->
                            notifySuccess(
                                SpecCodingBundle.message(
                                    "spec.toolwindow.gate.quickFix.aiFill.success",
                                    RequirementsSectionSupport.describeSections(
                                        result.preview.patches.map { patch -> patch.sectionId },
                                    ),
                                ),
                            )
                            openRequirementsDocument(result.requirementsDocumentPath)
                            selectWorkflow(result.workflow.id)
                            onApplied(result)
                        },
                        onFailure,
                    )
                }
            },
            onFailure,
        )
    }
}
