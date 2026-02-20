package com.eacape.speccodingplugin.ui

import com.intellij.util.messages.Topic

interface ChatToolWindowControlListener {
    fun onNewSessionRequested()

    fun onOpenHistoryRequested()

    companion object {
        val TOPIC: Topic<ChatToolWindowControlListener> = Topic.create(
            "SpecCoding.ChatToolWindowControl",
            ChatToolWindowControlListener::class.java,
        )
    }
}

