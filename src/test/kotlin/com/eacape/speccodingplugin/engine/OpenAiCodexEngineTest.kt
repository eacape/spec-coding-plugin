package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiCodexEngineTest {

    @Test
    fun `buildCommandArgs should append image flags before stdin marker`() {
        val engine = OpenAiCodexEngine("codex")
        val request = EngineRequest(
            prompt = "describe attached image",
            imagePaths = listOf("C:/tmp/a.png", "D:/tmp/b.jpg"),
            options = mapOf("model" to "gpt-5"),
        )

        val args = invokeBuildCommandArgs(engine, request)

        val firstImageFlagIndex = args.indexOf("--image")
        val stdinMarkerIndex = args.indexOf("--")

        assertTrue(firstImageFlagIndex >= 0, "Expected --image flag in args: $args")
        assertTrue(stdinMarkerIndex > firstImageFlagIndex, "Expected --image before stdin marker: $args")
        assertEquals(listOf("--image", "C:/tmp/a.png", "--image", "D:/tmp/b.jpg"), args.subList(firstImageFlagIndex, stdinMarkerIndex))
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildCommandArgs(engine: OpenAiCodexEngine, request: EngineRequest): List<String> {
        val method = engine::class.java.getDeclaredMethod("buildCommandArgs", EngineRequest::class.java)
        method.isAccessible = true
        return method.invoke(engine, request) as List<String>
    }
}
