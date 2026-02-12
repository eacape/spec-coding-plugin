package com.eacape.speccodingplugin.window

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MultiWindowAcceptanceTest {

    @Test
    fun `two windows should keep isolated session state`() {
        val windowAState = WindowStateStore()
        val windowBState = WindowStateStore()

        windowAState.updateActiveSessionId("session-window-a")
        windowAState.updateOperationMode("PLAN")

        windowBState.updateActiveSessionId("session-window-b")
        windowBState.updateOperationMode("AGENT")

        val snapshotA = windowAState.snapshot()
        val snapshotB = windowBState.snapshot()

        assertEquals("session-window-a", snapshotA.activeSessionId)
        assertEquals("PLAN", snapshotA.operationMode)

        assertEquals("session-window-b", snapshotB.activeSessionId)
        assertEquals("AGENT", snapshotB.operationMode)

        windowAState.updateActiveSessionId(null)
        assertNull(windowAState.snapshot().activeSessionId)
        assertEquals("session-window-b", windowBState.snapshot().activeSessionId)
    }

    @Test
    fun `global config change should broadcast latest snapshot in realtime`() {
        val messageBus = mockk<MessageBus>(relaxed = true)
        val listener = mockk<GlobalConfigSyncListener>(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<GlobalConfigSyncListener>>()) } returns listener

        val settings = SpecCodingSettingsState().apply {
            defaultProvider = "openai"
            openaiModel = "gpt-4o"
            anthropicModel = "claude-opus-4-20250514"
        }

        val windowRegistry = mockk<WindowRegistry>(relaxed = true)
        val windowAProject = mockk<Project>(relaxed = true)
        every { windowAProject.name } returns "Window-A"
        val windowBProject = mockk<Project>(relaxed = true)
        every { windowBProject.name } returns "Window-B"

        every { windowRegistry.currentWindowId(windowAProject) } returns "window-a"
        every { windowRegistry.currentWindowId(windowBProject) } returns "window-b"

        var now = 1000L
        val service = GlobalConfigSyncService(
            messageBus = messageBus,
            settingsProvider = { settings },
            windowRegistryProvider = { windowRegistry },
            clock = { now++ },
        )

        val firstEvent = service.notifyGlobalConfigChanged(
            sourceProject = windowAProject,
            reason = "window-a-update",
        )
        assertEquals("window-a", firstEvent.sourceWindowId)
        assertEquals("openai", firstEvent.snapshot.defaultProvider)

        settings.defaultProvider = "anthropic"
        settings.anthropicModel = "claude-3-5-sonnet"

        val secondEvent = service.notifyGlobalConfigChanged(
            sourceProject = windowBProject,
            reason = "window-b-update",
        )
        assertEquals("window-b", secondEvent.sourceWindowId)
        assertEquals("anthropic", secondEvent.snapshot.defaultProvider)
        assertEquals("claude-3-5-sonnet", secondEvent.snapshot.anthropicModel)

        verify(exactly = 2) { listener.onGlobalConfigChanged(any()) }
    }
}

