package com.mikeyphw.bkx.android.embedded

import android.content.Context
import android.os.Build
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRoot
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRootResolver
import com.mikeyphw.bkx.android.core.state.BkxSharedStateContract
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persistent app-owned Go runtime transport.
 *
 * The generated executable is launched directly from ApplicationInfo.nativeLibraryDir.
 * No shell is involved. The concurrent protocol lets progress/cancel requests run while
 * an execute request is still active inside the same Go process.
 */
class GoNativeGateway(
    context: Context,
    private val stateNamespace: String = LOCAL_STATE_NAMESPACE,
) : AvailableEmbeddedRuntimeGateway {
    private val appContext = context.applicationContext ?: context

    init {
        require(stateNamespace in ALLOWED_STATE_NAMESPACES) { "Unsupported BKX Go runtime state namespace." }
    }
    private val stateLock = Any()
    private val writeLock = Any()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, PendingCommand>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var sessionStateRoot: String? = null
    @Volatile private var sessionId: Long = 0L

    override fun isAvailable(): Boolean =
        Build.SUPPORTED_ABIS.any { it == ABI } && runtimeFile().let { it.isFile && it.canExecute() }

    override suspend fun readinessJson(): String = request("readiness")

    override suspend fun executeJson(requestJson: String): String =
        request("execute", requestJson = requestJson, timeoutMillis = EXECUTE_TIMEOUT_MS)

    override suspend fun progressJson(requestId: String): String =
        request("progress", requestId = requestId, timeoutMillis = PROGRESS_TIMEOUT_MS, invalidateOnFailure = false)

    override suspend fun cancelJson(requestId: String): String =
        request("cancel", requestId = requestId, timeoutMillis = CANCEL_TIMEOUT_MS, invalidateOnFailure = false)

    private suspend fun request(
        entrypoint: String,
        requestId: String? = null,
        requestJson: String? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        invalidateOnFailure: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        check(isAvailable()) { "Generated BKX Go runtime is unavailable for this ABI." }
        val session = ensureSession()
        val commandId = "android-${sequence.incrementAndGet()}"
        val deferred = CompletableDeferred<String>()
        val pendingCommand = PendingCommand(session.id, entrypoint, deferred)
        pending[commandId] = pendingCommand
        try {
            val command = GoNativeSessionWire.command(commandId, entrypoint, requestId, requestJson)
            synchronized(writeLock) {
                session.writer.write(command)
                session.writer.newLine()
                session.writer.flush()
            }
            val rawReply = withTimeout(timeoutMillis) { deferred.await() }
            GoNativeSessionWire.reply(rawReply, commandId).toString()
        } catch (error: Throwable) {
            if (invalidateOnFailure) {
                invalidateSession(error, session.id)
            }
            throw error
        } finally {
            pending.remove(commandId, pendingCommand)
        }
    }

    private fun ensureSession(): SessionHandle = synchronized(stateLock) {
        val desiredStateRoot = desiredStateRoot()
        val current = process
        val currentWriter = writer
        if (current != null && isAlive(current) && currentWriter != null && sessionStateRoot == desiredStateRoot.absolutePath) {
            return@synchronized SessionHandle(sessionId, currentWriter)
        }
        if (current != null) {
            closeCurrentSessionLocked(current, IllegalStateException("BKX Go runtime session replaced."))
        }

        val home = appContext.filesDir.resolve("bkx/$stateNamespace/home").apply { mkdirs() }
        val tmp = appContext.cacheDir.resolve("bkx-$stateNamespace-tmp").apply { mkdirs() }
        val state = appContext.filesDir.resolve("bkx/$stateNamespace").apply { mkdirs() }
        val stderr = state.resolve("runtime.stderr.log")
        runCatching { stderr.writeText("", Charsets.UTF_8) }
        val legacyRunHistory = state.resolve("runs.jsonl")
        val runHistory = desiredStateRoot.resolve("runs").resolve("runs.jsonl")
        val builder = ProcessBuilder(
            runtimeFile().absolutePath,
            "android-runtime",
            "bridge-session",
            runHistory.absolutePath,
        ).directory(home)
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = tmp.absolutePath
        builder.environment()[BkxSharedStateContract.ENVIRONMENT] = desiredStateRoot.absolutePath
        builder.environment()["BKX_LEGACY_RUN_HISTORY"] = legacyRunHistory.absolutePath
        val rcloneConfig = appContext.filesDir.resolve("bkx/rclone/rclone.conf").also { it.parentFile?.mkdirs() }
        builder.environment()["BKX_RCLONE_PATH"] = rcloneRuntimeFile().absolutePath
        builder.environment()["BKX_RCLONE_CONFIG"] = rcloneConfig.absolutePath
        val started = builder.start()
        val startedWriter = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        val newSessionId = sessionId + 1L
        process = started
        writer = startedWriter
        sessionStateRoot = desiredStateRoot.absolutePath
        sessionId = newSessionId
        startErrorReader(started, stderr)
        startReader(started, newSessionId, stderr)
        SessionHandle(newSessionId, startedWriter)
    }

    private fun startReader(started: Process, readerSessionId: Long, stderrLog: File) {
        Thread({
            try {
                started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val id = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(line)
                                .jsonObject["id"]?.jsonPrimitive?.content
                        }.getOrNull()
                        if (id != null) pending[id]?.takeIf { it.sessionId == readerSessionId }?.deferred?.complete(line)
                    }
                }
                if (isAlive(started)) started.destroy()
                completePendingForSession(readerSessionId, runtimeExitError(started, stderrLog))
            } catch (error: Throwable) {
                completePendingForSession(readerSessionId, error)
            } finally {
                synchronized(stateLock) {
                    if (process === started && sessionId == readerSessionId) {
                        process = null
                        writer = null
                        sessionStateRoot = null
                    }
                }
            }
        }, "bkx-go-runtime-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun startErrorReader(started: Process, logFile: File) {
        Thread({
            runCatching {
                started.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> appendBoundedRuntimeLog(logFile, line) }
                }
            }
        }, "bkx-go-runtime-stderr").apply {
            isDaemon = true
            start()
        }
    }

    private fun isAlive(candidate: Process): Boolean = try {
        candidate.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun invalidateSession(error: Throwable, expectedSessionId: Long) = synchronized(stateLock) {
        if (sessionId != expectedSessionId) return@synchronized
        process?.let { closeCurrentSessionLocked(it, error) }
        writer = null
        process = null
        sessionStateRoot = null
    }

    private fun closeCurrentSessionLocked(current: Process, error: Throwable) {
        val closingSession = sessionId
        writer = null
        current.destroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!current.waitFor(SESSION_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                current.destroyForcibly()
                current.waitFor(SESSION_SHUTDOWN_FORCE_MS, TimeUnit.MILLISECONDS)
            }
        } else if (!waitForLegacyProcess(current, SESSION_SHUTDOWN_GRACE_MS)) {
            // API 24-25 has no timed wait or destroyForcibly. Calling destroy again is
            // harmless, and bounded polling keeps session replacement from hanging.
            current.destroy()
            waitForLegacyProcess(current, SESSION_SHUTDOWN_FORCE_MS)
        }
        process = null
        sessionStateRoot = null
        completePendingForSession(closingSession, error)
    }

    private fun waitForLegacyProcess(candidate: Process, timeoutMillis: Long): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (isAlive(candidate) && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(LEGACY_PROCESS_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return !isAlive(candidate)
            }
        }
        return !isAlive(candidate)
    }

    private fun runtimeExitError(candidate: Process, stderrLog: File): IllegalStateException {
        // stderr is drained on a sibling thread, so allow a tiny bounded window for the
        // final diagnostic lines to reach disk after stdout reaches EOF.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STDERR_DRAIN_GRACE_MS)
        while (isAlive(candidate) && System.nanoTime() < deadline) Thread.yield()
        val exitCode = runCatching { candidate.exitValue() }.getOrNull()
        val stderrTail = runCatching {
            stderrLog.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                ?.takeLast(MAX_STDERR_DIAGNOSTIC_CHARS)
                ?.trim()
        }.getOrNull().orEmpty()
        val detail = buildString {
            append("BKX Go runtime session ended")
            if (exitCode != null) append(" (exit code ").append(exitCode).append(')')
            append('.')
            if (stderrTail.isNotBlank()) append(" Runtime stderr: ").append(stderrTail)
            else append(" No runtime stderr was emitted.")
        }
        return IllegalStateException(detail)
    }

    private fun completePendingForSession(closingSession: Long, error: Throwable) {
        pending.values
            .filter { it.sessionId == closingSession }
            .forEach { pendingCommand -> pendingCommand.deferred.completeExceptionally(error) }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { pendingCommand -> pendingCommand.deferred.completeExceptionally(error) }
    }

    fun close() = synchronized(stateLock) {
        writer = null
        process?.destroy()
        process = null
        sessionStateRoot = null
        failPending(IllegalStateException("BKX Go runtime session closed."))
    }

    private fun appendBoundedRuntimeLog(logFile: File, line: String) {
        if (logFile.length() > MAX_STDERR_LOG_BYTES) {
            logFile.writeText("[bkx] stderr log rotated after ${MAX_STDERR_LOG_BYTES} bytes\n", Charsets.UTF_8)
        }
        logFile.appendText(line.take(MAX_STDERR_LINE_CHARS) + "\n", Charsets.UTF_8)
    }


    private fun desiredStateRoot(): File = BkxControlPlaneRootResolver.resolve(appContext, stateNamespace)
        .also { root ->
            if (root.source == BkxControlPlaneRoot.Source.Shared) {
                root.directory.mkdirs()
            }
        }
        .directory

    private fun runtimeFile(): File = File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_LIBRARY)
    private fun rcloneRuntimeFile(): File = File(appContext.applicationInfo.nativeLibraryDir, RCLONE_RUNTIME_LIBRARY)

    private companion object {
        const val ABI = "arm64-v8a"
        const val LOCAL_STATE_NAMESPACE = "go-local"
        const val ROOT_STATE_NAMESPACE = "go-root"
        val ALLOWED_STATE_NAMESPACES = setOf(LOCAL_STATE_NAMESPACE, ROOT_STATE_NAMESPACE)
        const val RUNTIME_LIBRARY = "libbkxgo.so"
        const val RCLONE_RUNTIME_LIBRARY = "libbkxrclone_exec.so"
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val PROGRESS_TIMEOUT_MS = 10_000L
        const val CANCEL_TIMEOUT_MS = 20_000L
        const val EXECUTE_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        const val SESSION_SHUTDOWN_GRACE_MS = 750L
        const val SESSION_SHUTDOWN_FORCE_MS = 250L
        const val LEGACY_PROCESS_POLL_MS = 25L
        const val MAX_STDERR_LOG_BYTES = 512L * 1024L
        const val MAX_STDERR_LINE_CHARS = 8 * 1024
        const val MAX_STDERR_DIAGNOSTIC_CHARS = 4 * 1024
        const val STDERR_DRAIN_GRACE_MS = 100L
    }

    private data class SessionHandle(
        val id: Long,
        val writer: BufferedWriter,
    )

    private data class PendingCommand(
        val sessionId: Long,
        val entrypoint: String,
        val deferred: CompletableDeferred<String>,
    )
}
