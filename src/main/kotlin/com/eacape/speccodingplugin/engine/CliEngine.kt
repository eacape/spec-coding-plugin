package com.eacape.speccodingplugin.engine

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CLI 引擎基类
 * 封装通过命令行调用外部 AI 工具的通用逻辑
 */
abstract class CliEngine(
    override val id: String,
    override val displayName: String,
    override val capabilities: Set<EngineCapability>,
    val cliPath: String,
) : CodeGenerationEngine {

    private val logger = thisLogger()
    private val activeProcesses = ConcurrentHashMap<String, Process>()

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
                // 尝试直接执行获取版本号来验证 CLI 可用性
                // 不再依赖 Files.exists() 检查，因为裸命令名或 .cmd 文件可能无法通过路径检查
                val version = getVersion()
                if (version != null) {
                    EngineHealthResult(
                        healthy = true,
                        status = EngineStatus.RUNNING,
                        message = "OK",
                        version = version
                    )
                } else {
                    EngineHealthResult(
                        healthy = false,
                        status = EngineStatus.ERROR,
                        message = "CLI not available: $cliPath"
                    )
                }
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
        val requestId = request.options["requestId"] ?: System.nanoTime().toString()
        val timeoutSeconds = resolveTimeoutSeconds(request)
        try {
            val args = buildCommandArgs(request)
            val process = startProcess(args, request.context.workingDirectory)
            activeProcesses[requestId] = process
            val (timedOut, watchdogFuture) = startTimeoutWatchdog(process, timeoutSeconds)
            try {
                runCatching { process.outputStream.close() }
                val stderrFuture = CompletableFuture.supplyAsync {
                    process.errorStream.bufferedReader().use { it.readText() }
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                val stderr = runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrNull().orEmpty()
                if (timedOut.get()) {
                    return@withContext EngineResponse(
                        content = "",
                        engineId = id,
                        success = false,
                        error = stderr.ifBlank { "CLI request timed out after $timeoutSeconds seconds" },
                    )
                }

                if (exitCode == 0) {
                    EngineResponse(
                        content = output,
                        engineId = id,
                        success = true
                    )
                } else {
                    EngineResponse(
                        content = "",
                        engineId = id,
                        success = false,
                        error = stderr.ifBlank { "Exit code: $exitCode" }
                    )
                }
            } finally {
                watchdogFuture.cancel(true)
                activeProcesses.remove(requestId)
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
        val requestId = request.options["requestId"] ?: System.nanoTime().toString()
        val timeoutSeconds = resolveTimeoutSeconds(request)
        val args = buildCommandArgs(request)
        val process = startProcess(args, request.context.workingDirectory)
        activeProcesses[requestId] = process
        val (timedOut, watchdogFuture) = startTimeoutWatchdog(process, timeoutSeconds)
        try {
            runCatching { process.outputStream.close() }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            var emittedChunk = false
            while (reader.readLine().also { line = it } != null) {
                val parsed = parseStreamLine(line!!)
                if (parsed != null) {
                    emittedChunk = true
                    emit(parsed)
                }
            }

            val exitCode = process.waitFor()
            val stderr = runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            if (timedOut.get()) {
                val timeoutMessage = if (stderr.isBlank()) {
                    "CLI request timed out after $timeoutSeconds seconds"
                } else {
                    "CLI request timed out after $timeoutSeconds seconds: $stderr"
                }
                throw RuntimeException(timeoutMessage)
            }
            if (exitCode != 0) {
                throw RuntimeException(stderr.ifBlank { "CLI exited with code: $exitCode" })
            }
            if (!emittedChunk && stderr.isNotBlank()) {
                throw RuntimeException(stderr)
            }
            emit(EngineChunk(delta = "", isLast = true))
        } finally {
            watchdogFuture.cancel(true)
            activeProcesses.remove(requestId)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 取消指定请求的进程
     */
    fun cancelProcess(requestId: String) {
        activeProcesses.remove(requestId)?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                logger.info("Cancelled process for request: $requestId")
            }
        }
    }

    /**
     * 取消所有活跃进程
     */
    fun cancelAll() {
        activeProcesses.keys.toList().forEach { cancelProcess(it) }
    }

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

    private fun resolveTimeoutSeconds(request: EngineRequest): Long {
        val option = request.options["timeout_seconds"]?.toLongOrNull()
        if (option != null && option > 0) return option
        return DEFAULT_TIMEOUT_SECONDS
    }

    private fun startTimeoutWatchdog(process: Process, timeoutSeconds: Long): Pair<AtomicBoolean, CompletableFuture<Void>> {
        val timedOut = AtomicBoolean(false)
        val future = CompletableFuture.runAsync {
            try {
                Thread.sleep(timeoutSeconds * 1000)
                if (process.isAlive) {
                    timedOut.set(true)
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return timedOut to future
    }

    private fun startProcess(
        args: List<String>,
        workingDir: String?
    ): Process {
        val normalizedArgs = normalizeWindowsArgs(args)
        val directBuilder = ProcessBuilder(listOf(cliPath) + normalizedArgs)
        configureBuilder(directBuilder, workingDir)

        return try {
            directBuilder.start()
        } catch (e: Exception) {
            if (isWindows()) {
                logger.debug("Direct CLI start failed for '$cliPath', fallback to cmd /c: ${e.message}")
                val cmdBuilder = ProcessBuilder(listOf("cmd", "/c", cliPath) + normalizedArgs)
                configureBuilder(cmdBuilder, workingDir)
                cmdBuilder.start()
            } else {
                throw e
            }
        }
    }

    private fun normalizeWindowsArgs(args: List<String>): List<String> {
        if (!isWindows()) return args
        return args.map { arg ->
            arg
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\n")
        }
    }

    private fun configureBuilder(builder: ProcessBuilder, workingDir: String?) {

        if (workingDir != null) {
            builder.directory(java.io.File(workingDir))
        }

        if (isWindows() && cliPath.contains("claude", ignoreCase = true)) {
            val env = builder.environment()
            val configuredBash = env["CLAUDE_CODE_GIT_BASH_PATH"] ?: System.getenv("CLAUDE_CODE_GIT_BASH_PATH")
            if (configuredBash.isNullOrBlank() || !File(configuredBash).isFile) {
                findGitBashPath()?.let { env["CLAUDE_CODE_GIT_BASH_PATH"] = it }
            }
        }

        builder.redirectErrorStream(false)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun findGitBashPath(): String? {
        val candidates = listOf(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe",
        )
        return candidates.firstOrNull { File(it).isFile }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 45L
    }
}
