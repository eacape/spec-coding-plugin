package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecTaskMarkdownParserTest {

    @Test
    fun `parse should extract structured tasks from canonical markdown`() {
        val markdown = """
            ## 任务列表

            ### T-001: 定义任务模型
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles:
              - src/main/kotlin/com/eacape/speccodingplugin/spec/WorkflowDomainModels.kt
            verificationResult:
              conclusion: PASS
              runId: run-001
              summary: parser ok
              at: "2026-03-09T00:00:00Z"
            ```
            - [ ] 补充数据模型
        """.trimIndent()

        val parsed = SpecTaskMarkdownParser.parse(markdown)

        assertTrue(parsed.issues.isEmpty())
        assertEquals(1, parsed.tasks.size)
        val task = parsed.tasks.single()
        assertEquals("T-001", task.id)
        assertEquals("定义任务模型", task.title)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(TaskPriority.P0, task.priority)
        assertEquals(
            listOf("src/main/kotlin/com/eacape/speccodingplugin/spec/WorkflowDomainModels.kt"),
            task.relatedFiles,
        )
        assertEquals(VerificationConclusion.PASS, task.verificationResult?.conclusion)
        assertEquals(4, task.metadataLocation.startLine)
    }

    @Test
    fun `parse should report missing immediate spec-task block and invalid dependency id`() {
        val markdown = """
            ## 任务列表

            ### T-001: 缺少 metadata

            ```spec-task
            status: PENDING
            priority: P0
            dependsOn:
              - task-2
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        val parsed = SpecTaskMarkdownParser.parse(markdown)

        assertEquals(2, parsed.issues.size)
        assertEquals(3, parsed.issues.first().line)
        assertTrue(parsed.issues.first().message.contains("followed immediately"))
        assertTrue(parsed.issues.last().message.contains("immediately follow a task heading"))
    }
}
