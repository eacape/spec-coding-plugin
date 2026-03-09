package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecVerificationServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var configService: SpecProjectConfigService
    private lateinit var verificationService: SpecVerificationService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        configService = SpecProjectConfigService(project)
        verificationService = SpecVerificationService(project, storage, configService, SpecProcessRunner())
    }

    @Test
    fun `preview should return plan id normalized commands and policy summary`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              defaultWorkingDirectory: .
              defaultTimeoutMs: 45000
              defaultOutputLimitChars: 4096
              redactionPatterns:
                - "(?i)sessionId=\\S+"
              commands:
                - id: gradle-test
                  displayName: Gradle tests
                  command:
                    - ./gradlew.bat
                    - test
                    - --offline
                  workingDirectory: .
                  timeoutMs: 60000
                  outputLimitChars: 8192
                  redactionPatterns:
                    - "(?i)password=\\S+"
            """.trimIndent(),
        )
        val workflow = createWorkflow(workflowId = "wf-verify-preview")

        val plan = verificationService.preview(workflow.id)
        val preparedRequests = verificationService.resolveExecutionRequests(workflow.id, plan.planId)

        assertTrue(WorkflowIdGenerator.isValid(plan.planId, "verify-plan"))
        assertEquals(workflow.id, plan.workflowId)
        assertEquals(StageId.VERIFY, plan.currentStage)
        assertEquals(VerifyPlanConfigSource.WORKFLOW_PINNED, plan.policy.configSource)
        assertEquals(workflow.configPinHash, plan.policy.workflowConfigPinHash)
        assertEquals(workflow.configPinHash, plan.policy.effectiveConfigHash)
        assertEquals(false, plan.policy.usesPinnedSnapshot)
        assertEquals(true, plan.policy.confirmationRequired)
        assertEquals(listOf("Review verify commands before execution."), plan.policy.confirmationReasons)
        assertEquals(listOf("gradle-test"), plan.commands.map(VerifyPlanCommand::commandId))
        assertEquals(listOf("./gradlew.bat", "test", "--offline"), plan.commands.single().command)
        assertEquals(tempDir.toAbsolutePath().normalize(), plan.commands.single().workingDirectory)
        assertEquals(60_000, plan.commands.single().timeoutMs)
        assertEquals(8_192, plan.commands.single().outputLimitChars)
        assertEquals(
            listOf("(?i)sessionId=\\S+", "(?i)password=\\S+"),
            plan.commands.single().redactionPatterns,
        )
        assertEquals(listOf("gradle-test"), preparedRequests.map(VerifyCommandExecutionRequest::commandId))
    }

    @Test
    fun `preview should fall back to pinned config snapshot when project verify config drifts`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              commands:
                - id: gradle-test
                  command:
                    - ./gradlew.bat
                    - test
            """.trimIndent(),
        )
        val workflow = createWorkflow(workflowId = "wf-verify-pinned")

        Files.createDirectories(tempDir.resolve("scripts"))
        writeConfig(
            """
            schemaVersion: 1
            verify:
              defaultWorkingDirectory: scripts
              commands:
                - id: npm-test
                  command:
                    - npm
                    - test
            """.trimIndent(),
        )

        val plan = verificationService.preview(workflow.id)

        assertEquals(VerifyPlanConfigSource.WORKFLOW_PINNED, plan.policy.configSource)
        assertEquals(true, plan.policy.usesPinnedSnapshot)
        assertEquals(workflow.configPinHash, plan.policy.effectiveConfigHash)
        assertEquals(listOf("gradle-test"), plan.commands.map(VerifyPlanCommand::commandId))
        assertTrue(
            plan.policy.confirmationReasons.contains(
                "Current project config differs from the workflow pin; using the pinned config snapshot for this plan.",
            ),
        )
    }

    @Test
    fun `resolvePlan should reject stale plan after workflow stage changes`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              commands:
                - id: gradle-test
                  command:
                    - ./gradlew.bat
                    - test
            """.trimIndent(),
        )
        val workflow = createWorkflow(
            workflowId = "wf-verify-stale",
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
        )
        val plan = verificationService.preview(workflow.id)

        val updatedWorkflow = workflow.copy(
            currentPhase = phaseFor(StageId.ARCHIVE),
            currentStage = StageId.ARCHIVE,
            verifyEnabled = false,
            stageStates = buildStageStates(currentStage = StageId.ARCHIVE, verifyEnabled = false),
            updatedAt = workflow.updatedAt + 1,
        )
        storage.saveWorkflow(updatedWorkflow).getOrThrow()

        assertThrows(StaleVerifyPlanError::class.java) {
            verificationService.resolvePlan(workflow.id, plan.planId)
        }
    }

    private fun createWorkflow(
        workflowId: String,
        currentStage: StageId = StageId.VERIFY,
        verifyEnabled: Boolean = true,
    ): SpecWorkflow {
        val configPin = configService.createConfigPin(configService.load())
        storage.saveConfigPinSnapshot(workflowId, configPin).getOrThrow()
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = phaseFor(currentStage),
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Verify Preview Workflow",
            description = "verification preview",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = buildStageStates(currentStage = currentStage, verifyEnabled = verifyEnabled),
            currentStage = currentStage,
            verifyEnabled = verifyEnabled,
            configPinHash = configPin.hash,
            createdAt = 1L,
            updatedAt = 2L,
        )
        storage.saveWorkflow(workflow).getOrThrow()
        return workflow
    }

    private fun buildStageStates(
        currentStage: StageId,
        verifyEnabled: Boolean,
    ): Map<StageId, StageState> {
        val currentIndex = StageId.entries.indexOf(currentStage)
        val timestamp = "2026-03-09T00:00:00Z"
        return StageId.entries.associateWith { stageId ->
            val active = stageId != StageId.VERIFY || verifyEnabled
            val stageIndex = StageId.entries.indexOf(stageId)
            val status = when {
                !active -> StageProgress.NOT_STARTED
                stageId == currentStage -> StageProgress.IN_PROGRESS
                stageIndex < currentIndex -> StageProgress.DONE
                else -> StageProgress.NOT_STARTED
            }
            StageState(
                active = active,
                status = status,
                enteredAt = if (status == StageProgress.NOT_STARTED) null else timestamp,
                completedAt = if (status == StageProgress.DONE) timestamp else null,
            )
        }
    }

    private fun phaseFor(stageId: StageId): SpecPhase {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> SpecPhase.IMPLEMENT
        }
    }

    private fun writeConfig(raw: String) {
        val configPath = configService.configPath()
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "$raw\n", StandardCharsets.UTF_8)
    }
}
