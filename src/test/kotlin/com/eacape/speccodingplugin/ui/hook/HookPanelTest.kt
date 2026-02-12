package com.eacape.speccodingplugin.ui.hook

import com.eacape.speccodingplugin.hook.HookAction
import com.eacape.speccodingplugin.hook.HookActionType
import com.eacape.speccodingplugin.hook.HookDefinition
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookExecutionLog
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookPanelTest {

    @Test
    fun `refresh should load hooks and logs`() {
        val hooks = listOf(
            HookDefinition(
                id = "h1",
                name = "Hook One",
                event = HookEvent.FILE_SAVED,
                enabled = true,
                actions = listOf(HookAction(type = HookActionType.SHOW_NOTIFICATION, message = "ok")),
            ),
            HookDefinition(
                id = "h2",
                name = "Hook Two",
                event = HookEvent.GIT_COMMIT,
                enabled = false,
                actions = listOf(HookAction(type = HookActionType.SHOW_NOTIFICATION, message = "ok")),
            ),
        )
        val logs = listOf(
            HookExecutionLog(
                hookId = "h1",
                hookName = "Hook One",
                event = HookEvent.FILE_SAVED,
                success = true,
                message = "done",
                timestamp = 1_700_000_000_000,
            )
        )

        val panel = HookPanel(
            project = fakeProject(),
            listHooksAction = { hooks },
            setHookEnabledAction = { _, _ -> true },
            listLogsAction = { logs },
            clearLogsAction = { },
            runSynchronously = true,
        )

        panel.clickRefreshForTest()

        assertEquals(2, panel.hooksForTest().size)
        assertTrue(panel.logTextForTest().contains("Hook One"))
        assertTrue(panel.statusTextForTest().isNotBlank())

        panel.dispose()
    }

    @Test
    fun `enable and disable should call setHookEnabledAction`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val hooksState = mutableMapOf(
            "h1" to HookDefinition(
                id = "h1",
                name = "Hook One",
                event = HookEvent.FILE_SAVED,
                enabled = false,
                actions = listOf(HookAction(type = HookActionType.SHOW_NOTIFICATION, message = "ok")),
            )
        )

        val panel = HookPanel(
            project = fakeProject(),
            listHooksAction = { hooksState.values.toList() },
            setHookEnabledAction = { hookId, enabled ->
                calls += hookId to enabled
                val current = hooksState[hookId] ?: return@HookPanel false
                hooksState[hookId] = current.copy(enabled = enabled)
                true
            },
            listLogsAction = { emptyList() },
            clearLogsAction = { },
            runSynchronously = true,
        )

        panel.selectHookForTest("h1")
        panel.clickEnableForTest()
        panel.clickDisableForTest()

        assertEquals(listOf("h1" to true, "h1" to false), calls)

        panel.dispose()
    }

    @Test
    fun `clear logs should call clear action and refresh log area`() {
        var cleared = 0
        var logs = listOf(
            HookExecutionLog(
                hookId = "h1",
                hookName = "Hook One",
                event = HookEvent.FILE_SAVED,
                success = true,
                message = "done",
                timestamp = 1_700_000_000_000,
            )
        )

        val panel = HookPanel(
            project = fakeProject(),
            listHooksAction = { emptyList() },
            setHookEnabledAction = { _, _ -> true },
            listLogsAction = { logs },
            clearLogsAction = {
                cleared++
                logs = emptyList()
            },
            runSynchronously = true,
        )

        panel.clickClearLogsForTest()

        assertEquals(1, cleared)
        assertTrue(panel.logTextForTest().contains("暂无") || panel.logTextForTest().contains("No execution logs"))

        panel.dispose()
    }

    private fun fakeProject(): Project {
        return mockk(relaxed = true) {
            every { isDisposed } returns false
        }
    }
}
