package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.SessionContextSnapshot
import com.eacape.speccodingplugin.session.SessionFilter
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
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
    private val saveContextSnapshot: (
        sessionId: String,
        messageId: String?,
        title: String?,
        metadataJson: String?,
    ) -> Result<SessionContextSnapshot> = { sessionId, messageId, title, metadataJson ->
        SessionManager.getInstance(project).saveContextSnapshot(sessionId, messageId, title, metadataJson)
    },
    private val continueFromSnapshot: (snapshotId: String, branchName: String?) -> Result<ConversationSession> = { snapshotId, branchName ->
        SessionManager.getInstance(project).continueFromSnapshot(snapshotId, branchName)
    },
    private val forkSession: (sourceSessionId: String, fromMessageId: String?, branchName: String?) -> Result<ConversationSession> = { sourceSessionId, fromMessageId, branchName ->
        SessionManager.getInstance(project).forkSession(sourceSessionId, fromMessageId, branchName)
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
        onContinueSession = ::onContinueSession,
        onDeleteSession = ::onDeleteSession,
        onBranchSession = ::onBranchSession,
    )
    private val detailPanel = HistoryDetailPanel()

    private var selectedSessionId: String? = null

    init {
        border = JBUI.Borders.empty(8)
        setupUi()
        refreshSessions()
    }

    private fun setupUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }

        searchField.columns = 28
        searchField.emptyText.text = SpecCodingBundle.message("history.search.placeholder")
        searchField.addActionListener { refreshSessions() }

        filterCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? SessionFilter)?.name ?: SessionFilter.ALL.name
                return this
            }
        }
        filterCombo.preferredSize = JBUI.size(92, 28)
        filterCombo.addActionListener { refreshSessions() }
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = JBColor(Color(120, 124, 132), Color(146, 152, 163))

        toolbar.add(JBLabel(SpecCodingBundle.message("history.search.label")))
        toolbar.add(searchField)
        toolbar.add(JBLabel(SpecCodingBundle.message("history.filter.label")))
        toolbar.add(filterCombo)
        toolbar.add(statusLabel)
        add(toolbar, BorderLayout.NORTH)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detailPanel).apply {
            dividerLocation = 320
            resizeWeight = 0.34
            dividerSize = JBUI.scale(5)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
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
                statusLabel.text = buildStatusText(sessions.size, query, filter)

                val selected = selectedSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                    ?: sessions.firstOrNull()
                selectedSessionId = selected?.id
                listPanel.setSelectedSession(selected?.id)
                if (selected == null) {
                    if (sessions.isEmpty() && (query.isNotBlank() || filter != SessionFilter.ALL)) {
                        detailPanel.showText(renderFilteredEmptyText(query, filter))
                    } else {
                        detailPanel.showEmpty()
                    }
                }
            }
        }
    }

    private fun onSessionSelected(sessionId: String) {
        selectedSessionId = sessionId
        runBackground {
            val messages = listMessages(sessionId, SESSION_MESSAGES_FETCH_LIMIT)
                .takeLast(SESSION_MESSAGES_RENDER_LIMIT)
            invokeLaterSafe {
                detailPanel.showMessages(messages)
            }
        }
    }

    private fun onOpenSession(sessionId: String) {
        onSessionSelected(sessionId)
        project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
            .onSessionOpenRequested(sessionId)
        focusChatTabOnly()
        statusLabel.text = SpecCodingBundle.message("history.status.opened")
    }

    private fun focusChatTabOnly() {
        runCatching {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return
            val chatTitle = SpecCodingBundle.message("toolwindow.tab.chat")
            val chatContent = toolWindow.contentManager.contents.firstOrNull {
                it.displayName == chatTitle
            } ?: return
            toolWindow.contentManager.setSelectedContent(chatContent, true)
            toolWindow.activate(null)
        }
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

    private fun onBranchSession(sessionId: String) {
        runBackground {
            val result = forkSession(sessionId, null, null)
            invokeLaterSafe {
                result.onSuccess { branched ->
                    selectedSessionId = branched.id
                    refreshSessions()
                    statusLabel.text = SpecCodingBundle.message("history.status.branchCreated", branched.title)
                }.onFailure { error ->
                    logger.warn("Failed to branch session: $sessionId", error)
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.branchFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onContinueSession(sessionId: String) {
        runBackground {
            runCatching {
                val snapshot = saveContextSnapshot(sessionId, null, null, null).getOrThrow()
                continueFromSnapshot(snapshot.id, null).getOrThrow()
            }.onSuccess { continued ->
                invokeLaterSafe {
                    selectedSessionId = continued.id
                    refreshSessions()
                    project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
                        .onSessionOpenRequested(continued.id)
                    statusLabel.text = SpecCodingBundle.message("history.status.continued", continued.title)
                }
            }.onFailure { error ->
                logger.warn("Failed to continue session from snapshot: $sessionId", error)
                invokeLaterSafe {
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.continueFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun buildStatusText(count: Int, query: String, filter: SessionFilter): String {
        val countText = SpecCodingBundle.message("history.status.count", count)
        val normalizedQuery = query.trim()
        return when {
            normalizedQuery.isBlank() && filter == SessionFilter.ALL -> countText
            normalizedQuery.isBlank() -> "$countText · ${filter.name}"
            else -> "$countText · ${filter.name} · \"$normalizedQuery\""
        }
    }

    private fun renderFilteredEmptyText(query: String, filter: SessionFilter): String {
        val queryText = query.trim().ifBlank {
            SpecCodingBundle.message("history.empty.filtered.query.none")
        }
        return buildString {
            appendLine("### ${SpecCodingBundle.message("history.empty.filtered.title")}")
            appendLine()
            appendLine(SpecCodingBundle.message("history.empty.filtered.desc", filter.name, queryText))
        }.trim()
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

    internal fun clickBranchForTest() {
        listPanel.clickBranchForTest()
    }

    internal fun clickContinueForTest() {
        listPanel.clickContinueForTest()
    }

    internal fun clickDeleteForTest() {
        listPanel.clickDeleteForTest()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    companion object {
        private const val SESSION_MESSAGES_FETCH_LIMIT = 5000
        private const val SESSION_MESSAGES_RENDER_LIMIT = 600
    }
}
