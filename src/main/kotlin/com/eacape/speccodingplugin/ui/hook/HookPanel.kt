package com.eacape.speccodingplugin.ui.hook

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.hook.HookDefinition
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookExecutionLog
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane

class HookPanel(
    private val project: Project,
    private val listHooksAction: () -> List<HookDefinition> = { HookManager.getInstance(project).listHooks() },
    private val setHookEnabledAction: (hookId: String, enabled: Boolean) -> Boolean = { hookId, enabled ->
        HookManager.getInstance(project).setHookEnabled(hookId, enabled)
    },
    private val listLogsAction: (limit: Int) -> List<HookExecutionLog> = { limit ->
        HookManager.getInstance(project).getExecutionLogs(limit)
    },
    private val clearLogsAction: () -> Unit = { HookManager.getInstance(project).clearExecutionLogs() },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val hooksModel = DefaultListModel<HookDefinition>()
    private val hooksList = JBList(hooksModel)
    private val logArea = JBTextArea()

    private val titleLabel = JBLabel(SpecCodingBundle.message("hook.panel.title"))
    private val statusLabel = JBLabel("")
    private val refreshButton = JButton(SpecCodingBundle.message("hook.action.refresh"))
    private val enableButton = JButton(SpecCodingBundle.message("hook.action.enable"))
    private val disableButton = JButton(SpecCodingBundle.message("hook.action.disable"))
    private val refreshLogButton = JButton(SpecCodingBundle.message("hook.log.refresh"))
    private val clearLogButton = JButton(SpecCodingBundle.message("hook.log.clear"))

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
        subscribeToEvents()
        refreshData()
    }

    private fun setupUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
        }

        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 13f)

        toolbar.add(titleLabel)
        toolbar.add(refreshButton)
        toolbar.add(enableButton)
        toolbar.add(disableButton)
        toolbar.add(refreshLogButton)
        toolbar.add(clearLogButton)
        toolbar.add(statusLabel)
        add(toolbar, BorderLayout.NORTH)

        hooksList.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        hooksList.cellRenderer = HookListRenderer()
        hooksList.addListSelectionListener { updateButtonState() }

        logArea.isEditable = false
        logArea.lineWrap = true
        logArea.wrapStyleWord = true
        logArea.text = SpecCodingBundle.message("hook.log.empty")

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(hooksList),
            JBScrollPane(logArea),
        ).apply {
            dividerLocation = 320
            dividerSize = JBUI.scale(4)
        }
        add(splitPane, BorderLayout.CENTER)

        refreshButton.addActionListener { refreshData() }
        refreshLogButton.addActionListener { refreshLogs() }
        enableButton.addActionListener { updateSelectedHookEnabled(true) }
        disableButton.addActionListener { updateSelectedHookEnabled(false) }
        clearLogButton.addActionListener { clearLogs() }

        updateButtonState()
    }

    private fun subscribeToEvents() {
        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun refreshData() {
        refreshHooks()
        refreshLogs()
    }

    private fun refreshHooks() {
        val selectedId = hooksList.selectedValue?.id
        runBackground {
            val hooks = listHooksAction().sortedBy { it.name }
            invokeLaterSafe {
                hooksModel.clear()
                hooks.forEach(hooksModel::addElement)

                if (selectedId != null) {
                    setSelectedHookById(selectedId)
                }
                if (hooksList.selectedIndex < 0 && hooksModel.size() > 0) {
                    hooksList.selectedIndex = 0
                }

                statusLabel.text = SpecCodingBundle.message("hook.status.count", hooks.size)
                hooksList.repaint()
                updateButtonState()
            }
        }
    }

    private fun refreshLogs() {
        runBackground {
            val logs = listLogsAction(200)
            invokeLaterSafe {
                logArea.text = if (logs.isEmpty()) {
                    SpecCodingBundle.message("hook.log.empty")
                } else {
                    logs
                        .sortedBy { it.timestamp }
                        .joinToString(separator = "\n") { log ->
                            SpecCodingBundle.message(
                                "hook.log.entry",
                                dateFormatter.format(Instant.ofEpochMilli(log.timestamp)),
                                log.hookName,
                                eventDisplayName(log.event),
                                if (log.success) {
                                    SpecCodingBundle.message("hook.log.result.success")
                                } else {
                                    SpecCodingBundle.message("hook.log.result.failed")
                                },
                                log.message,
                            )
                        }
                }
                logArea.caretPosition = logArea.document.length
            }
        }
    }

    private fun clearLogs() {
        runBackground {
            clearLogsAction()
            invokeLaterSafe {
                statusLabel.text = SpecCodingBundle.message("hook.status.logs.cleared")
                refreshLogs()
            }
        }
    }

    private fun updateSelectedHookEnabled(enabled: Boolean) {
        val selected = hooksList.selectedValue ?: return

        runBackground {
            val updated = setHookEnabledAction(selected.id, enabled)
            invokeLaterSafe {
                if (updated) {
                    val status = if (enabled) {
                        SpecCodingBundle.message("hook.enabled")
                    } else {
                        SpecCodingBundle.message("hook.disabled")
                    }
                    statusLabel.text = SpecCodingBundle.message("hook.status.updated", selected.name, status)
                    refreshHooks()
                } else {
                    statusLabel.text = SpecCodingBundle.message("hook.status.updateFailed", selected.name)
                }
            }
        }
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("hook.panel.title")
        refreshButton.text = SpecCodingBundle.message("hook.action.refresh")
        enableButton.text = SpecCodingBundle.message("hook.action.enable")
        disableButton.text = SpecCodingBundle.message("hook.action.disable")
        refreshLogButton.text = SpecCodingBundle.message("hook.log.refresh")
        clearLogButton.text = SpecCodingBundle.message("hook.log.clear")
        hooksList.repaint()
        if (logArea.text == SpecCodingBundle.message("hook.log.empty")) {
            logArea.text = SpecCodingBundle.message("hook.log.empty")
        }
    }

    private fun updateButtonState() {
        val selected = hooksList.selectedValue
        val hasSelection = selected != null
        enableButton.isEnabled = hasSelection && selected?.enabled == false
        disableButton.isEnabled = hasSelection && selected?.enabled == true
    }

    private fun setSelectedHookById(hookId: String) {
        val index = (0 until hooksModel.size()).firstOrNull { hooksModel.get(it).id == hookId } ?: return
        hooksList.selectedIndex = index
    }

    private fun eventDisplayName(event: HookEvent): String {
        return when (event) {
            HookEvent.FILE_SAVED -> SpecCodingBundle.message("hook.event.fileSaved")
            HookEvent.GIT_COMMIT -> SpecCodingBundle.message("hook.event.gitCommit")
            HookEvent.SPEC_STAGE_CHANGED -> SpecCodingBundle.message("hook.event.specStageChanged")
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (runSynchronously) {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
            return
        }

        if (isDisposed) return
        invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    private fun runBackground(task: () -> Unit) {
        if (runSynchronously) {
            task()
            return
        }
        scope.launch(Dispatchers.IO) { task() }
    }

    internal fun hooksForTest(): List<HookDefinition> {
        return (0 until hooksModel.size()).map { hooksModel.get(it) }
    }

    internal fun logTextForTest(): String = logArea.text

    internal fun statusTextForTest(): String = statusLabel.text

    internal fun selectHookForTest(hookId: String?) {
        if (hookId == null) {
            hooksList.clearSelection()
            return
        }
        setSelectedHookById(hookId)
    }

    internal fun clickEnableForTest() {
        enableButton.doClick()
    }

    internal fun clickDisableForTest() {
        disableButton.doClick()
    }

    internal fun clickRefreshForTest() {
        refreshButton.doClick()
    }

    internal fun clickClearLogsForTest() {
        clearLogButton.doClick()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    private inner class HookListRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
            val hook = value as? HookDefinition ?: return@also
            val enabledText = if (hook.enabled) {
                SpecCodingBundle.message("hook.enabled")
            } else {
                SpecCodingBundle.message("hook.disabled")
            }
            text = SpecCodingBundle.message(
                "hook.list.item",
                hook.name,
                eventDisplayName(hook.event),
                enabledText,
            )
        }
    }
}
