package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecProjectConfigServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var service: SpecProjectConfigService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        service = SpecProjectConfigService(project)
    }

    @Test
    fun `load should return defaults when config file is missing`() {
        val config = service.load()

        assertEquals(SpecProjectConfig.SUPPORTED_SCHEMA_VERSION, config.schemaVersion)
        assertEquals(WorkflowTemplate.FULL_SPEC, config.defaultTemplate)
        assertEquals(WorkflowTemplate.entries.toSet(), config.templates.keys)
        assertEquals(WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC), config.policyFor(WorkflowTemplate.FULL_SPEC).definition)
        assertEquals(SpecGatePolicy(), config.gate)
        assertTrue(config.rules.isEmpty())
    }

    @Test
    fun `load should merge template gate and rule overrides`() {
        writeConfig(
            """
            schemaVersion: 1
            defaultTemplate: QUICK_TASK
            templates:
              FULL_SPEC:
                verifyEnabled: true
              DIRECT_IMPLEMENT:
                stagePlan:
                  - IMPLEMENT
                  - id: VERIFY
                    optional: true
                    defaultEnabled: true
                  - ARCHIVE
            gate:
              allowWarning: false
              allowJump: false
            rules:
              task-structure:
                enabled: false
                severity: WARNING
              verify-conclusion:
                severity: ERROR
            """.trimIndent(),
        )

        val config = service.load()

        assertEquals(WorkflowTemplate.QUICK_TASK, config.defaultTemplate)
        assertTrue(config.policyFor(WorkflowTemplate.FULL_SPEC).verifyEnabledByDefault)
        assertEquals(
            listOf(StageId.IMPLEMENT, StageId.VERIFY, StageId.ARCHIVE),
            config.policyFor(WorkflowTemplate.DIRECT_IMPLEMENT).definition.stagePlan.map { it.id },
        )

        assertFalse(config.gate.allowWarningAdvance)
        assertFalse(config.gate.allowJump)
        assertTrue(config.gate.requireWarningConfirmation)
        assertTrue(config.gate.jumpRequiresMinimalGate)
        assertTrue(config.gate.allowRollback)

        assertEquals(2, config.rules.size)
        assertEquals(false, config.rules.getValue("task-structure").enabled)
        assertEquals(GateStatus.WARNING, config.rules.getValue("task-structure").severity)
        assertEquals(true, config.rules.getValue("verify-conclusion").enabled)
        assertEquals(GateStatus.ERROR, config.rules.getValue("verify-conclusion").severity)
    }

    @Test
    fun `load should reject unsupported schema version`() {
        writeConfig(
            """
            schemaVersion: 2
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.load()
        }
        assertTrue(error.message?.contains("Unsupported config schemaVersion") == true)
    }

    @Test
    fun `load should reject invalid template stage plan`() {
        writeConfig(
            """
            schemaVersion: 1
            templates:
              QUICK_TASK:
                stagePlan:
                  - TASKS
                  - IMPLEMENT
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.load()
        }
        assertTrue(error.message?.contains("must end with ARCHIVE") == true)
    }

    private fun writeConfig(raw: String) {
        val configPath = service.configPath()
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            "$raw\n",
            StandardCharsets.UTF_8,
        )
    }
}
