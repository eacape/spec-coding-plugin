package com.eacape.speccodingplugin.engine

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
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
                writeRequestInput(process, request)
                val stderrFuture = CompletableFuture.supplyAsync {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val exitCode = process.waitFor()
                val stderr = sanitizeProcessError(
                    runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrNull().orEmpty()
                )
                if (timedOut.get()) {
                    return@withContext EngineResponse(
                        content = "",
                        engineId = id,
                        success = false,
                        error = stderr.ifBlank { timeoutMessage(timeoutSeconds) },
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
                watchdogFuture?.cancel(true)
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
        val args = buildStreamCommandArgs(request)
        val process = startProcess(args, request.context.workingDirectory)
        activeProcesses[requestId] = process
        val (timedOut, watchdogFuture) = startTimeoutWatchdog(process, timeoutSeconds)
        try {
            writeRequestInput(process, request)
            var emittedChunk = false
            val frames: BlockingQueue<StreamFrame> = LinkedBlockingQueue()
            val stdoutDone = AtomicBoolean(false)
            val stderrDone = AtomicBoolean(false)
            val stderrText = StringBuilder()

            val stdoutPump = startLinePump(
                stream = process.inputStream,
                source = StreamSource.STDOUT,
                queue = frames,
                done = stdoutDone,
                stderrCollector = null,
            )
            val stderrPump = startLinePump(
                stream = process.errorStream,
                source = StreamSource.STDERR,
                queue = frames,
                done = stderrDone,
                stderrCollector = stderrText,
            )

            while (true) {
                val frame = frames.poll(STREAM_POLL_MILLIS, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val parsed = when (frame.source) {
                        StreamSource.STDOUT -> parseStreamLine(
                            if (frame.hasLineBreak) frame.line + "\n" else frame.line
                        )
                        StreamSource.STDERR -> parseProgressLine(frame.line)
                    }
                    if (parsed != null) {
                        emittedChunk = true
                        emit(parsed)
                    }
                }

                val streamDrained = stdoutDone.get() && stderrDone.get() && frames.isEmpty()
                if (streamDrained && !process.isAlive) {
                    break
                }
            }

            stdoutPump.join(STREAM_PUMP_JOIN_MILLIS)
            stderrPump.join(STREAM_PUMP_JOIN_MILLIS)

            val exitCode = process.waitFor()
            val stderr = sanitizeProcessError(stderrText.toString())
            if (timedOut.get()) {
                val resolvedTimeoutMessage = if (stderr.isBlank()) {
                    timeoutMessage(timeoutSeconds)
                } else {
                    "${timeoutMessage(timeoutSeconds)}: $stderr"
                }
                throw RuntimeException(resolvedTimeoutMessage)
            }
            if (exitCode != 0) {
                throw RuntimeException(stderr.ifBlank { "CLI exited with code: $exitCode" })
            }
            if (!emittedChunk && stderr.isNotBlank()) {
                throw RuntimeException(stderr)
            }
            emit(EngineChunk(delta = "", isLast = true))
        } finally {
            watchdogFuture?.cancel(true)
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

    /**
     * 构建流式命令行参数。
     * 默认与非流式保持一致，子类可按需覆盖（例如启用结构化流输出）。
     */
    protected open fun buildStreamCommandArgs(request: EngineRequest): List<String> = buildCommandArgs(request)

    /**
     * 可选 stdin 负载；返回 null 表示不写入 stdin。
     */
    protected open fun stdinPayload(request: EngineRequest): String? = null

    /** 解析流式输出行 */
    protected abstract fun parseStreamLine(line: String): EngineChunk?

    /**
     * 解析 CLI 过程输出（通常来自 stderr）。
     * 默认映射为 trace 事件，不写入最终 answer 文本。
     */
    protected open fun parseProgressLine(line: String): EngineChunk? {
        val event = CliProgressEventParser.parseStderr(line) ?: return null
        return EngineChunk(
            delta = "",
            event = event,
        )
    }

    /**
     * stdout 在无换行时的分片阈值。
     * 返回 null 或 <= 0 时仅按换行刷新，适合 JSONL 等按行协议。
     */
    protected open fun stdoutChunkFlushChars(): Int? = STREAM_STDOUT_FLUSH_CHARS

    /** 获取版本号 */
    protected abstract suspend fun getVersion(): String?

    private fun writeRequestInput(process: Process, request: EngineRequest) {
        val payload = stdinPayload(request)
        if (payload == null) {
            runCatching { process.outputStream.close() }
            return
        }
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
        }
    }

    private fun resolveTimeoutSeconds(request: EngineRequest): Long? {
        val option = request.options["timeout_seconds"]?.toLongOrNull()
        return option?.takeIf { it > 0 }
    }

    private fun timeoutMessage(timeoutSeconds: Long?): String {
        return if (timeoutSeconds != null && timeoutSeconds > 0) {
            "CLI request timed out after $timeoutSeconds seconds"
        } else {
            "CLI request timed out"
        }
    }

    private fun startTimeoutWatchdog(
        process: Process,
        timeoutSeconds: Long?,
    ): Pair<AtomicBoolean, CompletableFuture<Void>?> {
        val timedOut = AtomicBoolean(false)
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return timedOut to null
        }
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

    private fun startLinePump(
        stream: InputStream,
        source: StreamSource,
        queue: BlockingQueue<StreamFrame>,
        done: AtomicBoolean,
        stderrCollector: StringBuilder?,
    ): Thread {
        val pump = Thread {
            try {
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val stdoutFlushChars = stdoutChunkFlushChars()
                    val buffer = StringBuilder()

                    fun flush(hasLineBreak: Boolean = false) {
                        if (buffer.isEmpty()) return
                        val text = if (source == StreamSource.STDERR) {
                            buffer.toString().trimEnd()
                        } else {
                            buffer.toString()
                        }
                        buffer.setLength(0)
                        if (source == StreamSource.STDERR && text.isBlank()) return
                        if (stderrCollector != null) {
                            synchronized(stderrCollector) {
                                stderrCollector.appendLine(text)
                            }
                        }
                        queue.offer(
                            StreamFrame(
                                source = source,
                                line = text,
                                hasLineBreak = hasLineBreak,
                            )
                        )
                    }

                    while (true) {
                        val value = reader.read()
                        if (value == -1) break
                        val ch = value.toChar()
                        when (ch) {
                            '\n', '\r' -> flush(hasLineBreak = true)
                            else -> {
                                buffer.append(ch)
                                if (source == StreamSource.STDERR && buffer.length >= STREAM_PROGRESS_FLUSH_CHARS) {
                                    flush(hasLineBreak = false)
                                }
                                if (source == StreamSource.STDOUT &&
                                    stdoutFlushChars != null &&
                                    stdoutFlushChars > 0 &&
                                    buffer.length >= stdoutFlushChars
                                ) {
                                    flush(hasLineBreak = false)
                                }
                            }
                        }
                    }
                    flush(hasLineBreak = false)
                }
            } catch (ignored: Exception) {
                logger.debug("Stream pump closed for $id ($source): ${ignored.message}")
            } finally {
                done.set(true)
            }
        }
        pump.isDaemon = true
        pump.name = "cli-$id-${source.name.lowercase()}-pump"
        pump.start()
        return pump
    }

    private fun sanitizeProcessError(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(ERROR_ANSI_REGEX, "")
            .replace(ERROR_REPLACEMENT_CHAR_REGEX, "")
            .replace('\u0008', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { looksLikeMojibakeLine(it) }
            .joinToString("\n")
            .trim()
    }

    private fun looksLikeMojibakeLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return false
        if (ERROR_CJK_REGEX.containsMatchIn(normalized)) return false
        if (ERROR_BOX_DRAWING_REGEX.containsMatchIn(normalized)) return true
        val suspiciousCount = ERROR_SUSPICIOUS_MOJIBAKE_REGEX.findAll(normalized).count()
        if (suspiciousCount < ERROR_MOJIBAKE_MIN_COUNT) return false
        val ratio = suspiciousCount.toDouble() / normalized.length.toDouble().coerceAtLeast(1.0)
        return ratio >= ERROR_MOJIBAKE_MIN_RATIO
    }

    companion object {
        private const val STREAM_POLL_MILLIS = 120L
        private const val STREAM_PUMP_JOIN_MILLIS = 500L
        private const val STREAM_PROGRESS_FLUSH_CHARS = 120
        private const val STREAM_STDOUT_FLUSH_CHARS = 64
        private val ERROR_ANSI_REGEX = Regex("""\u001B\[[;\d]*[ -/]*[@-~]""")
        private val ERROR_REPLACEMENT_CHAR_REGEX = Regex("""\uFFFD+""")
        private val ERROR_BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
        private val ERROR_CJK_REGEX = Regex("""\p{IsHan}""")
        private val ERROR_SUSPICIOUS_MOJIBAKE_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
        private const val ERROR_MOJIBAKE_MIN_COUNT = 4
        private const val ERROR_MOJIBAKE_MIN_RATIO = 0.15
    }

    private enum class StreamSource {
        STDOUT,
        STDERR,
    }

    private data class StreamFrame(
        val source: StreamSource,
        val line: String,
        val hasLineBreak: Boolean,
    )
}
