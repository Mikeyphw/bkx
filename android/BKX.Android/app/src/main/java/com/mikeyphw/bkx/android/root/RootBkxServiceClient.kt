package com.mikeyphw.bkx.android.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

/**
 * Lifecycle-safe libsu RootService client.
 *
 * Mutating execute calls are never automatically retried: a transport failure
 * must not duplicate a backup, restore, or transfer.
 */
class RootBkxServiceClient(
    context: Context,
) {
    private val appContext = context.applicationContext ?: context
    private val bindMutex = Mutex()
    private val executeMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var remote: IBkxRootEngineService? = null

    @Volatile
    private var connection: ServiceConnection? = null

    @Volatile
    private var binder: IBinder? = null

    suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(ROOT_PROBE_TIMEOUT_MS) {
            runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        } ?: false
    }

    suspend fun readinessJson(): String = call(READINESS_TIMEOUT_MS) { it.readinessJson() }

    suspend fun chrootctlReadinessJson(): String = call(CHROOTCTL_READINESS_TIMEOUT_MS) {
        it.chrootctlReadinessJson()
    }

    suspend fun chrootctlQueryJson(requestJson: String): String {
        requireChrootctlRequestBudget(requestJson)
        return call(CHROOTCTL_QUERY_TIMEOUT_MS) { it.chrootctlQueryJson(requestJson) }
    }

    suspend fun chrootctlPreviewJson(requestJson: String): String {
        requireChrootctlRequestBudget(requestJson)
        return call(CHROOTCTL_PREVIEW_TIMEOUT_MS) { it.chrootctlPreviewJson(requestJson) }
    }

    suspend fun chrootctlOperationJson(operationId: String): String = call(CHROOTCTL_QUERY_TIMEOUT_MS) {
        it.chrootctlOperationJson(operationId)
    }

    suspend fun chrootctlCancelOperationJson(operationId: String): String = call(CHROOTCTL_CANCEL_TIMEOUT_MS) {
        it.chrootctlCancelOperationJson(operationId)
    }

    suspend fun chrootctlReconcileOperationJson(operationId: String): String = call(CHROOTCTL_QUERY_TIMEOUT_MS) {
        it.chrootctlReconcileOperationJson(operationId)
    }

    /**
     * Starts one fixed `chrootctl app run` request and drains its NDJSON through
     * a Binder pipe. Each individual event is bounded before it reaches callers.
     * No operation start is automatically retried after uncertain execution.
     */
    suspend fun chrootctlStreamOperation(
        requestJson: String,
        onEvent: suspend (String) -> Unit,
    ): String {
        requireChrootctlRequestBudget(requestJson)
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val started = try {
            call(CHROOTCTL_START_TIMEOUT_MS) { service ->
                service.chrootctlStartOperation(requestJson, writeSide)
            }
        } finally {
            runCatching { writeSide.close() }
        }
        if (!runCatching { JSONObject(started).optBoolean("ok", false) }.getOrDefault(false)) {
            runCatching { readSide.close() }
            return started
        }
        withContext(Dispatchers.IO) {
            val closeOnCancel = coroutineContext[Job]?.invokeOnCompletion { runCatching { readSide.close() } }
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    readBoundedNdjson(input, CHROOTCTL_MAX_EVENT_BYTES, onEvent)
                }
            } finally {
                closeOnCancel?.dispose()
                runCatching { readSide.close() }
            }
        }
        return started
    }

    suspend fun executeJson(requestJson: String): String = executeMutex.withLock {
        call(EXECUTE_TIMEOUT_MS) { it.executeJson(requestJson) }
    }

    suspend fun progressJson(requestId: String): String = call(PROGRESS_TIMEOUT_MS) {
        it.progressJson(requestId)
    }

    suspend fun cancelJson(requestId: String): String = call(CANCEL_TIMEOUT_MS) {
        it.cancelJson(requestId)
    }

    private suspend fun readBoundedNdjson(
        input: java.io.InputStream,
        maxLineBytes: Int,
        onEvent: suspend (String) -> Unit,
    ) {
        val line = java.io.ByteArrayOutputStream(minOf(maxLineBytes, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 0xff
                if (value == '\n'.code) {
                    if (line.size() > 0) {
                        onEvent(line.toString(Charsets.UTF_8.name()).trimEnd('\r'))
                        line.reset()
                    }
                } else {
                    require(line.size() < maxLineBytes) {
                        "chrootctl NDJSON event exceeds $maxLineBytes bytes."
                    }
                    line.write(value)
                }
            }
        }
        if (line.size() > 0) onEvent(line.toString(Charsets.UTF_8.name()).trimEnd('\r'))
    }

    private fun requireChrootctlRequestBudget(requestJson: String) {
        val bytes = requestJson.toByteArray(Charsets.UTF_8).size
        require(bytes <= CHROOTCTL_MAX_BINDER_REQUEST_BYTES) {
            "chrootctl request exceeds the Binder-safe $CHROOTCTL_MAX_BINDER_REQUEST_BYTES-byte ceiling."
        }
    }

    fun release() {
        val current = synchronized(stateLock) {
            val value = connection
            remote = null
            connection = null
            binder = null
            value
        }
        if (current != null) {
            runCatching { RootService.unbind(current) }
        }
    }

    private suspend fun call(
        timeoutMs: Long,
        block: (IBkxRootEngineService) -> String,
    ): String = try {
        withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                val service = service()
                check(service.uid() == ROOT_UID) {
                    "BKX root transport connected to UID ${service.uid()} instead of UID 0."
                }
                try {
                    block(service)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    invalidate()
                    throw error
                }
            }
        }
    } catch (error: TimeoutCancellationException) {
        invalidate()
        throw IllegalStateException("BKX root service call timed out.", error)
    }

    private suspend fun service(): IBkxRootEngineService {
        liveRemote()?.let { return it }
        return bindMutex.withLock {
            liveRemote()?.let { return@withLock it }
            bindService()
        }
    }

    private fun liveRemote(): IBkxRootEngineService? {
        val current = remote ?: return null
        val currentBinder = binder ?: return null
        return if (currentBinder.isBinderAlive && currentBinder.pingBinder()) current else null
    }

    private suspend fun bindService(): IBkxRootEngineService = withTimeout(BIND_TIMEOUT_MS) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val intent = Intent(appContext, BkxRootEngineService::class.java)
                lateinit var candidate: ServiceConnection
                candidate = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
                        val service = IBkxRootEngineService.Stub.asInterface(serviceBinder)
                        if (runCatching { service.uid() }.getOrDefault(-1) != ROOT_UID) {
                            runCatching { RootService.unbind(this) }
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("libsu connected BKX to a non-root service process."),
                                )
                            }
                            return
                        }
                        synchronized(stateLock) {
                            remote = service
                            connection = this
                            binder = serviceBinder
                        }
                        runCatching {
                            serviceBinder.linkToDeath({ invalidate(expected = serviceBinder) }, 0)
                        }
                        if (continuation.isActive) continuation.resume(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        invalidate()
                    }

                    override fun onBindingDied(name: ComponentName) {
                        invalidate()
                    }

                    override fun onNullBinding(name: ComponentName) {
                        invalidate()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("BKX root service returned no Binder."))
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    val shouldUnbind = synchronized(stateLock) { connection !== candidate }
                    if (shouldUnbind) runCatching { RootService.unbind(candidate) }
                }

                runCatching { RootService.bind(intent, candidate) }
                    .onFailure { error ->
                        runCatching { RootService.unbind(candidate) }
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        }
    }

    private fun invalidate(expected: IBinder? = null) {
        val current = synchronized(stateLock) {
            if (expected != null && binder !== expected) return
            val value = connection
            remote = null
            connection = null
            binder = null
            value
        }
        if (current != null) runCatching { RootService.unbind(current) }
    }

    private companion object {
        const val ROOT_UID = 0
        const val ROOT_PROBE_TIMEOUT_MS = 8_000L
        const val BIND_TIMEOUT_MS = 20_000L
        const val READINESS_TIMEOUT_MS = 20_000L
        const val CHROOTCTL_READINESS_TIMEOUT_MS = 30_000L
        const val CHROOTCTL_QUERY_TIMEOUT_MS = 60_000L
        const val CHROOTCTL_PREVIEW_TIMEOUT_MS = 60_000L
        const val CHROOTCTL_START_TIMEOUT_MS = 20_000L
        const val CHROOTCTL_CANCEL_TIMEOUT_MS = 20_000L
        const val CHROOTCTL_MAX_BINDER_REQUEST_BYTES = 256 * 1024
        const val CHROOTCTL_MAX_EVENT_BYTES = 256 * 1024
        const val PROGRESS_TIMEOUT_MS = 10_000L
        const val CANCEL_TIMEOUT_MS = 20_000L
        const val EXECUTE_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
