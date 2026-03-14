package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.intellij.util.messages.Topic

data class SpecToolWindowOpenRequest(
    val workflowId: String,
    val taskId: String? = null,
    val focusedStage: StageId? = null,
)

interface SpecToolWindowControlListener {
    fun onCreateWorkflowRequested()

    fun onSelectWorkflowRequested(workflowId: String)

    fun onOpenWorkflowRequested(request: SpecToolWindowOpenRequest) {
        onSelectWorkflowRequested(request.workflowId)
    }

    companion object {
        val TOPIC: Topic<SpecToolWindowControlListener> = Topic.create(
            "SpecCoding.SpecToolWindowControl",
            SpecToolWindowControlListener::class.java,
        )
    }
}
