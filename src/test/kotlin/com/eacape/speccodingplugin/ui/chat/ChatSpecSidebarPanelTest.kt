package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class ChatSpecSidebarPanelTest {

    @Test
    fun `focus workflow should render preferred phase content`() {
        val workflow = workflowWithDocs(
            id = "spec-201",
            currentPhase = SpecPhase.DESIGN,
            docs = mapOf(
                SpecPhase.DESIGN to specDocument(
                    phase = SpecPhase.DESIGN,
                    content = "## Design\n\n- component: checkout",
                    valid = true,
                ),
            ),
        )
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    if (workflowId == workflow.id) Result.success(workflow) else Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflow.id) },
            )
        }

        runOnEdt {
            panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.DESIGN)
        }

        val rendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(rendered.contains("component: checkout"))
        assertEquals(workflow.id, panel.currentFocusedWorkflowId())
    }

    @Test
    fun `refresh should fallback to latest workflow when no focus exists`() {
        val workflowA = workflowWithDocs(
            id = "spec-a",
            currentPhase = SpecPhase.SPECIFY,
            docs = mapOf(
                SpecPhase.SPECIFY to specDocument(
                    phase = SpecPhase.SPECIFY,
                    content = "workflow-a content",
                    valid = true,
                ),
            ),
        )
        val workflowB = workflowWithDocs(
            id = "spec-b",
            currentPhase = SpecPhase.IMPLEMENT,
            docs = mapOf(
                SpecPhase.IMPLEMENT to specDocument(
                    phase = SpecPhase.IMPLEMENT,
                    content = "workflow-b latest",
                    valid = false,
                ),
            ),
        )
        val store = mapOf(
            workflowA.id to workflowA,
            workflowB.id to workflowB,
        )
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    store[workflowId]?.let { Result.success(it) }
                        ?: Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflowA.id, workflowB.id) },
            )
        }

        runOnEdt {
            panel.refreshCurrentWorkflow()
        }

        val rendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(rendered.contains("workflow-b latest"))
        assertEquals(workflowB.id, panel.currentFocusedWorkflowId())
    }

    private fun specDocument(
        phase: SpecPhase,
        content: String,
        valid: Boolean,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "${phase.displayName} doc",
            ),
            validationResult = ValidationResult(valid = valid),
        )
    }

    private fun workflowWithDocs(
        id: String,
        currentPhase: SpecPhase,
        docs: Map<SpecPhase, SpecDocument>,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = currentPhase,
            documents = docs,
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            description = "test workflow",
        )
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private fun <T> runOnEdtResult(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        return result!!
    }
}
