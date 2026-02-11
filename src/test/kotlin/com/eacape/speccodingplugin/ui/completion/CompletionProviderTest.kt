package com.eacape.speccodingplugin.ui.completion

import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.skill.Skill
import com.eacape.speccodingplugin.skill.SkillRegistry
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompletionProviderTest {

    private lateinit var project: Project
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var promptManager: PromptManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        skillRegistry = mockk()
        promptManager = mockk()
    }

    @Test
    fun `slash completion should return skills from registry`() {
        every { skillRegistry.searchSkills("rev") } returns listOf(
            Skill(
                id = "review",
                name = "Code Review",
                description = "Analyze code quality",
                slashCommand = "review",
                promptTemplate = "review {{selected_code}}",
            )
        )
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.SLASH,
                triggerOffset = 0,
                query = "rev",
            )
        )

        assertEquals(1, completions.size)
        assertEquals("/review", completions[0].displayText)
        assertEquals("/review", completions[0].insertText)
        assertEquals("Analyze code quality", completions[0].description)
    }

    @Test
    fun `template completion should filter templates by query`() {
        every { skillRegistry.searchSkills(any()) } returns emptyList()
        every { promptManager.listPromptTemplates() } returns listOf(
            PromptTemplate(
                id = "design",
                name = "Design Plan",
                content = "design content",
                scope = PromptScope.PROJECT,
            ),
            PromptTemplate(
                id = "debug",
                name = "Debug Checklist",
                content = "debug content",
                scope = PromptScope.PROJECT,
            )
        )

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.ANGLE,
                triggerOffset = 0,
                query = "des",
            )
        )

        assertEquals(1, completions.size)
        assertEquals("Design Plan", completions[0].displayText)
        assertEquals("design content", completions[0].insertText)
        assertEquals("prompt template", completions[0].description)
    }

    @Test
    fun `file completion should return injected file candidates with context`() {
        every { skillRegistry.searchSkills(any()) } returns emptyList()
        every { promptManager.listPromptTemplates() } returns emptyList()

        val fileCompletion = CompletionItem(
            displayText = "Main.kt",
            insertText = "@src/Main.kt",
            description = "src/Main.kt",
            contextItem = ContextItem(
                type = ContextType.REFERENCED_FILE,
                label = "Main.kt",
                content = "",
                filePath = "/workspace/src/Main.kt",
                priority = 60,
            ),
        )

        val provider = createProvider(
            fileCompletions = listOf(fileCompletion),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.AT,
                triggerOffset = 0,
                query = "main",
            )
        )

        assertEquals(1, completions.size)
        assertEquals("Main.kt", completions[0].displayText)
        assertEquals("@src/Main.kt", completions[0].insertText)
        assertEquals("src/Main.kt", completions[0].description)
        assertEquals("Main.kt", completions[0].contextItem?.label)
        assertEquals(60, completions[0].contextItem?.priority)
        assertTrue(completions[0].contextItem?.filePath?.endsWith("/src/Main.kt") == true)
    }

    @Test
    fun `file completion should return empty when provider yields none`() {
        every { skillRegistry.searchSkills(any()) } returns emptyList()
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.AT,
                triggerOffset = 0,
                query = "config",
            )
        )

        assertTrue(completions.isEmpty())
    }

    @Test
    fun `symbol completion should return class matches when indexing is ready`() {
        every { skillRegistry.searchSkills(any()) } returns emptyList()
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = arrayOf("FooService", "BarService", "BazClient"),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "ser",
            )
        )

        assertEquals(2, completions.size)
        assertTrue(completions.any { it.displayText == "FooService" && it.insertText == "#FooService" })
        assertTrue(completions.any { it.displayText == "BarService" && it.insertText == "#BarService" })
    }

    @Test
    fun `symbol completion should return empty when project is in dumb mode`() {
        every { skillRegistry.searchSkills(any()) } returns emptyList()
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = true,
            classNames = arrayOf("FooService"),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "ser",
            )
        )

        assertTrue(completions.isEmpty())
    }

    @Test
    fun `symbol completion should return empty when query is too short`() {
        every { skillRegistry.searchSkills(any()) } returns emptyList()
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = arrayOf("FooService"),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "a",
            )
        )

        assertTrue(completions.isEmpty())
    }

    private fun createProvider(
        fileCompletions: List<CompletionItem>,
        isDumbMode: Boolean,
        classNames: Array<String>,
    ): CompletionProvider {
        return CompletionProvider(
            project = project,
            skillRegistryProvider = { skillRegistry },
            promptManagerProvider = { promptManager },
            fileCompletionsProvider = { fileCompletions },
            isDumbModeProvider = { isDumbMode },
            classNamesProvider = { classNames },
        )
    }
}
