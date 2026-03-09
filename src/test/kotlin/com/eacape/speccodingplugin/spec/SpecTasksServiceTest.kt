package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecTasksServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var tasksService: SpecTasksService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        tasksService = SpecTasksService(project)
    }

    @Test
    fun `parseDocument should sort structured tasks by id and preserve handwritten task markdown`() {
        val markdown = """
            # Implement Document

            ## Task List

            ### T-010: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            #### Handwritten note
            This note must stay attached to T-010.
            ### T-002: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Finish this first.
            ### Notes
            This is not a structured task heading and must remain in the current task body.
            ## Test Plan
            - [ ] Re-run tasks service checks.
        """.trimIndent()

        val parsed = tasksService.parseDocument(markdown)

        assertTrue(parsed.issues.isEmpty())
        assertEquals(listOf("T-002", "T-010"), parsed.tasksById.map { task -> task.id })
        assertEquals(listOf("T-010", "T-002"), parsed.taskSectionsInSourceOrder.map { section -> section.entry.id })
        assertTrue(
            parsed.taskSectionsById.first { section -> section.entry.id == "T-002" }
                .sectionMarkdown
                .contains("### Notes\nThis is not a structured task heading"),
        )
        assertTrue(
            parsed.taskSectionsById.first { section -> section.entry.id == "T-010" }
                .sectionMarkdown
                .contains("#### Handwritten note\nThis note must stay attached to T-010."),
        )
        assertTrue(parsed.trailingMarkdown.startsWith("## Test Plan"))
    }

    @Test
    fun `stabilizeTaskArtifact should rewrite tasks md in id order and keep trailing sections`() {
        val workflowId = "wf-tasks-service"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-010: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            #### Handwritten note
            This note must stay attached to T-010.
            ### T-002: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Finish this first.
            ### Notes
            This is not a structured task heading and must remain in the current task body.
            ## Test Plan
            - [ ] Re-run tasks service checks.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val stabilized = tasksService.stabilizeTaskArtifact(workflowId)
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals(listOf("T-002", "T-010"), stabilized?.tasksById?.map { task -> task.id })
        assertTrue(persisted.indexOf("### T-002: First task") < persisted.indexOf("### T-010: Second task"))
        assertTrue(persisted.contains("### Notes\nThis is not a structured task heading and must remain in the current task body."))
        assertTrue(persisted.contains("#### Handwritten note\nThis note must stay attached to T-010."))
        assertTrue(persisted.indexOf("## Test Plan") > persisted.indexOf("### T-010: Second task"))
    }

    @Test
    fun `addTask should allocate next id fill defaults and preserve handwritten sections`() {
        val workflowId = "wf-tasks-add"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-003: Third task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            ### T-001: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Handwritten paragraph that must stay with the first task.

            ## Test Plan
            - [ ] Keep trailing section.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val createdTask = tasksService.addTask(
            workflowId = workflowId,
            title = "Fourth task",
            priority = TaskPriority.P2,
            dependsOn = listOf("T-001"),
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals("T-004", createdTask.id)
        assertEquals(TaskStatus.PENDING, createdTask.status)
        assertEquals(TaskPriority.P2, createdTask.priority)
        assertEquals(listOf("T-001"), createdTask.dependsOn)
        assertTrue(createdTask.relatedFiles.isEmpty())
        assertNull(createdTask.verificationResult)
        assertTrue(persisted.indexOf("### T-001: First task") < persisted.indexOf("### T-003: Third task"))
        assertTrue(persisted.indexOf("### T-003: Third task") < persisted.indexOf("### T-004: Fourth task"))
        assertTrue(persisted.contains("Handwritten paragraph that must stay with the first task."))
        assertTrue(persisted.contains("## Test Plan\n- [ ] Keep trailing section."))
        assertTrue(persisted.contains("dependsOn:\n  - T-001"))
        assertTrue(persisted.contains("verificationResult: null"))
    }

    @Test
    fun `addTask should insert before trailing sections when task list is empty`() {
        val workflowId = "wf-tasks-empty-add"
        val markdown = """
            # Implement Document

            ## Task List

            ## Test Plan
            - [ ] Existing verification notes.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val createdTask = tasksService.addTask(
            workflowId = workflowId,
            title = "Bootstrap task",
            priority = TaskPriority.P0,
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals("T-001", createdTask.id)
        assertTrue(persisted.indexOf("### T-001: Bootstrap task") < persisted.indexOf("## Test Plan"))
        assertTrue(persisted.contains("status: PENDING"))
    }

    @Test
    fun `removeTask should delete only target section and keep trailing markdown`() {
        val workflowId = "wf-tasks-remove"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            First task body.

            ### T-002: Second task
            ```spec-task
            status: IN_PROGRESS
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Second task body.
            #### Keep this note
            This note must remain after deletion.

            ## Test Plan
            - [ ] Run targeted checks.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        tasksService.removeTask(workflowId, "T-001")
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertFalse(persisted.contains("### T-001: First task"))
        assertTrue(persisted.contains("### T-002: Second task"))
        assertTrue(persisted.contains("#### Keep this note\nThis note must remain after deletion."))
        assertTrue(persisted.contains("## Test Plan\n- [ ] Run targeted checks."))
    }

    @Test
    fun `removeTask should fail when task id is missing`() {
        val workflowId = "wf-tasks-remove-missing"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val error = assertThrows(MissingStructuredTaskError::class.java) {
            tasksService.removeTask(workflowId, "T-999")
        }

        assertEquals("Task not found: T-999", error.message)
    }
}
