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

        // Modern Codex CLI runs non-interactive requests via `codex exec [PROMPT]`.
        args.add("exec")
        // Plugin can be used from Welcome/default workspaces that are not git repos.
        args.add("--skip-git-repo-check")

        request.options["model"]?.let {
            args.add("--model")
            args.add(it)
        }

        request.context.workingDirectory?.let {
            args.add("--cd")
            args.add(it)
        }

        // Ensure prompt text is always treated as positional input, not flags.
        args.add("--")
        args.add(request.prompt)

        return args
    }

    override fun parseStreamLine(line: String): EngineChunk? {
        if (line.isEmpty()) return null
        val eventLine = line.trimEnd('\n', '\r')
        return EngineChunk(
            delta = line,
            event = if (eventLine.isBlank()) null else CliProgressEventParser.parseStdout(eventLine),
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
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }
}
