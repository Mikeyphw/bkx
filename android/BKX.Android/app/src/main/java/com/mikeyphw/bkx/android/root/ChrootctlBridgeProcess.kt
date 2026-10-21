package com.mikeyphw.bkx.android.root

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal data class ChrootctlProcessResult(
    val exitCode: Int,
    val output: String,
    val errorOutput: String,
    val timedOut: Boolean,
    val truncated: Boolean,
)

internal data class ChrootctlStreamResult(
    val exitCode: Int,
    val errorOutput: String,
    val timedOut: Boolean,
    val truncated: Boolean,
)

/** Runs only the already-trusted executable selected by CCT-01. */
internal class ChrootctlBridgeProcess(private val executable: String) {
    fun invoke(
        arguments: List<String>,
        requestJson: String? = null,
        timeoutSeconds: Long = INVOKE_TIMEOUT_SECONDS,
    ): ChrootctlProcessResult {
        val process = start(arguments)
        val stdout = BoundedOutputStream(MAX_CAPTURE_BYTES)
        val stderr = BoundedOutputStream(MAX_CAPTURE_BYTES)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val stdoutThread = drain(process.inputStream, stdout, "bkx-chrootctl-stdout", failures)
        val stderrThread = drain(process.errorStream, stderr, "bkx-chrootctl-stderr", failures)
        var finished = false
        var timedOut = false
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                if (requestJson != null) writer.write(requestJson)
            }
            finished = process.waitForChrootctlCompat(timeoutSeconds)
            timedOut = !finished
            if (timedOut) terminateTree(process, failures)
        } catch (interrupted: InterruptedException) {
            failures += "bridge wait interrupted"
            terminateTree(process, failures)
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            if (!finished && process.isRunningChrootctlCompat()) terminateTree(process, failures)
            stdoutThread.join(READER_JOIN_TIMEOUT_MS)
            stderrThread.join(READER_JOIN_TIMEOUT_MS)
        }
        return ChrootctlProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = stdout.asString(),
            errorOutput = diagnostics(stderr, failures),
            timedOut = timedOut,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    fun stream(
        arguments: List<String>,
        requestJson: String,
        output: OutputStream,
        timeoutSeconds: Long = STREAM_TIMEOUT_SECONDS,
    ): ChrootctlStreamResult {
        val process = start(arguments)
        val stderr = BoundedOutputStream(MAX_CAPTURE_BYTES)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val stdoutThread = drainStreaming(process.inputStream, output, "bkx-chrootctl-stream", failures)
        val stderrThread = drain(process.errorStream, stderr, "bkx-chrootctl-stream-stderr", failures)
        var finished = false
        var timedOut = false
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(requestJson) }
            finished = process.waitForChrootctlCompat(timeoutSeconds)
            timedOut = !finished
            if (timedOut) terminateTree(process, failures)
        } catch (interrupted: InterruptedException) {
            failures += "bridge stream wait interrupted"
            terminateTree(process, failures)
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            if (!finished && process.isRunningChrootctlCompat()) terminateTree(process, failures)
            stdoutThread.join(READER_JOIN_TIMEOUT_MS)
            stderrThread.join(READER_JOIN_TIMEOUT_MS)
            runCatching { output.flush() }
        }
        return ChrootctlStreamResult(
            exitCode = if (finished) process.exitValue() else -1,
            errorOutput = diagnostics(stderr, failures),
            timedOut = timedOut,
            truncated = stderr.truncated,
        )
    }

    private fun start(arguments: List<String>): Process {
        require(arguments.isNotEmpty() && arguments.first() == "app") { "Only the fixed chrootctl app bridge may be executed." }
        require(arguments.none { it.contains('\n') || it.contains('\r') || '\u0000' in it }) { "Invalid chrootctl bridge argument." }
        return ProcessBuilder(listOf(executable) + arguments)
            .apply {
                environment().clear()
                environment().putAll(
                    linkedMapOf(
                        "HOME" to "/data/adb/chrootctl",
                        "PATH" to "/data/adb/chrootctl/bin:/system/bin:/system/xbin:/vendor/bin:/product/bin",
                        "TMPDIR" to "/data/local/tmp",
                        "LANG" to "C.UTF-8",
                        "LC_ALL" to "C.UTF-8",
                    ),
                )
            }
            .start()
    }

    private fun terminateTree(process: Process, failures: MutableList<String>) {
        runCatching {
            val rootPid = processPid(process)
            if (rootPid <= 0) {
                failures += "process PID unavailable; descendant termination cannot be verified"
                process.killChrootctlCompat()
                return@runCatching
            }
            val known = linkedSetOf<Int>()
            fun refresh() {
                if (File("/proc/$rootPid").exists()) known += collectProcessTree(rootPid)
            }
            fun signalKnown(signal: Int) {
                known.toList().asReversed().forEach { pid ->
                    if (File("/proc/$pid").exists()) runCatching { Os.kill(pid, signal) }
                }
            }
            refresh()
            runCatching { Os.kill(rootPid, OsConstants.SIGTERM) }
            signalKnown(OsConstants.SIGTERM)
            val termDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < termDeadline && known.any { File("/proc/$it").exists() }) {
                refresh()
                Thread.sleep(50)
            }
            refresh()
            signalKnown(OsConstants.SIGKILL)
            runCatching { process.killChrootctlCompat() }
        }.onFailure { failures += "process-tree termination failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun processPid(process: Process): Int = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        val value = field.get(process) as? Number
        value?.toInt() ?: -1
    }.getOrDefault(-1)

    private fun collectProcessTree(rootPid: Int): List<Int> {
        val seen = linkedSetOf<Int>()
        fun visit(pid: Int) {
            if (pid <= 0 || !seen.add(pid)) return
            val children = runCatching {
                File("/proc/$pid/task/$pid/children").readText().trim().split(Regex("\\s+"))
                    .filter(String::isNotBlank).mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
            children.forEach(::visit)
        }
        visit(rootPid)
        return seen.toList()
    }

    private fun drain(input: InputStream, output: OutputStream, name: String, failures: MutableList<String>): Thread = Thread({
        try {
            input.use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } catch (error: Throwable) {
            failures += "$name closed: ${error.message ?: error.javaClass.simpleName}"
        }
    }, name).apply { isDaemon = true; start() }

    private fun drainStreaming(input: InputStream, output: OutputStream, name: String, failures: MutableList<String>): Thread = Thread({
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var target: OutputStream = output
            while (true) {
                val count = try {
                    source.read(buffer)
                } catch (error: Throwable) {
                    failures += "$name read failed: ${error.message ?: error.javaClass.simpleName}"
                    break
                }
                if (count < 0) break
                if (count == 0) continue
                try {
                    target.write(buffer, 0, count)
                    target.flush()
                } catch (error: Throwable) {
                    failures += "$name output closed: ${error.message ?: error.javaClass.simpleName}"
                    target = DiscardingOutputStream
                }
            }
        }
    }, name).apply { isDaemon = true; start() }

    private fun diagnostics(stderr: BoundedOutputStream, failures: List<String>): String = buildList {
        stderr.asString().takeIf(String::isNotBlank)?.let(::add)
        if (stderr.truncated) add("stderr capture truncated at $MAX_CAPTURE_BYTES bytes")
        addAll(failures)
    }.joinToString("\n").take(MAX_DIAGNOSTIC_CHARS)

    private class BoundedOutputStream(private val limit: Int) : OutputStream() {
        private val buffer = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        var truncated: Boolean = false
            private set

        override fun write(value: Int) {
            if (buffer.size() < limit) buffer.write(value) else truncated = true
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val remaining = (limit - buffer.size()).coerceAtLeast(0)
            val accepted = minOf(length, remaining)
            if (accepted > 0) buffer.write(bytes, offset, accepted)
            if (accepted < length) truncated = true
        }

        fun asString(): String = String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    private object DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private companion object {
        const val MAX_CAPTURE_BYTES = 1024 * 1024
        const val MAX_DIAGNOSTIC_CHARS = 4096
        const val READER_JOIN_TIMEOUT_MS = 2_000L
        const val INVOKE_TIMEOUT_SECONDS = 60L
        const val STREAM_TIMEOUT_SECONDS = 6 * 60 * 60L
    }
}

internal sealed interface ChrootctlOutputValidation {
    data class Accepted(val json: String) : ChrootctlOutputValidation
    data class Rejected(val code: String, val message: String) : ChrootctlOutputValidation
}

internal fun validateChrootctlProcessResult(
    executable: String,
    result: ChrootctlProcessResult,
    expectedRequestId: String? = null,
): ChrootctlOutputValidation {
    if (result.timedOut) return ChrootctlOutputValidation.Rejected("BRIDGE_TIMEOUT", "chrootctl app bridge timed out")
    if (result.truncated) return ChrootctlOutputValidation.Rejected("BRIDGE_RESPONSE_TRUNCATED", "chrootctl app bridge exceeded the bounded response capture")
    val output = result.output.trim()
    if (output.toByteArray(Charsets.UTF_8).size > MAX_VALIDATED_RESPONSE_BYTES) {
        return ChrootctlOutputValidation.Rejected("BRIDGE_RESPONSE_TOO_LARGE", "chrootctl app bridge response exceeded $MAX_VALIDATED_RESPONSE_BYTES bytes")
    }
    val envelope = runCatching { JSONObject(output) }.getOrNull()
        ?: return ChrootctlOutputValidation.Rejected(
            "INVALID_BRIDGE_RESPONSE",
            "chrootctl app bridge returned malformed JSON${result.errorOutput.takeIf(String::isNotBlank)?.let { ": ${it.take(240)}" }.orEmpty()}",
        )
    if (envelope.optInt("protocolVersion", -1) != 1) {
        return ChrootctlOutputValidation.Rejected("UNSUPPORTED_BRIDGE_PROTOCOL", "chrootctl app bridge protocol is not 1")
    }
    if (!envelope.has("ok")) {
        return ChrootctlOutputValidation.Rejected("INVALID_BRIDGE_RESPONSE", "chrootctl app bridge response has no ok field")
    }
    if (!expectedRequestId.isNullOrBlank() && envelope.optString("requestId") != expectedRequestId) {
        return ChrootctlOutputValidation.Rejected("BRIDGE_REQUEST_ID_MISMATCH", "chrootctl response requestId did not match $expectedRequestId")
    }
    if (result.exitCode != 0 && envelope.optBoolean("ok", true)) {
        return ChrootctlOutputValidation.Rejected("BRIDGE_EXIT_MISMATCH", "chrootctl app bridge exited ${result.exitCode} while reporting success")
    }
    return ChrootctlOutputValidation.Accepted(output)
}

private const val MAX_VALIDATED_RESPONSE_BYTES = 1024 * 1024

/** Java Process timeout/isAlive/destroyForcibly APIs are unavailable before Android 26. */
internal fun Process.isRunningChrootctlCompat(): Boolean = try {
    exitValue()
    false
} catch (_: IllegalThreadStateException) {
    true
}

/** Bounded polling uses only Android API-24-compatible Process methods; interruption propagates. */
internal fun Process.waitForChrootctlCompat(timeoutSeconds: Long): Boolean {
    require(timeoutSeconds >= 0) { "Negative chrootctl process timeout" }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (true) {
        if (!isRunningChrootctlCompat()) return true
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) return false
        TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)))
    }
}

/** Try a direct OS-level SIGKILL on the known PID, then close the Process handle. */
internal fun Process.killChrootctlCompat() {
    val pid = runCatching {
        val field = javaClass.getDeclaredField("pid")
        field.isAccessible = true
        (field.get(this) as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)
    if (pid > 0) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
    destroy() // API 1 fallback when reflection is denied or PID is unavailable
}
