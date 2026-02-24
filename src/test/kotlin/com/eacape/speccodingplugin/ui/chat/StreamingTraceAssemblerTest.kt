package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class StreamingTraceAssemblerTest {

    @Test
    fun `gbk mojibake output should be repaired to chinese`() {
        val expected = "把大模型调味包讲明白.md"
        val garbled = String(expected.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))
        val assembler = StreamingTraceAssembler()

        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.OUTPUT,
                detail = garbled,
                status = ChatTraceStatus.INFO,
            )
        )

        val snapshot = assembler.snapshot(content = "")
        assertEquals(1, snapshot.items.size)
        assertEquals(expected, snapshot.items.first().detail)
    }

    @Test
    fun `noise line should still be filtered after mojibake repair support`() {
        val assembler = StreamingTraceAssembler()

        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.OUTPUT,
                detail = "'C:\\Users\\12186\\.claude' ç═╬▌xóèij",
                status = ChatTraceStatus.ERROR,
            )
        )

        val snapshot = assembler.snapshot(content = "")
        assertTrue(snapshot.items.isEmpty())
    }
}
