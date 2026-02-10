package com.eacape.speccodingplugin.engine

/**
 * OpenAI Codex CLI 引擎适配
 */
class OpenAiCodexEngine(
    cliPath: String,
) : CliEngine(
    id = "openai-codex",
    displayName = "OpenAI Codex CLI",
    capabilities = setOf(
        EngineCapability.CODE_GENERATION,
        EngineCapability.CODE_REVIEW,
        EngineCapability.REFACTOR,
        EngineCapability.TEST_GENERATION,
        EngineCapability.BUG_FIX,
        EngineCapability.EXPLANATION,
    ),
    cliPath = cliPath,
) {

    override fun buildCommandArgs(request: EngineRequest): List<String> {
        val args = mutableListOf<String>()

        args.add("--prompt")
        args.add(request.prompt)

        request.context.workingDirectory?.let {
            args.add("--cwd")
            args.add(it)
        }

        request.context.currentFile?.let {
            args.add("--file")
            args.add(it)
        }

        return args
    }

    override fun parseStreamLine(line: String): EngineChunk? {
        if (line.isBlank()) return null
        return EngineChunk(delta = line + "\n")
    }

    override suspend fun getVersion(): String? {
        return try {
            val process = ProcessBuilder(listOf("codex", "--version"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            null
        }
    }
}
