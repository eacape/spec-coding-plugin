package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairApplyResult
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairService
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

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
    ) {
        val repairService = RequirementsSectionRepairService(project)
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = previewTitle,
            task = {
                repairService.previewRepair(
                    workflowId = workflowId,
                    requestedMissingSections = missingSections,
                    confirmedContextOverride = confirmedContextOverride,
                )
            },
            onSuccess = { preview ->
                if (!preview.changed) {
                    SpecWorkflowActionSupport.showInfo(
                        project,
                        SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.title"),
                        SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.message"),
                    )
                    onNoop()
                    return@runBackground
                }
                val dialog = RequirementsSectionRepairPreviewDialog(project, preview)
                if (!dialog.showAndGet()) {
                    onPreviewCancelled()
                    return@runBackground
                }
                SpecWorkflowActionSupport.runBackground(
                    project = project,
                    title = applyTitle,
                    task = { repairService.applyPreview(preview) },
                    onSuccess = { result ->
                        SpecWorkflowActionSupport.notifySuccess(
                            project,
                            SpecCodingBundle.message(
                                "spec.toolwindow.gate.quickFix.aiFill.success",
                                RequirementsSectionSupport.describeSections(
                                    result.preview.patches.map { patch -> patch.sectionId },
                                ),
                            ),
                        )
                        SpecWorkflowActionSupport.openFile(project, result.requirementsDocumentPath)
                        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
                            .onSelectWorkflowRequested(result.workflow.id)
                        onApplied(result)
                    },
                    onFailure = onFailure,
                )
            },
            onFailure = onFailure,
        )
    }
}
