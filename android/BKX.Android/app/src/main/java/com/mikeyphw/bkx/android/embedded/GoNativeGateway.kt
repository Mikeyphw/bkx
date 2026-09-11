package com.mikeyphw.bkx.android.embedded

import android.content.Context
import android.os.Build
import android.os.Environment
import com.mikeyphw.bkx.android.core.state.BkxSharedStateContract
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
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
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var sessionStateRoot: String? = null

    override fun isAvailable(): Boolean =
        Build.SUPPORTED_ABIS.any { it == ABI } && runtimeFile().let { it.isFile && it.canExecute() }

    override suspend fun readinessJson(): String = request("readiness")

    override suspend fun executeJson(requestJson: String): String =
        request("execute", requestJson = requestJson, timeoutMillis = EXECUTE_TIMEOUT_MS)

    override suspend fun progressJson(requestId: String): String =
        request("progress", requestId = requestId, timeoutMillis = PROGRESS_TIMEOUT_MS)

    override suspend fun cancelJson(requestId: String): String =
        request("cancel", requestId = requestId, timeoutMillis = CANCEL_TIMEOUT_MS)

    private suspend fun request(
        entrypoint: String,
        requestId: String? = null,
        requestJson: String? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        check(isAvailable()) { "Generated BKX Go runtime is unavailable for this ABI." }
        val commandId = "android-${sequence.incrementAndGet()}"
        val deferred = CompletableDeferred<String>()
        pending[commandId] = deferred
        try {
            val sessionWriter = ensureSession()
            val command = GoNativeSessionWire.command(commandId, entrypoint, requestId, requestJson)
            synchronized(writeLock) {
                sessionWriter.write(command)
                sessionWriter.newLine()
                sessionWriter.flush()
            }
            val rawReply = withTimeout(timeoutMillis) { deferred.await() }
            GoNativeSessionWire.reply(rawReply, commandId).toString()
        } catch (error: Throwable) {
            invalidateSession(error)
            throw error
        } finally {
            pending.remove(commandId)
        }
    }

    private fun ensureSession(): BufferedWriter = synchronized(stateLock) {
        val desiredStateRoot = desiredStateRoot()
        val current = process
        val currentWriter = writer
        if (current != null && isAlive(current) && currentWriter != null && sessionStateRoot == desiredStateRoot.absolutePath) {
            return@synchronized currentWriter
        }
        if (current != null) {
            writer = null
            current.destroy()
            process = null
        }

        val home = appContext.filesDir.resolve("bkx/$stateNamespace/home").apply { mkdirs() }
        val tmp = appContext.cacheDir.resolve("bkx-$stateNamespace-tmp").apply { mkdirs() }
        val state = appContext.filesDir.resolve("bkx/$stateNamespace").apply { mkdirs() }
        val stderr = state.resolve("runtime.stderr.log")
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
        process = started
        writer = startedWriter
        sessionStateRoot = desiredStateRoot.absolutePath
        startReader(started)
        startErrorReader(started, stderr)
        startedWriter
    }

    private fun startReader(started: Process) {
        Thread({
            try {
                started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val id = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(line)
                                .jsonObject["id"]?.jsonPrimitive?.content
                        }.getOrNull()
                        if (id != null) pending[id]?.complete(line)
                    }
                }
                if (isAlive(started)) started.destroy()
                failPending(IllegalStateException("BKX Go runtime session ended."))
            } catch (error: Throwable) {
                failPending(error)
            } finally {
                synchronized(stateLock) {
                    if (process === started) {
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
                    lines.forEach { line -> logFile.appendText(line + "\n") }
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

    private fun invalidateSession(error: Throwable) = synchronized(stateLock) {
        writer = null
        process?.destroy()
        process = null
        sessionStateRoot = null
        failPending(error)
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { deferred -> deferred.completeExceptionally(error) }
    }

    fun close() = synchronized(stateLock) {
        writer = null
        process?.destroy()
        process = null
        sessionStateRoot = null
        failPending(IllegalStateException("BKX Go runtime session closed."))
    }


    private fun desiredStateRoot(): File {
        val sharedAllowed = stateNamespace == ROOT_STATE_NAMESPACE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        if (!sharedAllowed) {
            return appContext.filesDir.resolve("bkx/$stateNamespace/home/.local/share/bkx").apply { mkdirs() }
        }
        return File(BkxSharedStateContract.ROOT).apply {
            mkdirs()
            val marker = resolve(BkxSharedStateContract.MARKER)
            if (!marker.exists()) {
                runCatching {
                    marker.writeText(
                        """{"schema_version":1,"kind":"${BkxSharedStateContract.MARKER_KIND}","creator":"android"}""" + "\n",
                        Charsets.UTF_8,
                    )
                }
            }
        }
    }

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
    }
}
