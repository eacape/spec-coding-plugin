package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPanelTest {

    @Test
    fun `showEmpty should clear preview and disable all actions`() {
        val panel = createPanel()

        panel.showEmpty()

        assertEquals("", panel.currentPreviewTextForTest())
        assertEquals("Select a workflow to view details", panel.currentValidationTextForTest())

        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertFalse(states["nextEnabled"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertFalse(states["pauseResumeEnabled"] as Boolean)
        assertFalse(states["openEditorEnabled"] as Boolean)
        assertFalse(states["historyDiffEnabled"] as Boolean)
    }

    @Test
    fun `updateWorkflow should show current phase preview and button states`() {
        val panel = createPanel()
        val designContent = """
            ## 架构设计
            - 使用三层架构

            ## 技术选型
            - Kotlin + IntelliJ Platform SDK

            ## 数据模型
            - SpecWorkflow / SpecDocument
        """.trimIndent()
        val workflow = SpecWorkflow(
            id = "wf-1",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = designContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Demo",
            description = "Demo workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        assertEquals(designContent, panel.currentPreviewTextForTest())
        assertEquals("Validation: PASSED", panel.currentValidationTextForTest())

        val states = panel.buttonStatesForTest()
        assertTrue(states["generateEnabled"] as Boolean)
        assertTrue(states["nextEnabled"] as Boolean)
        assertTrue(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertTrue(states["pauseResumeEnabled"] as Boolean)
        assertEquals("Pause", states["pauseResumeText"])
        assertTrue(states["openEditorEnabled"] as Boolean)
        assertTrue(states["historyDiffEnabled"] as Boolean)
    }

    @Test
    fun `updateWorkflow should follow current phase when requested`() {
        val panel = createPanel()
        val specifyContent = "requirements content"
        val designContent = "design content"

        val initial = SpecWorkflow(
            id = "wf-follow",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = specifyContent,
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = designContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Follow",
            description = "Follow current phase",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(initial)
        assertEquals(specifyContent, panel.currentPreviewTextForTest())
        panel.setInputTextForTest("temporary user input")

        val moved = initial.copy(currentPhase = SpecPhase.DESIGN, updatedAt = 3L)
        panel.updateWorkflow(moved)
        assertEquals(specifyContent, panel.currentPreviewTextForTest())
        assertEquals("temporary user input", panel.currentInputTextForTest())

        panel.updateWorkflow(moved, followCurrentPhase = true)
        assertEquals(designContent, panel.currentPreviewTextForTest())
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `updateWorkflow should enable complete only when implement document is valid`() {
        val panel = createPanel()

        val workflow = SpecWorkflow(
            id = "wf-2",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                    valid = true,
                )
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Impl",
            description = "Impl workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertTrue(states["completeEnabled"] as Boolean)
    }

    @Test
    fun `paused workflow should show resume text and disable generate related actions`() {
        val panel = createPanel()

        val workflow = SpecWorkflow(
            id = "wf-3",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                )
            ),
            status = WorkflowStatus.PAUSED,
            title = "Paused",
            description = "Paused workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertFalse(states["nextEnabled"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertTrue(states["pauseResumeEnabled"] as Boolean)
        assertEquals("Resume", states["pauseResumeText"])
        assertTrue(states["historyDiffEnabled"] as Boolean)
    }

    @Test
    fun `clarification loading should animate status and lock clarify actions until draft is ready`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-clarify",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = """
                        ## 功能需求
                        - 用户可创建任务
                        
                        ## 非功能需求
                        - 响应时间 < 1s
                        
                        ## 用户故事
                        As a user, I want to create tasks, so that I can track work.
                        
                        ## 验收标准
                        - [ ] 创建成功
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify",
            description = "Clarification workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showClarificationGenerating(
            phase = SpecPhase.SPECIFY,
            input = "build a todo app",
            suggestedDetails = "build a todo app",
        )

        assertTrue(panel.currentValidationTextForTest().contains("Generating clarification questions..."))
        assertFalse(panel.isClarificationPreviewVisibleForTest())
        val generatingStates = panel.buttonStatesForTest()
        assertFalse(generatingStates["confirmGenerateEnabled"] as Boolean)
        assertFalse(generatingStates["regenerateClarificationEnabled"] as Boolean)
        assertFalse(generatingStates["skipClarificationEnabled"] as Boolean)
        assertFalse(generatingStates["cancelClarificationEnabled"] as Boolean)

        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build a todo app",
            questionsMarkdown = "1. Should this support offline mode?",
            suggestedDetails = "- need offline cache",
        )

        val readyStatus = panel.currentValidationTextForTest()
        assertTrue(readyStatus.contains("Review clarification questions"))
        assertFalse(readyStatus.contains("◐"))
        assertTrue(panel.isClarificationPreviewVisibleForTest())
        val readyStates = panel.buttonStatesForTest()
        assertTrue(readyStates["confirmGenerateEnabled"] as Boolean)
        assertTrue(readyStates["regenerateClarificationEnabled"] as Boolean)
        assertTrue(readyStates["skipClarificationEnabled"] as Boolean)
        assertTrue(readyStates["cancelClarificationEnabled"] as Boolean)
    }

    @Test
    fun `clicking generate should clear input area`() {
        var generatedInput: String? = null
        val panel = createPanel(
            onGenerate = { generatedInput = it },
        )
        val workflow = SpecWorkflow(
            id = "wf-generate-clear",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Generate",
            description = "Generate workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.setInputTextForTest("new requirements input")

        panel.clickGenerateForTest()

        assertEquals("new requirements input", generatedInput)
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `validation failure should provide interactive repair guidance`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-validation",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "draft tasks",
                    valid = false,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Validation",
            description = "Validation workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showValidationFailureInteractive(
            phase = SpecPhase.IMPLEMENT,
            validation = ValidationResult(
                valid = false,
                errors = listOf("Missing required section: Implementation Steps"),
                warnings = listOf("Task count is low"),
                suggestions = listOf("Add priority labels"),
            ),
        )

        assertTrue(panel.currentValidationTextForTest().contains("Validation: FAILED"))
        val preview = panel.currentPreviewTextForTest()
        assertTrue(preview.contains("draft tasks"))
        assertTrue(preview.contains("Validation Issues"))
        assertTrue(preview.contains("Implementation Steps"))
        assertTrue(preview.contains("Task count is low"))

        val states = panel.buttonStatesForTest()
        assertTrue(states["generateEnabled"] as Boolean)
    }

    @Test
    fun `clarification confirm should allow empty details in implement phase`() {
        var confirmedInput: String? = null
        var confirmedContext: String? = null
        val panel = createPanel(
            onClarificationConfirm = { input, context ->
                confirmedInput = input
                confirmedContext = context
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-implement-clarify",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = """
                        ## 架构设计
                        - layered
                        
                        ## 技术选型
                        - Kotlin
                        
                        ## 数据模型
                        - Task
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Implement Clarify",
            description = "implement clarification",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.IMPLEMENT,
            input = "",
            questionsMarkdown = "1. 任务优先级如何拆分？",
            suggestedDetails = "",
        )
        panel.setInputTextForTest("")

        panel.clickConfirmGenerateForTest()

        assertEquals("", confirmedInput)
        assertEquals("", confirmedContext)
    }

    private fun createPanel(
        onGenerate: (String) -> Unit = {},
        onClarificationConfirm: (String, String) -> Unit = { _, _ -> },
    ): SpecDetailPanel {
        return SpecDetailPanel(
            onGenerate = onGenerate,
            onClarificationConfirm = onClarificationConfirm,
            onClarificationRegenerate = { _, _ -> },
            onClarificationSkip = {},
            onClarificationCancel = {},
            onNextPhase = {},
            onGoBack = {},
            onComplete = {},
            onPauseResume = {},
            onOpenInEditor = {},
            onShowHistoryDiff = {},
            onSaveDocument = { _, _, onDone -> onDone(Result.failure(IllegalStateException("not implemented"))) },
        )
    }

    private fun document(phase: SpecPhase, content: String, valid: Boolean): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "test",
            ),
            validationResult = ValidationResult(valid = valid),
        )
    }
}
