package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.chat.ChangesetTimelinePanel
import com.eacape.speccodingplugin.ui.prompt.PromptManagerPanel
import com.eacape.speccodingplugin.ui.mcp.McpPanel
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = SpecCodingBundle.message("toolwindow.title")

        val contentFactory = ContentFactory.getInstance()

        // Chat 标签页
        val chatPanel = ImprovedChatPanel(project)
        val chatContent = contentFactory.createContent(chatPanel, "Chat", false)
        Disposer.register(chatContent, chatPanel)
        toolWindow.contentManager.addContent(chatContent)

        // Changes 标签页（变更时间线）
        val timelinePanel = ChangesetTimelinePanel(project)
        val timelineContent = contentFactory.createContent(timelinePanel, "Changes", false)
        toolWindow.contentManager.addContent(timelineContent)

        // Prompts 标签页（Prompt 管理）
        val promptPanel = PromptManagerPanel(project)
        val promptContent = contentFactory.createContent(promptPanel, "Prompts", false)
        toolWindow.contentManager.addContent(promptContent)

        // Specs 标签页（Spec 工作流）
        val specPanel = SpecWorkflowPanel(project)
        val specContent = contentFactory.createContent(specPanel, "Specs", false)
        Disposer.register(specContent, specPanel)
        toolWindow.contentManager.addContent(specContent)

        // MCP 标签页（MCP Server 管理）
        val mcpPanel = McpPanel(project)
        val mcpContent = contentFactory.createContent(mcpPanel, "MCP", false)
        Disposer.register(mcpContent, mcpPanel)
        toolWindow.contentManager.addContent(mcpContent)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Tool Window 始终可用
        return true
    }
}
