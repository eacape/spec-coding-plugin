package com.eacape.speccodingplugin.ui.spec

import com.intellij.util.messages.Topic

interface SpecToolWindowControlListener {
    fun onCreateWorkflowRequested()

    fun onSelectWorkflowRequested(workflowId: String)

    companion object {
        val TOPIC: Topic<SpecToolWindowControlListener> = Topic.create(
            "SpecCoding.SpecToolWindowControl",
            SpecToolWindowControlListener::class.java,
        )
    }
}
