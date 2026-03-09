package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineTemplateSwitchApplyTest {

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
    fun `applyTemplateSwitch should scaffold missing artifacts and persist rollback snapshot`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            """.trimIndent() + "\n",
        )
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Direct implement workflow",
            description = "switch apply",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.FULL_SPEC).getOrThrow()
        val applied = engine.applyTemplateSwitch(created.id, preview.previewId).getOrThrow()
        val workflowDir = workflowDirFor(created.id)

        assertTrue(WorkflowIdGenerator.isValid(applied.switchId, prefix = "switch"))
        assertEquals(preview.previewId, applied.previewId)
        assertEquals(WorkflowTemplate.FULL_SPEC, applied.workflow.template)
        assertEquals(StageId.IMPLEMENT, applied.workflow.currentStage)
        assertTrue(applied.workflow.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertTrue(applied.workflow.stageStates.getValue(StageId.DESIGN).active)
        assertTrue(applied.workflow.stageStates.getValue(StageId.TASKS).active)
        assertEquals(StageProgress.NOT_STARTED, applied.workflow.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertEquals(StageProgress.NOT_STARTED, applied.workflow.stageStates.getValue(StageId.DESIGN).status)
        assertEquals(StageProgress.IN_PROGRESS, applied.workflow.stageStates.getValue(StageId.IMPLEMENT).status)
        assertEquals(setOf("requirements.md", "design.md"), applied.generatedArtifacts.toSet())
        assertTrue(Files.exists(workflowDir.resolve("requirements.md")))
        assertTrue(Files.exists(workflowDir.resolve("design.md")))
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertNotNull(applied.beforeSnapshotId)
        assertNotNull(applied.afterSnapshotId)

        val event = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.TEMPLATE_SWITCHED }
        assertEquals(applied.switchId, event.details["switchId"])
        assertEquals(preview.previewId, event.details["previewId"])
        assertEquals("DIRECT_IMPLEMENT", event.details["fromTemplate"])
        assertEquals("FULL_SPEC", event.details["toTemplate"])
        assertEquals("requirements.md,design.md", event.details["generatedArtifacts"])

        val rollbackSnapshotId = event.details["beforeSnapshotId"]
        assertNotNull(rollbackSnapshotId)
        val snapshotWorkflow = storage.loadWorkflowSnapshot(created.id, rollbackSnapshotId!!).getOrThrow()
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, snapshotWorkflow.template)
        assertFalse(snapshotWorkflow.stageStates.getValue(StageId.REQUIREMENTS).active)
    }

    @Test
    fun `rollbackTemplateSwitch should restore previous metadata without deleting scaffolded artifacts`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            """.trimIndent() + "\n",
        )
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Direct implement workflow",
            description = "switch rollback",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.FULL_SPEC).getOrThrow()
        val applied = engine.applyTemplateSwitch(created.id, preview.previewId).getOrThrow()
        val rolledBack = engine.rollbackTemplateSwitch(created.id, applied.switchId).getOrThrow()
        val workflowDir = workflowDirFor(created.id)

        assertEquals(applied.switchId, rolledBack.switchId)
        assertEquals(applied.beforeSnapshotId, rolledBack.restoredFromSnapshotId)
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, rolledBack.workflow.template)
        assertEquals(StageId.IMPLEMENT, rolledBack.workflow.currentStage)
        assertFalse(rolledBack.workflow.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertFalse(rolledBack.workflow.stageStates.getValue(StageId.DESIGN).active)
        assertTrue(Files.exists(workflowDir.resolve("requirements.md")))
        assertTrue(Files.exists(workflowDir.resolve("design.md")))

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, reloaded.template)
        assertFalse(reloaded.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertFalse(reloaded.stageStates.getValue(StageId.DESIGN).active)

        val event = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.TEMPLATE_SWITCH_ROLLED_BACK }
        assertEquals(applied.switchId, event.details["switchId"])
        assertEquals(applied.beforeSnapshotId, event.details["restoredFromSnapshotId"])
        assertEquals("FULL_SPEC", event.details["fromTemplate"])
        assertEquals("DIRECT_IMPLEMENT", event.details["toTemplate"])
    }

    private fun writeProjectConfig(content: String) {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, content)
    }

    private fun workflowDirFor(workflowId: String): Path {
        return tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
    }

    private fun loadAuditEvents(workflowId: String): List<SpecAuditEvent> {
        val auditPath = workflowDirFor(workflowId)
            .resolve(".history")
            .resolve("audit.yaml")
        return SpecAuditLogCodec.decodeDocuments(Files.readString(auditPath))
    }
}
