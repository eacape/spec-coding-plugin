package com.eacape.speccodingplugin.engine

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths

/**
 * CLI 引擎基类
 * 封装通过命令行调用外部 AI 工具的通用逻辑
 */
abstract class CliEngine(
    override val id: String,
    override val displayName: String,
    override val capabilities: Set<EngineCapability>,
    private val cliPath: String,
) : CodeGenerationEngine {

    private val logger = thisLogger()

    @Volatile
    private var _status: EngineStatus = EngineStatus.STOPPED

    override val status: EngineStatus get() = _status

    override suspend fun start() {
        _status = EngineStatus.STARTING
        val result = healthCheck()
        _status = if (result.healthy) EngineStatus.RUNNING else EngineStatus.ERROR
    }

    override suspend fun stop() {
        _status = EngineStatus.STOPPED
    }

    override suspend fun healthCheck(): EngineHealthResult {
        return withContext(Dispatchers.IO) {
            try {
                val path = Paths.get(cliPath)
                if (!Files.exists(path)) {
                    return@withContext EngineHealthResult(
                        healthy = false,
                        status = EngineStatus.ERROR,
                        message = "CLI not found at: $cliPath"
                    )
                }

                val version = getVersion()
                EngineHealthResult(
                    healthy = true,
                    status = EngineStatus.RUNNING,
                    message = "OK",
                    version = version
                )
            } catch (e: Exception) {
                EngineHealthResult(
                    healthy = false,
                    status = EngineStatus.ERROR,
                    message = e.message ?: "Health check failed"
                )
            }
        }
    }

    override suspend fun generate(
        request: EngineRequest
    ): EngineResponse = withContext(Dispatchers.IO) {
        try {
            val args = buildCommandArgs(request)
            val process = startProcess(args, request.context.workingDirectory)
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                EngineResponse(
                    content = output,
                    engineId = id,
                    success = true
                )
            } else {
                val error = process.errorStream.bufferedReader().readText()
                EngineResponse(
                    content = "",
                    engineId = id,
                    success = false,
                    error = error.ifBlank { "Exit code: $exitCode" }
                )
            }
        } catch (e: Exception) {
            EngineResponse(
                content = "",
                engineId = id,
                success = false,
                error = e.message
            )
        }
    }

    override fun stream(request: EngineRequest): Flow<EngineChunk> = flow {
        val args = buildCommandArgs(request)
        val process = startProcess(args, request.context.workingDirectory)
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val parsed = parseStreamLine(line!!)
            if (parsed != null) {
                emit(parsed)
            }
        }

        process.waitFor()
        emit(EngineChunk(delta = "", isLast = true))
    }.flowOn(Dispatchers.IO)

    override fun estimateTokens(content: String): Int {
        // 粗略估算：每 4 个字符约 1 个 token
        return content.length / 4
    }

    /** 构建命令行参数 */
    protected abstract fun buildCommandArgs(request: EngineRequest): List<String>

    /** 解析流式输出行 */
    protected abstract fun parseStreamLine(line: String): EngineChunk?

    /** 获取版本号 */
    protected abstract suspend fun getVersion(): String?

    private fun startProcess(
        args: List<String>,
        workingDir: String?
    ): Process {
        val command = listOf(cliPath) + args
        val builder = ProcessBuilder(command)

        if (workingDir != null) {
            builder.directory(java.io.File(workingDir))
        }

        builder.redirectErrorStream(false)
        return builder.start()
    }
}
