package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeStreamJsonParserTest {

    @Test
    fun `progress line should map to running trace event`() {
        val line = """{"type":"progress","message":"Reading src/main/kotlin/App.kt"}"""

        val chunk = ClaudeStreamJsonParser.parseLine(line)

        assertNotNull(chunk)
        assertEquals("", chunk!!.delta)
        assertNotNull(chunk.event)
        assertEquals(ChatTraceStatus.RUNNING, chunk.event!!.status)
        assertTrue(
            chunk.event!!.kind == ChatTraceKind.READ || chunk.event!!.kind == ChatTraceKind.TASK
        )
    }

    @Test
    fun `stream event text delta should emit assistant delta`() {
        val line =
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello, world"}}}"""

        val chunk = ClaudeStreamJsonParser.parseLine(line)

        assertNotNull(chunk)
        assertEquals("Hello, world", chunk!!.delta)
        assertNull(chunk.event)
    }

    @Test
    fun `tool summary should map to done tool event`() {
        val line = """{"type":"tool_use_summary","summary":"bash rg --files"}"""

        val chunk = ClaudeStreamJsonParser.parseLine(line)

        assertNotNull(chunk)
        assertNotNull(chunk!!.event)
        assertEquals(ChatTraceKind.TOOL, chunk.event!!.kind)
        assertEquals(ChatTraceStatus.DONE, chunk.event!!.status)
    }

    @Test
    fun `result error should map to error status`() {
        val line = """{"type":"result","subtype":"error","error":"command failed"}"""

        val chunk = ClaudeStreamJsonParser.parseLine(line)

        assertNotNull(chunk)
        assertNotNull(chunk!!.event)
        assertEquals(ChatTraceStatus.ERROR, chunk.event!!.status)
        assertTrue(chunk.event!!.detail.contains("command failed"))
    }

    @Test
    fun `non json workflow line should fallback to plain text parsing`() {
        val line = "[Task] collect project context"

        val chunk = ClaudeStreamJsonParser.parseLine(line)

        assertNotNull(chunk)
        assertEquals(line, chunk!!.delta)
        assertNotNull(chunk.event)
        assertEquals(ChatTraceKind.TASK, chunk.event!!.kind)
    }

    @Test
    fun `partial json fragment should be ignored`() {
        val chunk = ClaudeStreamJsonParser.parseLine("{\"type\":\"stream_event\"")
        assertNull(chunk)
    }
}
