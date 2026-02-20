package com.eacape.speccodingplugin.llm

internal object LlmRequestContext {
    internal const val WORKING_DIRECTORY_METADATA_KEY = "working_directory"

    fun extractWorkingDirectory(request: LlmRequest): String? {
        return normalizeWorkingDirectory(request.workingDirectory)
            ?: normalizeWorkingDirectory(request.metadata[WORKING_DIRECTORY_METADATA_KEY])
    }

    fun normalizeWorkingDirectory(path: String?): String? {
        return path?.trim()?.ifBlank { null }
    }
}
