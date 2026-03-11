package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecEngineTemplateSwitchHistoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `listTemplateSwitchHistory should surface latest apply details`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "History Workflow",
            description = "history",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.DIRECT_IMPLEMENT).getOrThrow()
        engine.applyTemplateSwitch(created.id, preview.previewId).getOrThrow()

        val history = engine.listTemplateSwitchHistory(created.id).getOrThrow()

        assertEquals(1, history.size)
        val entry = history.single()
        assertEquals(WorkflowTemplate.FULL_SPEC, entry.fromTemplate)
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, entry.toTemplate)
        assertEquals(preview.previewId, entry.previewId)
        assertFalse(entry.rolledBack)
        assertTrue(entry.beforeSnapshotId?.isNotBlank() == true)
    }

    @Test
    fun `listTemplateSwitchHistory should mark switch as rolled back after rollback`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Rollback History Workflow",
            description = "rollback history",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.DIRECT_IMPLEMENT).getOrThrow()
        val applied = engine.applyTemplateSwitch(created.id, preview.previewId).getOrThrow()
        engine.rollbackTemplateSwitch(created.id, applied.switchId).getOrThrow()

        val history = engine.listTemplateSwitchHistory(created.id).getOrThrow()

        assertEquals(1, history.size)
        assertTrue(history.single().rolledBack)
    }
}
