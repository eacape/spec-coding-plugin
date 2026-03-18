package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchFallbackReason
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLegacyCompactNotice
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchPresentation
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchSurface
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSection
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSectionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class WorkflowChatExecutionLaunchMessagePanelTest {

    @Test
    fun `presentation payload should render execution launch summary card`() {
        val panel = runOnEdtResult {
            WorkflowChatExecutionLaunchMessagePanel(
                payload = WorkflowChatExecutionLaunchRestorePayload.Presentation(
                    WorkflowChatExecutionLaunchPresentation(
                        workflowId = "wf-149",
                        taskId = "T-149",
                        taskTitle = "Render execution launch card in workflow chat",
                        runId = "run-149",
                        focusedStage = StageId.TASKS,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        launchSurface = WorkflowChatExecutionLaunchSurface.TASK_ROW,
                        taskStatusBeforeExecution = TaskStatus.PENDING,
                        taskPriority = TaskPriority.P1,
                        sections = listOf(
                            WorkflowChatExecutionPresentationSection(
                                kind = WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                                itemCount = 2,
                                previewItems = listOf(
                                    "requirements.md: execution requests should render as cards.",
                                    "design.md: restore should prefer card reconstruction.",
                                ),
                            ),
                            WorkflowChatExecutionPresentationSection(
                                kind = WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT,
                                itemCount = 1,
                                previewItems = listOf("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
                            ),
                        ),
                        supplementalInstruction = "Keep the summary compact.",
                        rawPromptDebugAvailable = true,
                    ),
                ),
                visibleContent = "## Execution Request",
            )
        }

        val snapshot = panel.snapshotForTest()
        assertEquals("presentation", snapshot.getValue("kind"))
        assertEquals("wf-149", snapshot.getValue("workflowId"))
        assertEquals("T-149", snapshot.getValue("taskId"))
        assertEquals("run-149", snapshot.getValue("runId"))
        assertEquals(StageId.TASKS.name, snapshot.getValue("focusedStage"))

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(renderedText.contains("requirements.md"))
        assertTrue(renderedText.contains("Keep the summary compact."))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.note.rawPromptHidden")))
    }

    @Test
    fun `legacy payload should render compact fallback notice instead of raw prompt`() {
        val panel = runOnEdtResult {
            WorkflowChatExecutionLaunchMessagePanel(
                payload = WorkflowChatExecutionLaunchRestorePayload.LegacyCompact(
                    WorkflowChatExecutionLegacyCompactNotice(
                        workflowId = "wf-legacy",
                        taskId = "T-019",
                        taskTitle = "Legacy launch restore",
                        runId = "run-019",
                        focusedStage = StageId.TASKS,
                        trigger = ExecutionTrigger.USER_RETRY,
                        launchSurface = WorkflowChatExecutionLaunchSurface.UNKNOWN,
                        sectionKinds = setOf(
                            WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                            WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT,
                        ),
                        supplementalInstructionPresent = true,
                        fallbackReason = WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA,
                        rawPromptDebugAvailable = true,
                    ),
                ),
                visibleContent = "## Execution Request",
            )
        }

        val snapshot = panel.snapshotForTest()
        assertEquals("legacy", snapshot.getValue("kind"))
        assertEquals("wf-legacy", snapshot.getValue("workflowId"))
        assertEquals("T-019", snapshot.getValue("taskId"))
        assertEquals("run-019", snapshot.getValue("runId"))
        assertEquals("MISSING_PRESENTATION_METADATA", snapshot.getValue("fallbackReason"))

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.note.legacy")))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.section.codeContext")))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.section.artifactSummaries")))
    }

    private fun <T> runOnEdtResult(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return action()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait {
            result = action()
        }
        return result ?: error("Expected EDT result")
    }

    private fun collectDescendants(component: Component): List<Component> {
        val result = mutableListOf<Component>()
        result += component
        val container = component as? Container ?: return result
        container.components.forEach { child ->
            result += collectDescendants(child)
        }
        return result
    }
}
