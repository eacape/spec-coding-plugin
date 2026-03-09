package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecVerificationService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.VerifyPlan
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowSelectionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class RunSpecWorkflowVerificationAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val specEngine = SpecEngine.getInstance(project)
        val verificationService = SpecVerificationService.getInstance(project)
        val tasksService = SpecTasksService.getInstance(project)
        val selectedWorkflowId = SpecWorkflowSelectionService.getInstance(project).currentWorkflowId()

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.loading"),
            task = {
                specEngine.listWorkflowMetadata()
                    .filter(::supportsVerify)
                    .sortedByDescending(WorkflowMeta::updatedAt)
            },
            onSuccess = { workflows ->
                if (workflows.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project,
                        SpecCodingBundle.message("spec.action.verify.empty.title"),
                        SpecCodingBundle.message("spec.action.verify.empty.message"),
                    )
                    return@runBackground
                }

                val selectedWorkflow = selectedWorkflowId?.let { workflowId ->
                    workflows.firstOrNull { it.workflowId == workflowId }
                } ?: workflows.singleOrNull()

                if (selectedWorkflow != null) {
                    previewAndRun(project, verificationService, tasksService, selectedWorkflow.workflowId)
                } else {
                    SpecWorkflowActionSupport.chooseWorkflow(
                        project = project,
                        workflows = workflows,
                        title = SpecCodingBundle.message("spec.action.verify.workflow.popup.title"),
                        onChosen = { workflow ->
                            previewAndRun(project, verificationService, tasksService, workflow.workflowId)
                        },
                    )
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun previewAndRun(
        project: com.intellij.openapi.project.Project,
        verificationService: SpecVerificationService,
        tasksService: SpecTasksService,
        workflowId: String,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.preview"),
            task = {
                val plan = verificationService.preview(workflowId)
                VerificationActionContext(
                    plan = plan,
                    scopeTasks = resolveScopeTasks(tasksService.parse(workflowId)),
                )
            },
            onSuccess = { context ->
                SpecWorkflowActionSupport.rememberWorkflow(project, workflowId)
                if (!SpecWorkflowActionSupport.confirmVerificationPlan(project, context.plan, context.scopeTasks)) {
                    return@runBackground
                }
                executeVerification(project, verificationService, workflowId, context)
            },
        )
    }

    private fun executeVerification(
        project: com.intellij.openapi.project.Project,
        verificationService: SpecVerificationService,
        workflowId: String,
        context: VerificationActionContext,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.executing"),
            task = {
                verificationService.run(
                    workflowId = workflowId,
                    planId = context.plan.planId,
                    scopeTaskIds = context.scopeTasks.map(StructuredTask::id),
                )
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.rememberWorkflow(project, workflowId)
                SpecWorkflowActionSupport.showVerificationRunResult(project, result)
            },
        )
    }

    private fun resolveScopeTasks(tasks: List<StructuredTask>): List<StructuredTask> {
        return tasks.sortedBy(StructuredTask::id)
    }

    private fun supportsVerify(workflow: WorkflowMeta): Boolean {
        return workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true
    }

    private data class VerificationActionContext(
        val plan: VerifyPlan,
        val scopeTasks: List<StructuredTask>,
    )
}
