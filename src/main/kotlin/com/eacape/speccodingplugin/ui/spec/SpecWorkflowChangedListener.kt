package com.eacape.speccodingplugin.ui.spec

import com.intellij.util.messages.Topic

data class SpecWorkflowChangedEvent(
    val workflowId: String?,
    val reason: String,
)

interface SpecWorkflowChangedListener {
    fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {}

    companion object {
        val TOPIC: Topic<SpecWorkflowChangedListener> = Topic.create(
            "SpecCoding.SpecWorkflowChanged",
            SpecWorkflowChangedListener::class.java,
        )
    }
}
