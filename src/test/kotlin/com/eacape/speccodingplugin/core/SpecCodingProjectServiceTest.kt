package com.eacape.speccodingplugin.core

import com.eacape.speccodingplugin.llm.LlmRole
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecCodingProjectServiceTest {
    @Test
    fun `llm role enum includes core roles`() {
        assertTrue(LlmRole.entries.contains(LlmRole.SYSTEM))
        assertTrue(LlmRole.entries.contains(LlmRole.USER))
        assertTrue(LlmRole.entries.contains(LlmRole.ASSISTANT))
    }
}
