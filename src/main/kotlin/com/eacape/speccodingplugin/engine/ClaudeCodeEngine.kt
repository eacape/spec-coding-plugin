package com.eacape.speccodingplugin.engine

/**
 * Claude Code CLI 引擎适配
 */
class ClaudeCodeEngine(
    cliPath: String,
) : CliEngine(
    id = "claude-code",
    displayName = "Claude Code CLI",
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

    override fun buildCommandArgs(
        request: EngineRequest
    ): List<String> {
        val args = mutableListOf<String>()
        args.add("--print")
        args.add("--output-format")
        args.add("text")

        request.options["model"]?.let {
            args.add("--model")
            args.add(it)
        }

        request.options["system_prompt"]?.let {
            args.add("--system-prompt")
            args.add(it)
        }

        request.options["max_tokens"]?.let {
            args.add("--max-tokens")
            args.add(it)
        }

        args.add(request.prompt)
        return args
    }

    override fun parseStreamLine(
        line: String
    ): EngineChunk? {
        if (line.isBlank()) return null
        return EngineChunk(
            delta = line + "\n",
            event = CliProgressEventParser.parseStdout(line),
        )
    }

    override suspend fun getVersion(): String? {
        return try {
            val command = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd", "/c", cliPath, "--version")
            } else {
                listOf(cliPath, "--version")
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true).start()
            val output = process.inputStream
                .bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }
}
