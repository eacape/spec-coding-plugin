package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowChatExecutionPromptDialogTest {

    @Test
    fun `parse should split execution prompt into lead lines and sections`() {
        val overview = WorkflowChatExecutionPromptDebugOverview.parse(
            """
            Interaction mode: workflow
            Workflow=wf-152 (docs: .spec-coding/specs/wf-152/{requirements,design,tasks}.md)
            Execution action: EXECUTE_WITH_AI
            Run ID: run-152

            ## Task
            Task ID: T-152
            Task Title: Improve internal prompt viewer

            ## Stage Context
            Current phase: IMPLEMENT
            Current stage: TASKS

            ## Execution Request
            Use the task-scoped context above to execute this structured task.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Interaction mode: workflow",
                "Workflow=wf-152 (docs: .spec-coding/specs/wf-152/{requirements,design,tasks}.md)",
                "Execution action: EXECUTE_WITH_AI",
                "Run ID: run-152",
            ),
            overview.leadLines,
        )
        assertEquals(listOf("Task", "Stage Context", "Execution Request"), overview.sections.map { it.title })
        assertTrue(overview.sections.first().lines.joinToString("\n").contains("Task ID: T-152"))
        assertTrue(overview.sections.last().lines.joinToString("\n").contains("Use the task-scoped context above"))
    }
}
