package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.prompt.TeamPromptSyncService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Prompt 管理面板
 * 展示 Prompt 列表，支持新建、编辑、删除操作
 */
class PromptManagerPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val promptManager = PromptManager.getInstance(project)
    private val teamPromptSyncService = TeamPromptSyncService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listModel = DefaultListModel<PromptTemplate>()
    private val promptList = JBList(listModel)

    private val titleLabel = JBLabel()
    private val newBtn = JButton()
    private val editBtn = JButton()
    private val deleteBtn = JButton()
    private val teamPullBtn = JButton()
    private val teamPushBtn = JButton()
    private val activeLabel = JBLabel("")

    @Volatile
    private var isDisposed = false

    init {
        border = JBUI.Borders.empty(8)
        setupUI()
        subscribeToLocaleEvents()
        refreshLocalizedTexts()
        refresh()
    }

    private fun setupUI() {
        // 顶部标题栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        toolbar.isOpaque = false

        titleLabel.font = titleLabel.font.deriveFont(
            java.awt.Font.BOLD, 13f
        )
        toolbar.add(titleLabel)
        toolbar.add(newBtn)
        toolbar.add(editBtn)
        toolbar.add(deleteBtn)
        toolbar.add(teamPullBtn)
        toolbar.add(teamPushBtn)

        // 列表
        promptList.selectionMode =
            ListSelectionModel.SINGLE_SELECTION
        promptList.cellRenderer = PromptListCellRenderer()
        promptList.addListSelectionListener {
            updateButtonStates()
        }

        val scrollPane = JBScrollPane(promptList)
        scrollPane.border = JBUI.Borders.emptyTop(8)

        // 底部状态
        activeLabel.foreground = JBColor.GRAY
        activeLabel.font = activeLabel.font.deriveFont(11f)
        activeLabel.border = JBUI.Borders.emptyTop(4)

        // 按钮事件
        newBtn.addActionListener { onNew() }
        editBtn.addActionListener { onEdit() }
        deleteBtn.addActionListener { onDelete() }
        teamPullBtn.addActionListener { onPullTeam() }
        teamPushBtn.addActionListener { onPushTeam() }

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(activeLabel, BorderLayout.SOUTH)

        updateButtonStates()
    }

    fun refresh() {
        val templates = promptManager.listPromptTemplates()
        val activeId = promptManager.getActivePromptId()

        listModel.clear()
        templates.forEach { listModel.addElement(it) }

        val activeName = templates.firstOrNull { it.id == activeId }?.name
            ?: activeId
            ?: SpecCodingBundle.message("prompt.manager.active.none")
        activeLabel.text = SpecCodingBundle.message("prompt.manager.active", activeName, templates.size)

        updateButtonStates()
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("prompt.manager.title")
        newBtn.text = SpecCodingBundle.message("prompt.manager.new")
        editBtn.text = SpecCodingBundle.message("prompt.manager.edit")
        deleteBtn.text = SpecCodingBundle.message("prompt.manager.delete")
        teamPullBtn.text = SpecCodingBundle.message("prompt.manager.teamPull")
        teamPushBtn.text = SpecCodingBundle.message("prompt.manager.teamPush")
    }

    private fun subscribeToLocaleEvents() {
        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                        refresh()
                    }
                }
            },
        )
    }

    private fun updateButtonStates() {
        val selected = promptList.selectedValue
        editBtn.isEnabled = selected != null
        deleteBtn.isEnabled = selected != null &&
            selected.id != PromptManager.DEFAULT_PROMPT_ID
    }

    private fun onNew() {
        val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
        val dialog = PromptEditorDialog(existingPromptIds = existingIds)
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onEdit() {
        val selected = promptList.selectedValue ?: return
        val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
        val dialog = PromptEditorDialog(existing = selected, existingPromptIds = existingIds)
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onDelete() {
        val selected = promptList.selectedValue ?: return
        if (selected.id == PromptManager.DEFAULT_PROMPT_ID) {
            return
        }
        promptManager.deleteTemplate(selected.id)
        refresh()
    }

    private fun onPullTeam() {
        runBackground {
            teamPromptSyncService.pullFromTeamRepo()
                .onSuccess { result ->
                    invokeLaterSafe {
                        refresh()
                        notifyUser(
                            SpecCodingBundle.message(
                                "prompt.teamSync.pull.success",
                                result.syncedFiles,
                                result.branch,
                            ),
                            NotificationType.INFORMATION,
                        )
                    }
                }
                .onFailure { error ->
                    invokeLaterSafe {
                        notifyUser(
                            SpecCodingBundle.message(
                                "prompt.teamSync.pull.failed",
                                error.message ?: SpecCodingBundle.message("prompt.teamSync.error.generic"),
                            ),
                            NotificationType.ERROR,
                        )
                    }
                }
        }
    }

    private fun onPushTeam() {
        runBackground {
            teamPromptSyncService.pushToTeamRepo()
                .onSuccess { result ->
                    invokeLaterSafe {
                        if (result.noChanges) {
                            notifyUser(
                                SpecCodingBundle.message("prompt.teamSync.push.noChanges", result.branch),
                                NotificationType.INFORMATION,
                            )
                        } else {
                            notifyUser(
                                SpecCodingBundle.message(
                                    "prompt.teamSync.push.success",
                                    result.syncedFiles,
                                    result.branch,
                                    result.commitId ?: SpecCodingBundle.message("common.unknown"),
                                ),
                                NotificationType.INFORMATION,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    invokeLaterSafe {
                        notifyUser(
                            SpecCodingBundle.message(
                                "prompt.teamSync.push.failed",
                                error.message ?: SpecCodingBundle.message("prompt.teamSync.error.generic"),
                            ),
                            NotificationType.ERROR,
                        )
                    }
                }
        }
    }

    private fun notifyUser(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(message, type)
            .notify(project)
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (isDisposed) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    private fun runBackground(task: () -> Unit) {
        if (isDisposed) {
            return
        }
        scope.launch(Dispatchers.IO) { task() }
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }
}
