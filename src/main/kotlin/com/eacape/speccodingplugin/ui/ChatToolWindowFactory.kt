package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.ui.chat.ChangesetTimelinePanel
import com.eacape.speccodingplugin.ui.prompt.PromptManagerPanel
import com.eacape.speccodingplugin.ui.mcp.McpPanel
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.eacape.speccodingplugin.ui.worktree.WorktreePanel
import com.eacape.speccodingplugin.ui.history.HistoryPanel
import com.eacape.speccodingplugin.ui.hook.HookPanel
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.SwingUtilities

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = SpecCodingBundle.message("toolwindow.title")
        val windowStateStore = WindowStateStore.getInstance(project)

        val contentFactory = ContentFactory.getInstance()

        // Chat 标签页
        val chatPanel = ImprovedChatPanel(project)
        val chatContent = contentFactory.createContent(chatPanel, SpecCodingBundle.message("toolwindow.tab.chat"), false)
        Disposer.register(chatContent, chatPanel)
        toolWindow.contentManager.addContent(chatContent)

        // Changes 标签页（变更时间线）
        val timelinePanel = ChangesetTimelinePanel(project)
        val timelineContent = contentFactory.createContent(timelinePanel, SpecCodingBundle.message("toolwindow.tab.changes"), false)
        toolWindow.contentManager.addContent(timelineContent)

        // Prompts 标签页（Prompt 管理）
        val promptPanel = PromptManagerPanel(project)
        val promptContent = contentFactory.createContent(promptPanel, SpecCodingBundle.message("toolwindow.tab.prompts"), false)
        toolWindow.contentManager.addContent(promptContent)

        // Specs 标签页（Spec 工作流）
        val specPanel = SpecWorkflowPanel(project)
        val specContent = contentFactory.createContent(specPanel, SpecCodingBundle.message("spec.tab.title"), false)
        Disposer.register(specContent, specPanel)
        toolWindow.contentManager.addContent(specContent)

        // MCP 标签页（MCP Server 管理）
        val mcpPanel = McpPanel(project)
        val mcpContent = contentFactory.createContent(mcpPanel, SpecCodingBundle.message("mcp.tab.title"), false)
        Disposer.register(mcpContent, mcpPanel)
        toolWindow.contentManager.addContent(mcpContent)

        // Worktree 标签页（Worktree 管理）
        val worktreePanel = WorktreePanel(project)
        val worktreeContent = contentFactory.createContent(worktreePanel, SpecCodingBundle.message("worktree.tab.title"), false)
        Disposer.register(worktreeContent, worktreePanel)
        toolWindow.contentManager.addContent(worktreeContent)

        // History 标签页（会话历史）
        val historyPanel = HistoryPanel(project)
        val historyContent = contentFactory.createContent(historyPanel, SpecCodingBundle.message("history.tab.title"), false)
        Disposer.register(historyContent, historyPanel)
        toolWindow.contentManager.addContent(historyContent)

        // Hook 标签页（Hook 管理）
        val hookPanel = HookPanel(project)
        val hookContent = contentFactory.createContent(hookPanel, SpecCodingBundle.message("hook.tab.title"), false)
        Disposer.register(hookContent, hookPanel)
        toolWindow.contentManager.addContent(hookContent)

        val contentManager = toolWindow.contentManager
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val selectedTabTitle = event.content?.displayName ?: return
                windowStateStore.updateSelectedTabTitle(selectedTabTitle)
            }
        })

        project.messageBus.connect(toolWindow.disposable).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: com.eacape.speccodingplugin.i18n.LocaleChangedEvent) {
                    SwingUtilities.invokeLater {
                        if (project.isDisposed) return@invokeLater

                        toolWindow.title = SpecCodingBundle.message("toolwindow.title")
                        chatContent.displayName = SpecCodingBundle.message("toolwindow.tab.chat")
                        timelineContent.displayName = SpecCodingBundle.message("toolwindow.tab.changes")
                        promptContent.displayName = SpecCodingBundle.message("toolwindow.tab.prompts")
                        specContent.displayName = SpecCodingBundle.message("spec.tab.title")
                        mcpContent.displayName = SpecCodingBundle.message("mcp.tab.title")
                        worktreeContent.displayName = SpecCodingBundle.message("worktree.tab.title")
                        historyContent.displayName = SpecCodingBundle.message("history.tab.title")
                        hookContent.displayName = SpecCodingBundle.message("hook.tab.title")
                    }
                }
            },
        )

        val restoredTabTitle = windowStateStore.snapshot().selectedTabTitle
        contentManager.contents.firstOrNull { it.displayName == restoredTabTitle }
            ?.let(contentManager::setSelectedContent)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Tool Window 始终可用
        return true
    }
}
