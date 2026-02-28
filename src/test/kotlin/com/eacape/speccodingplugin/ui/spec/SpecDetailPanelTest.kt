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
        assertTrue(panel.isInputSectionVisibleForTest())
        assertFalse(panel.isBottomCollapsedForChecklistForTest())
        val readyStates = panel.buttonStatesForTest()
        assertTrue(readyStates["confirmGenerateEnabled"] as Boolean)
        assertTrue(readyStates["regenerateClarificationEnabled"] as Boolean)
        assertTrue(readyStates["skipClarificationEnabled"] as Boolean)
        assertTrue(readyStates["cancelClarificationEnabled"] as Boolean)
    }

    @Test
    fun `clarification checklist should support click-to-confirm without manual input`() {
        var confirmedInput: String? = null
        var confirmedContext: String? = null
        val panel = createPanel(
            onClarificationConfirm = { input, context ->
                confirmedInput = input
                confirmedContext = context
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-checklist",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist",
            description = "Clarification checklist",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "RunnerGo needs redis nodes support",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "是否需要多机房容灾？",
                "性能指标要求是多少？",
            ),
        )

        assertFalse(panel.isInputEditableForTest())
        assertFalse(panel.isInputSectionVisibleForTest())
        assertTrue(panel.isBottomCollapsedForChecklistForTest())
        panel.clickConfirmGenerateForTest()
        assertEquals(null, confirmedContext)
        assertTrue(panel.currentValidationTextForTest().contains("Please add confirmed details"))

        panel.toggleClarificationQuestionForTest(0)
        panel.clickConfirmGenerateForTest()
        assertEquals(null, confirmedContext)
        assertTrue(panel.currentValidationTextForTest().contains("Please add details for confirmed item"))

        panel.setClarificationQuestionDetailForTest(0, "至少两地三中心，RPO<5s，RTO<30s")
        panel.clickConfirmGenerateForTest()

        assertEquals("RunnerGo needs redis nodes support", confirmedInput)
        assertTrue((confirmedContext ?: "").contains("**Confirmed Clarification Points**"))
        assertTrue((confirmedContext ?: "").contains("是否需要多机房容灾？"))
        assertTrue((confirmedContext ?: "").contains("Detail: 至少两地三中心，RPO<5s，RTO<30s"))
        assertFalse((confirmedContext ?: "").contains("## "))
    }

    @Test
    fun `clarification checklist should support not applicable and detail serialization without checkbox markers`() {
        var confirmedContext: String? = null
        val panel = createPanel(
            onClarificationConfirm = { _, context ->
                confirmedContext = context
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-checklist-tristate",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Tri-state",
            description = "Clarification checklist tri-state",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build a storage scheduler",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "是否要求跨区域容灾？",
                "目标吞吐量是多少？",
            ),
        )

        panel.toggleClarificationQuestionForTest(0)
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(0))
        panel.setClarificationQuestionDetailForTest(0, "容灾等级按 P0 执行")

        panel.markClarificationQuestionNotApplicableForTest(1)
        assertEquals("NOT_APPLICABLE", panel.currentChecklistDecisionForTest(1))

        panel.clickConfirmGenerateForTest()

        val context = confirmedContext ?: ""
        assertTrue(context.contains("**Confirmed Clarification Points**"))
        assertTrue(context.contains("是否要求跨区域容灾？"))
        assertTrue(context.contains("Detail: 容灾等级按 P0 执行"))
        assertTrue(context.contains("**Not Applicable Clarification Points**"))
        assertTrue(context.contains("目标吞吐量是多少？"))
        assertFalse(context.contains("[x]"))
        assertFalse(context.contains("[ ]"))
        assertFalse(context.contains("## "))
    }

    @Test
    fun `clarification checklist should auto collapse previous detail editor when switching items`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-checklist-auto-collapse",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Collapse",
            description = "clarification checklist collapse",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "clarify details",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "问题A",
                "问题B",
            ),
        )

        panel.toggleClarificationQuestionForTest(0)
        panel.setClarificationQuestionDetailForTest(0, "A 的补充")
        assertEquals(0, panel.activeChecklistDetailIndexForTest())
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(0))

        panel.toggleClarificationQuestionForTest(1)
        assertEquals(1, panel.activeChecklistDetailIndexForTest())
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(0))
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(1))
    }

    @Test
    fun `clarification checklist should lock edits after confirm generate`() {
        var confirmCalls = 0
        val panel = createPanel(
            onClarificationConfirm = { _, _ ->
                confirmCalls += 1
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-checklist-lock-after-confirm",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Lock",
            description = "clarification checklist lock",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "clarify details",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "问题A",
                "问题B",
            ),
        )
        panel.toggleClarificationQuestionForTest(0)
        panel.setClarificationQuestionDetailForTest(0, "A 的补充")
        val originalDetail = panel.currentChecklistDetailForTest(0)

        panel.clickConfirmGenerateForTest()

        assertEquals(1, confirmCalls)
        assertTrue(panel.isClarificationChecklistReadOnlyForTest())

        panel.toggleClarificationQuestionForTest(1)
        assertEquals("UNDECIDED", panel.currentChecklistDecisionForTest(1))

        panel.setClarificationQuestionDetailForTest(0, "被忽略的修改")
        assertEquals(originalDetail, panel.currentChecklistDetailForTest(0))
    }

    @Test
    fun `clarification collapse toggle should use explicit text labels`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-clarify-toggle-text",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify Toggle Text",
            description = "clarify toggle text",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build scheduler",
            questionsMarkdown = "1. Should support offline?",
            suggestedDetails = "",
        )

        assertEquals("Collapse", panel.clarificationQuestionsToggleTextForTest())
        panel.toggleClarificationQuestionsExpandedForTest()
        assertEquals("Expand", panel.clarificationQuestionsToggleTextForTest())
    }

    @Test
    fun `checklist markdown bold markers should be parsed into bold segments`() {
        val panel = createPanel()

        val segments = panel.parseChecklistQuestionSegmentsForTest("**VIBE 你好** 和 **SPEC** 需要确认")

        assertEquals(
            listOf(
                "VIBE 你好" to true,
                " 和 " to false,
                "SPEC" to true,
                " 需要确认" to false,
            ),
            segments,
        )
    }

    @Test
    fun `process timeline should show entries and clear properly`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-process",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Process",
            description = "timeline",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.appendProcessTimelineEntry(
            text = "Prepare clarification context",
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )
        panel.appendProcessTimelineEntry(
            text = "Calling model to generate content (30%)",
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )

        assertTrue(panel.isProcessTimelineVisibleForTest())
        val timelineText = panel.currentProcessTimelineTextForTest()
        assertTrue(timelineText.contains("Prepare clarification context"))
        assertTrue(timelineText.contains("Calling model to generate content"))

        panel.clearProcessTimeline()

        assertFalse(panel.isProcessTimelineVisibleForTest())
        assertEquals("", panel.currentProcessTimelineTextForTest())
    }

    @Test
    fun `process timeline should support collapse toggle`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-process-collapse",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Process Collapse",
            description = "timeline collapse",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.appendProcessTimelineEntry(
            text = "Prepare clarification context",
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )

        assertTrue(panel.isProcessTimelineExpandedForTest())
        panel.toggleProcessTimelineExpandedForTest()
        assertFalse(panel.isProcessTimelineExpandedForTest())
        panel.toggleProcessTimelineExpandedForTest()
        assertTrue(panel.isProcessTimelineExpandedForTest())
    }

    @Test
    fun `clarification sections should support collapse toggles`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-clarify-collapse",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify Collapse",
            description = "clarify collapse",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build scheduler",
            questionsMarkdown = "1. Should support offline?",
            suggestedDetails = "",
        )

        assertTrue(panel.isClarificationQuestionsExpandedForTest())
        assertTrue(panel.isClarificationPreviewExpandedForTest())
        panel.toggleClarificationQuestionsExpandedForTest()
        panel.toggleClarificationPreviewExpandedForTest()
        assertFalse(panel.isClarificationQuestionsExpandedForTest())
        assertFalse(panel.isClarificationPreviewExpandedForTest())
    }

    @Test
    fun `clarification reply input should autosave on text changes`() {
        var autosaveCalls = 0
        var autosavedContext = ""
        var autosavedQuestions = emptyList<String>()
        val panel = createPanel(
            onClarificationDraftAutosave = { _, context, _, questions ->
                autosaveCalls += 1
                autosavedContext = context
                autosavedQuestions = questions
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-clarify-autosave",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify Autosave",
            description = "clarify autosave",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build storage service",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "是否需要多租户隔离？",
                "是否需要跨区域容灾？",
            ),
        )
        panel.toggleClarificationQuestionForTest(0)
        panel.setClarificationQuestionDetailForTest(0, "需要，按租户维度做资源与权限隔离")

        assertTrue(autosaveCalls > 0)
        assertTrue(autosavedContext.contains("Detail: 需要，按租户维度做资源与权限隔离"))
        assertEquals(2, autosavedQuestions.size)
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
    fun `clicking generate with empty requirements should prompt input and not trigger generation`() {
        var generateCallCount = 0
        val panel = createPanel(
            onGenerate = { generateCallCount += 1 },
        )
        val workflow = SpecWorkflow(
            id = "wf-generate-empty",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Generate Empty",
            description = "Generate empty workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.setInputTextForTest("")

        panel.clickGenerateForTest()

        assertEquals(0, generateCallCount)
        assertEquals("Please provide requirements before generating.", panel.currentValidationTextForTest())
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
    fun `validation failure should keep clarification actions when clarification state exists`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-validation-clarify",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "draft tasks",
                    valid = false,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Validation Clarify",
            description = "Validation with clarify state",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.IMPLEMENT,
            input = "",
            questionsMarkdown = "1. Is rollback required?",
            suggestedDetails = "- keep rollback steps",
        )
        panel.setInputTextForTest("- keep rollback steps")

        panel.showValidationFailureInteractive(
            phase = SpecPhase.IMPLEMENT,
            validation = ValidationResult(
                valid = false,
                errors = listOf("Missing required section: Implementation Steps"),
            ),
        )

        val states = panel.buttonStatesForTest()
        assertTrue(states["confirmGenerateEnabled"] as Boolean)
        assertTrue(states["regenerateClarificationEnabled"] as Boolean)
        assertTrue(states["skipClarificationEnabled"] as Boolean)
        assertEquals("- keep rollback steps", panel.currentInputTextForTest())
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
        onClarificationRegenerate: (String, String) -> Unit = { _, _ -> },
        onClarificationDraftAutosave: (String, String, String, List<String>) -> Unit = { _, _, _, _ -> },
    ): SpecDetailPanel {
        return SpecDetailPanel(
            onGenerate = onGenerate,
            onClarificationConfirm = onClarificationConfirm,
            onClarificationRegenerate = onClarificationRegenerate,
            onClarificationSkip = {},
            onClarificationCancel = {},
            onNextPhase = {},
            onGoBack = {},
            onComplete = {},
            onPauseResume = {},
            onOpenInEditor = {},
            onShowHistoryDiff = {},
            onSaveDocument = { _, _, onDone -> onDone(Result.failure(IllegalStateException("not implemented"))) },
            onClarificationDraftAutosave = onClarificationDraftAutosave,
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
