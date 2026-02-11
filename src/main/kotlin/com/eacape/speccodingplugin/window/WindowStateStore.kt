package com.eacape.speccodingplugin.window

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "SpecCodingWindowState",
    storages = [Storage("specCodingWindowState.xml")],
)
class WindowStateStore : PersistentStateComponent<WindowStateStore.WindowState> {

    private var state: WindowState = WindowState()

    @Synchronized
    override fun getState(): WindowState = state.copy()

    @Synchronized
    override fun loadState(state: WindowState) {
        this.state = state.copy()
    }

    @Synchronized
    fun snapshot(): WindowRuntimeState {
        return WindowRuntimeState(
            selectedTabTitle = state.selectedTabTitle,
            activeSessionId = state.activeSessionId,
            operationMode = state.operationMode,
            updatedAt = state.updatedAt,
        )
    }

    @Synchronized
    fun updateSelectedTabTitle(tabTitle: String?) {
        val value = tabTitle?.trim()?.ifBlank { null } ?: return
        state.selectedTabTitle = value
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateActiveSessionId(sessionId: String?) {
        state.activeSessionId = sessionId?.trim()?.ifBlank { null }
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateOperationMode(modeName: String?) {
        state.operationMode = modeName?.trim()?.ifBlank { null }
        state.updatedAt = System.currentTimeMillis()
    }

    data class WindowState(
        var selectedTabTitle: String = "Chat",
        var activeSessionId: String? = null,
        var operationMode: String? = null,
        var updatedAt: Long = 0L,
    )

    companion object {
        fun getInstance(project: Project): WindowStateStore = project.service()
    }
}

data class WindowRuntimeState(
    val selectedTabTitle: String,
    val activeSessionId: String?,
    val operationMode: String?,
    val updatedAt: Long,
)

