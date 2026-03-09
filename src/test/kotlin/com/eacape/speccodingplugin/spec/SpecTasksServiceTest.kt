package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
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

            ## 任务列表

            ### T-010: 第二个任务
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 保留这个 checklist
            #### 手写备注
            这里的说明必须跟着 T-010 一起移动。

            ### T-002: 第一个任务
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 先做这里
            ### Notes
            这不是新任务标题，必须保留在当前任务正文里。

            ## 测试计划
            - [ ] 回归 tasks 服务
        """.trimIndent()

        val parsed = tasksService.parseDocument(markdown)

        assertTrue(parsed.issues.isEmpty())
        assertEquals(listOf("T-002", "T-010"), parsed.tasksById.map { task -> task.id })
        assertEquals(listOf("T-010", "T-002"), parsed.taskSectionsInSourceOrder.map { section -> section.entry.id })
        assertTrue(
            parsed.taskSectionsById.first { section -> section.entry.id == "T-002" }
                .sectionMarkdown
                .contains("### Notes\n这不是新任务标题"),
        )
        assertTrue(
            parsed.taskSectionsById.first { section -> section.entry.id == "T-010" }
                .sectionMarkdown
                .contains("#### 手写备注\n这里的说明必须跟着 T-010 一起移动。"),
        )
        assertTrue(parsed.trailingMarkdown.startsWith("## 测试计划"))
    }

    @Test
    fun `stabilizeTaskArtifact should rewrite tasks md in id order and keep trailing sections`() {
        val workflowId = "wf-tasks-service"
        val markdown = """
            # Implement Document

            ## 任务列表

            ### T-010: 第二个任务
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 保留这个 checklist
            #### 手写备注
            这里的说明必须跟着 T-010 一起移动。

            ### T-002: 第一个任务
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 先做这里
            ### Notes
            这不是新任务标题，必须保留在当前任务正文里。

            ## 测试计划
            - [ ] 回归 tasks 服务
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val stabilized = tasksService.stabilizeTaskArtifact(workflowId)
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals(listOf("T-002", "T-010"), stabilized?.tasksById?.map { task -> task.id })
        assertTrue(persisted.indexOf("### T-002: 第一个任务") < persisted.indexOf("### T-010: 第二个任务"))
        assertTrue(persisted.contains("### Notes\n这不是新任务标题，必须保留在当前任务正文里。"))
        assertTrue(persisted.contains("#### 手写备注\n这里的说明必须跟着 T-010 一起移动。"))
        assertTrue(persisted.indexOf("## 测试计划") > persisted.indexOf("### T-010: 第二个任务"))
    }
}
