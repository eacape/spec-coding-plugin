package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.SessionFilter
import com.eacape.speccodingplugin.session.SessionExportFormat
import com.eacape.speccodingplugin.session.SessionExporter
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.SessionExportResult
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane

class HistoryPanel(
    private val project: Project,
    private val searchSessions: (query: String, filter: SessionFilter, limit: Int) -> List<SessionSummary> = { query, filter, limit ->
        SessionManager.getInstance(project).searchSessions(query = query, filter = filter, limit = limit)
    },
    private val listMessages: (sessionId: String, limit: Int) -> List<ConversationMessage> = { sessionId, limit ->
        SessionManager.getInstance(project).listMessages(sessionId, limit)
    },
    private val deleteSession: (sessionId: String) -> Result<Unit> = { sessionId ->
        SessionManager.getInstance(project).deleteSession(sessionId)
    },
    private val getSession: (sessionId: String) -> ConversationSession? = { sessionId ->
        SessionManager.getInstance(project).getSession(sessionId)
    },
    private val exportSession: (
        exportDir: Path,
        session: ConversationSession,
        messages: List<ConversationMessage>,
        format: SessionExportFormat,
    ) -> Result<SessionExportResult> = { exportDir, session, messages, format ->
        SessionExporter.exportSession(exportDir, session, messages, format)
    },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val searchField = JBTextField()
    private val filterCombo = JComboBox(SessionFilter.entries.toTypedArray())
    private val statusLabel = JBLabel("")

    private val listPanel = HistorySessionListPanel(
        onSessionSelected = ::onSessionSelected,
        onOpenSession = ::onOpenSession,
        onExportSession = ::onExportSession,
        onDeleteSession = ::onDeleteSession,
    )
    private val detailPanel = HistoryDetailPanel()

    private var selectedSessionId: String? = null

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
        refreshSessions()
    }

    private fun setupUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
        }

        searchField.columns = 24
        searchField.emptyText.text = SpecCodingBundle.message("history.search.placeholder")
        searchField.addActionListener { refreshSessions() }

        filterCombo.addActionListener { refreshSessions() }

        toolbar.add(JBLabel(SpecCodingBundle.message("history.search.label")))
        toolbar.add(searchField)
        toolbar.add(JBLabel(SpecCodingBundle.message("history.filter.label")))
        toolbar.add(filterCombo)
        toolbar.add(statusLabel)
        add(toolbar, BorderLayout.NORTH)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detailPanel).apply {
            dividerLocation = 280
            dividerSize = JBUI.scale(4)
        }
        add(splitPane, BorderLayout.CENTER)
    }

    fun refreshSessions() {
        val query = searchField.text
        val filter = filterCombo.selectedItem as? SessionFilter ?: SessionFilter.ALL

        runBackground {
            val sessions = searchSessions(query, filter, 200)
            invokeLaterSafe {
                listPanel.updateSessions(sessions)
                statusLabel.text = SpecCodingBundle.message("history.status.count", sessions.size)

                val selected = selectedSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                    ?: sessions.firstOrNull()
                selectedSessionId = selected?.id
                listPanel.setSelectedSession(selected?.id)
                if (selected == null) {
                    detailPanel.showEmpty()
                }
            }
        }
    }

    private fun onSessionSelected(sessionId: String) {
        selectedSessionId = sessionId
        runBackground {
            val messages = listMessages(sessionId, 500)
            invokeLaterSafe {
                detailPanel.showMessages(messages)
            }
        }
    }

    private fun onOpenSession(sessionId: String) {
        onSessionSelected(sessionId)
        project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
            .onSessionOpenRequested(sessionId)
        statusLabel.text = SpecCodingBundle.message("history.status.opened")
    }

    private fun onDeleteSession(sessionId: String) {
        runBackground {
            val result = deleteSession(sessionId)
            invokeLaterSafe {
                result.onSuccess {
                    if (selectedSessionId == sessionId) {
                        selectedSessionId = null
                        detailPanel.showEmpty()
                    }
                    refreshSessions()
                }.onFailure { error ->
                    logger.warn("Failed to delete session: $sessionId", error)
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.deleteFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onExportSession(sessionId: String, format: SessionExportFormat) {
        runBackground {
            runCatching {
                val session = getSession(sessionId)
                    ?: error("Session not found: $sessionId")
                val messages = listMessages(sessionId, 5000)

                val exportDir = resolveExportDir()
                exportSession(exportDir, session, messages, format).getOrThrow()
            }.onSuccess { result ->
                invokeLaterSafe {
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.exported",
                        result.format.toString(),
                        result.filePath.fileName.toString(),
                    )
                }
            }.onFailure { error ->
                logger.warn("Failed to export session: $sessionId", error)
                invokeLaterSafe {
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.exportFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun resolveExportDir(): Path {
        val basePath = project.basePath
            ?: error("Project base path is not available")
        return Path.of(basePath, ".spec-coding", "exports")
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

    internal fun setSearchQueryForTest(query: String) {
        searchField.text = query
    }

    internal fun setFilterForTest(filter: SessionFilter) {
        filterCombo.selectedItem = filter
    }

    internal fun statusTextForTest(): String = statusLabel.text

    internal fun selectedSessionIdForTest(): String? = selectedSessionId

    internal fun sessionsForTest(): List<SessionSummary> = listPanel.sessionsForTest()

    internal fun detailTextForTest(): String = detailPanel.displayedTextForTest()

    internal fun isDetailEmptyForTest(): Boolean = detailPanel.isShowingEmptyForTest()

    internal fun selectSessionForTest(sessionId: String?) {
        listPanel.setSelectedSession(sessionId)
        selectedSessionId = sessionId
    }

    internal fun clickOpenForTest() {
        listPanel.clickOpenForTest()
    }

    internal fun clickDeleteForTest() {
        listPanel.clickDeleteForTest()
    }

    internal fun clickExportForTest() {
        listPanel.clickExportForTest()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }
}
