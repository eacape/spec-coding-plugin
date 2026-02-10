package com.eacape.speccodingplugin.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PromptInterpolatorTest {
    @Test
    fun `replaces placeholders with variables`() {
        val result = PromptInterpolator.render(
            template = "Project={{project_name}}, Lang={{ language }}",
            variables = mapOf(
                "project_name" to "spec-coding-plugin",
                "language" to "Kotlin",
            ),
        )

        assertEquals("Project=spec-coding-plugin, Lang=Kotlin", result)
    }

    @Test
    fun `keeps unresolved placeholders as-is`() {
        val result = PromptInterpolator.render(
            template = "Hello {{name}}, mode={{mode}}",
            variables = mapOf("name" to "Dev"),
        )

        assertEquals("Hello Dev, mode={{mode}}", result)
    }
}

