package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.ui.chat.ChangesetTimelinePanel
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.eacape.speccodingplugin.ui.worktree.WorktreePanel
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.SwingUtilities

class ChatToolWindowFactory : ToolWindowFactory {
    companion object {
        private val CHANGES_CONTENT_KEY = Key.create<Boolean>("SpecCoding.ChangesContent")
        private val WORKTREE_CONTENT_KEY = Key.create<Boolean>("SpecCoding.WorktreeContent")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = SpecCodingBundle.message("toolwindow.title")
        val windowStateStore = WindowStateStore.getInstance(project)

        // Tool Window 标题栏图标（从左到右：新建会话、历史会话、变更、worktree、设置）
        val newSessionTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                focusChatTab(toolWindow)
                project.messageBus.syncPublisher(ChatToolWindowControlListener.TOPIC).onNewSessionRequested()
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.session.new.tooltip")
                e.presentation.icon = AllIcons.General.Add
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val openHistoryTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                project.messageBus.syncPublisher(ChatToolWindowControlListener.TOPIC).onOpenHistoryRequested()
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.history.open.tooltip")
                e.presentation.icon = AllIcons.Vcs.History
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val openChangesTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                openOrSelectTransientContent(
                    toolWindow = toolWindow,
                    contentKey = CHANGES_CONTENT_KEY,
                    title = SpecCodingBundle.message("toolwindow.tab.changes"),
                    createPanel = { ChangesetTimelinePanel(project) },
                )
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.changes.open.tooltip")
                e.presentation.icon = AllIcons.Actions.Undo
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val openWorktreeTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                openOrSelectTransientContent(
                    toolWindow = toolWindow,
                    contentKey = WORKTREE_CONTENT_KEY,
                    title = SpecCodingBundle.message("worktree.tab.title"),
                    createPanel = { WorktreePanel(project) },
                )
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.worktree.open.tooltip")
                e.presentation.icon = AllIcons.Vcs.Branch
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val settingsAction = ActionManager.getInstance().getAction("SpecCoding.OpenSettings")
        val titleActions = mutableListOf<AnAction>(
            newSessionTitleAction,
            openHistoryTitleAction,
            openChangesTitleAction,
            openWorktreeTitleAction,
        )
        if (settingsAction != null) {
            titleActions.add(settingsAction)
        }
        toolWindow.setTitleActions(titleActions)

        val contentFactory = ContentFactory.getInstance()

        // Chat 标签页
        val chatPanel = ImprovedChatPanel(project)
        val chatContent = contentFactory.createContent(chatPanel, SpecCodingBundle.message("toolwindow.tab.chat"), false)
        Disposer.register(chatContent, chatPanel)
        toolWindow.contentManager.addContent(chatContent)

        // Specs 标签页（Spec 工作流）
        val specPanel = SpecWorkflowPanel(project)
        val specContent = contentFactory.createContent(specPanel, SpecCodingBundle.message("spec.tab.title"), false)
        Disposer.register(specContent, specPanel)
        toolWindow.contentManager.addContent(specContent)

        val contentManager = toolWindow.contentManager
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val selectedTabTitle = event.content?.displayName ?: return
                windowStateStore.updateSelectedTabTitle(selectedTabTitle)
                if (selectedTabTitle == SpecCodingBundle.message("spec.tab.title")) {
                    specPanel.refreshWorkflows()
                }
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
                        specContent.displayName = SpecCodingBundle.message("spec.tab.title")
                        updateTransientContentDisplayName(
                            toolWindow = toolWindow,
                            contentKey = CHANGES_CONTENT_KEY,
                            title = SpecCodingBundle.message("toolwindow.tab.changes"),
                        )
                        updateTransientContentDisplayName(
                            toolWindow = toolWindow,
                            contentKey = WORKTREE_CONTENT_KEY,
                            title = SpecCodingBundle.message("worktree.tab.title"),
                        )
                    }
                }
            },
        )

        val restoredTabTitle = windowStateStore.snapshot().selectedTabTitle
        contentManager.contents.firstOrNull { it.displayName == restoredTabTitle }
            ?.let(contentManager::setSelectedContent)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        return true
    }

    private fun focusChatTab(toolWindow: ToolWindow) {
        val chatTitle = SpecCodingBundle.message("toolwindow.tab.chat")
        toolWindow.contentManager.contents
            .firstOrNull { it.displayName == chatTitle }
            ?.let(toolWindow.contentManager::setSelectedContent)
        toolWindow.activate(null)
    }

    private fun openOrSelectTransientContent(
        toolWindow: ToolWindow,
        contentKey: Key<Boolean>,
        title: String,
        createPanel: () -> javax.swing.JPanel,
    ) {
        val contentManager = toolWindow.contentManager
        val existing = contentManager.contents.firstOrNull { it.getUserData(contentKey) == true }
        if (existing != null) {
            existing.displayName = title
            contentManager.setSelectedContent(existing)
            return
        }

        val panel = createPanel()
        val content = ContentFactory.getInstance().createContent(panel, title, false)
        content.putUserData(contentKey, true)
        if (panel is Disposable) {
            Disposer.register(content, panel)
        }
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)

        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content != content && contentManager.contents.contains(content)) {
                    contentManager.removeContentManagerListener(this)
                    contentManager.removeContent(content, true)
                }
            }
        })
    }

    private fun updateTransientContentDisplayName(
        toolWindow: ToolWindow,
        contentKey: Key<Boolean>,
        title: String,
    ) {
        toolWindow.contentManager.contents
            .firstOrNull { it.getUserData(contentKey) == true }
            ?.displayName = title
    }
}
