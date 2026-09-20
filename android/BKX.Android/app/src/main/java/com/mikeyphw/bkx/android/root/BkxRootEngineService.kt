package com.mikeyphw.bkx.android.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestIdPolicy
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessCodec
import com.mikeyphw.bkx.android.embedded.GoNativeGateway
import java.io.FileInputStream
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Privileged protocol-v1 BKX execution process.
 *
 * libsu starts this service as UID 0. The ordinary BKX Go bridge and the
 * chrootctl integration both remain fixed typed transports. No Binder method
 * accepts an executable path, shell fragment, or arbitrary argv.
 */
class BkxRootEngineService : RootService() {
    private val goGateway: GoNativeGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)
    }
    private val chrootctlExecutor = ThreadPoolExecutor(
        1,
        CHROOTCTL_MAX_WORKERS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(CHROOTCTL_MAX_QUEUED_WORK),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val activeChrootctlRequests = ConcurrentHashMap<String, Boolean>()
    private val activeChrootctlCopies = ConcurrentHashMap<String, Boolean>()

    private val binder = object : IBkxRootEngineService.Stub() {
        override fun uid(): Int = Os.getuid()
        override fun readinessJson(): String = requireRoot { runBlocking { goGateway.readinessJson() } }
        override fun chrootctlReadinessJson(): String = requireRoot {
            ChrootctlReadinessCodec.encode(ChrootctlRuntimeResolver.resolve())
        }

        override fun chrootctlQueryJson(requestJson: String): String = requireRoot {
            invokeChrootctlRequest(requestJson, ChrootctlRootMode.Query, "query")
        }

        override fun chrootctlPreviewJson(requestJson: String): String = requireRoot {
            invokeChrootctlRequest(requestJson, ChrootctlRootMode.Preview, "preview")
        }

        override fun chrootctlStartOperation(requestJson: String, output: ParcelFileDescriptor): String = requireRoot {
            startChrootctlOperation(requestJson, output)
        }

        override fun chrootctlCopyManagedExportArtifact(copyId: String, path: String, output: ParcelFileDescriptor): String = requireRoot {
            startManagedExportCopy(copyId, path, output)
        }

        override fun chrootctlOperationJson(operationId: String): String = requireRoot {
            invokeChrootctlOperationCommand("operation", operationId)
        }

        override fun chrootctlCancelOperationJson(operationId: String): String = requireRoot {
            invokeChrootctlOperationCommand("cancel", operationId)
        }

        override fun chrootctlReconcileOperationJson(operationId: String): String = requireRoot {
            invokeChrootctlOperationCommand("reconcile", operationId)
        }

        override fun executeJson(requestJson: String): String = requireRoot { runBlocking { goGateway.executeJson(requestJson) } }
        override fun progressJson(requestId: String): String = requireRoot { runBlocking { goGateway.progressJson(requestId) } }
        override fun cancelJson(requestId: String): String = requireRoot { runBlocking { goGateway.cancelJson(requestId) } }
    }

    override fun onCreate() {
        super.onCreate()
        check(Os.getuid() == ROOT_UID) { "BKX privileged engine refused to initialize outside UID 0." }
        check(goGateway.isAvailable()) { "BKX privileged Go runtime is unavailable for this ABI." }
    }

    override fun onDestroy() {
        chrootctlExecutor.shutdownNow()
        activeChrootctlRequests.clear()
        activeChrootctlCopies.clear()
        goGateway.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun invokeChrootctlRequest(
        requestJson: String,
        expectedMode: ChrootctlRootMode,
        command: String,
    ): String {
        val validated = ChrootctlRootBridgePolicy.validateRequest(requestJson, expectedMode).getOrElse { error ->
            return chrootctlFailure(
                ChrootctlRootBridgePolicy.requestIdOrUnknown(requestJson),
                "INVALID_REQUEST",
                error.message ?: "Invalid chrootctl bridge request.",
            )
        }
        val executable = trustedChrootctlExecutable(validated.requestId) ?: return chrootctlFailure(
            validated.requestId,
            "CHROOTCTL_NOT_READY",
            "The active chrootctl module/runtime is not ready for BKX.",
        )
        val result = runCatching {
            ChrootctlBridgeProcess(executable).invoke(
                arguments = listOf("app", command, "--request-file", "-"),
                requestJson = requestJson,
            )
        }.getOrElse { error ->
            return chrootctlFailure(validated.requestId, "BRIDGE_PROCESS_FAILED", error.message ?: error.javaClass.simpleName)
        }
        return when (val checked = validateChrootctlProcessResult(executable, result, validated.requestId)) {
            is ChrootctlOutputValidation.Accepted -> checked.json
            is ChrootctlOutputValidation.Rejected -> chrootctlFailure(validated.requestId, checked.code, checked.message)
        }
    }

    private fun startChrootctlOperation(requestJson: String, output: ParcelFileDescriptor): String {
        val validated = ChrootctlRootBridgePolicy.validateRequest(requestJson, ChrootctlRootMode.Operation).getOrElse { error ->
            runCatching { output.close() }
            return chrootctlFailure(
                ChrootctlRootBridgePolicy.requestIdOrUnknown(requestJson),
                "INVALID_REQUEST",
                error.message ?: "Invalid chrootctl operation request.",
            )
        }
        if (activeChrootctlRequests.putIfAbsent(validated.requestId, true) != null) {
            runCatching { output.close() }
            return chrootctlFailure(validated.requestId, "REQUEST_ALREADY_ACTIVE", "Request ${validated.requestId} is already active.")
        }
        val executable = trustedChrootctlExecutable(validated.requestId)
        if (executable == null) {
            activeChrootctlRequests.remove(validated.requestId)
            runCatching { output.close() }
            return chrootctlFailure(validated.requestId, "CHROOTCTL_NOT_READY", "The active chrootctl module/runtime is not ready for BKX.")
        }
        val owned = runCatching { ParcelFileDescriptor.dup(output.fileDescriptor) }.getOrElse { error ->
            activeChrootctlRequests.remove(validated.requestId)
            runCatching { output.close() }
            return chrootctlFailure(validated.requestId, "OUTPUT_DUPLICATION_FAILED", error.message ?: "Unable to duplicate bridge output descriptor.")
        }
        runCatching { output.close() }
        try {
            chrootctlExecutor.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(owned).use { stream ->
                        val attempt = runCatching {
                            ChrootctlBridgeProcess(executable).stream(
                                arguments = listOf("app", "run", "--request-file", "-", "--events", "ndjson"),
                                requestJson = requestJson,
                                output = stream,
                            )
                        }
                        val result = attempt.getOrNull()
                        if (result == null) {
                            val error = attempt.exceptionOrNull()
                            appendSyntheticBridgeEvent(
                                stream,
                                validated.requestId,
                                "BRIDGE_PROCESS_FAILED",
                                error?.message ?: error?.javaClass?.simpleName ?: "chrootctl app run failed",
                            )
                        } else if (result.timedOut || result.truncated) {
                            val code = if (result.timedOut) "BRIDGE_TIMEOUT" else "BRIDGE_STDERR_TRUNCATED"
                            val detail = result.errorOutput.ifBlank { "chrootctl app run transport did not finish cleanly." }
                            appendSyntheticBridgeEvent(stream, validated.requestId, code, detail)
                        }
                    }
                } finally {
                    activeChrootctlRequests.remove(validated.requestId)
                }
            }
        } catch (_: RejectedExecutionException) {
            activeChrootctlRequests.remove(validated.requestId)
            runCatching { owned.close() }
            return chrootctlFailure(validated.requestId, "ROOT_SERVICE_BUSY", "The BKX chrootctl worker queue is full; retry after current work completes.")
        }
        return chrootctlSuccess(validated.requestId, JSONObject().put("state", "started"))
    }

    private fun startManagedExportCopy(copyId: String, path: String, output: ParcelFileDescriptor): String {
        val normalizedCopyId = runCatching { BridgeRequestIdPolicy.normalize(copyId) }.getOrElse { error ->
            runCatching { output.close() }
            return chrootctlFailure("unknown", "INVALID_COPY_ID", error.message ?: "Invalid managed artifact copy id.")
        }
        if (!path.startsWith('/') || path.length > CHROOTCTL_MAX_ARTIFACT_PATH_CHARS || '\u0000' in path) {
            runCatching { output.close() }
            return chrootctlFailure(normalizedCopyId, "INVALID_ARTIFACT_PATH", "Invalid managed chrootctl export path.")
        }
        if (activeChrootctlCopies.putIfAbsent(normalizedCopyId, true) != null) {
            runCatching { output.close() }
            return chrootctlFailure(normalizedCopyId, "COPY_ALREADY_ACTIVE", "Managed artifact copy $normalizedCopyId is already active.")
        }
        val executable = trustedChrootctlExecutable(normalizedCopyId)
        if (executable == null) {
            activeChrootctlCopies.remove(normalizedCopyId)
            runCatching { output.close() }
            return chrootctlFailure(normalizedCopyId, "CHROOTCTL_NOT_READY", "The active chrootctl module/runtime is not ready for BKX.")
        }
        val artifact = runCatching { resolveManagedExportArtifact(executable, path) }.getOrElse { error ->
            activeChrootctlCopies.remove(normalizedCopyId)
            runCatching { output.close() }
            return chrootctlFailure(normalizedCopyId, "UNTRUSTED_MANAGED_ARTIFACT", error.message ?: "Managed export validation failed.")
        }
        val owned = runCatching { ParcelFileDescriptor.dup(output.fileDescriptor) }.getOrElse { error ->
            activeChrootctlCopies.remove(normalizedCopyId)
            runCatching { output.close() }
            return chrootctlFailure(normalizedCopyId, "OUTPUT_DUPLICATION_FAILED", error.message ?: "Unable to duplicate artifact output descriptor.")
        }
        runCatching { output.close() }
        try {
            chrootctlExecutor.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(owned).use { destination ->
                        FileInputStream(artifact.path).use { source ->
                            val buffer = ByteArray(CHROOTCTL_ARTIFACT_COPY_BUFFER_BYTES)
                            var copied = 0L
                            while (copied < artifact.sizeBytes) {
                                val remaining = artifact.sizeBytes - copied
                                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(count > 0) { "Managed chrootctl export ended before the registered size was copied." }
                                destination.write(buffer, 0, count)
                                copied += count
                            }
                            check(source.read() == -1) { "Managed chrootctl export grew after registry validation." }
                            destination.flush()
                        }
                    }
                } catch (_: Throwable) {
                    runCatching { owned.close() }
                } finally {
                    activeChrootctlCopies.remove(normalizedCopyId)
                }
            }
        } catch (_: RejectedExecutionException) {
            activeChrootctlCopies.remove(normalizedCopyId)
            runCatching { owned.close() }
            return chrootctlFailure(normalizedCopyId, "ROOT_SERVICE_BUSY", "The BKX chrootctl worker queue is full; retry after current work completes.")
        }
        return chrootctlSuccess(
            normalizedCopyId,
            JSONObject()
                .put("state", "started")
                .put("artifactId", artifact.artifactId)
                .put("path", artifact.path)
                .put("sizeBytes", artifact.sizeBytes),
        )
    }

    private fun resolveManagedExportArtifact(executable: String, requestedPath: String): TrustedChrootctlExportArtifact {
        val queryId = "bkx-artifact-${System.nanoTime().toString(16)}"
        val request = JSONObject()
            .put("protocolVersion", 1)
            .put("requestId", queryId)
            .put("operation", "artifacts.list")
            .put("payload", JSONObject())
            .toString()
        val result = ChrootctlBridgeProcess(executable).invoke(
            arguments = listOf("app", "query", "--request-file", "-"),
            requestJson = request,
        )
        val checked = validateChrootctlProcessResult(executable, result, queryId)
        val response = when (checked) {
            is ChrootctlOutputValidation.Accepted -> checked.json
            is ChrootctlOutputValidation.Rejected -> error(checked.message)
        }
        return ChrootctlManagedArtifactPolicy.resolveExport(response, requestedPath)
    }

    private fun invokeChrootctlOperationCommand(command: String, operationId: String): String {
        if (command !in setOf("operation", "cancel", "reconcile") || !ChrootctlRootBridgePolicy.validOperationId(operationId)) {
            return chrootctlFailure(operationId.take(128).ifBlank { "unknown" }, "INVALID_OPERATION_ID", "Invalid chrootctl operation id.")
        }
        val executable = trustedChrootctlExecutable(operationId) ?: return chrootctlFailure(
            operationId,
            "CHROOTCTL_NOT_READY",
            "The active chrootctl module/runtime is not ready for BKX.",
        )
        val result = runCatching {
            ChrootctlBridgeProcess(executable).invoke(listOf("app", command, operationId))
        }.getOrElse { error ->
            return chrootctlFailure(operationId, "BRIDGE_PROCESS_FAILED", error.message ?: error.javaClass.simpleName)
        }
        return when (val checked = validateChrootctlProcessResult(executable, result, operationId)) {
            is ChrootctlOutputValidation.Accepted -> checked.json
            is ChrootctlOutputValidation.Rejected -> chrootctlFailure(operationId, checked.code, checked.message)
        }
    }

    private fun trustedChrootctlExecutable(requestId: String): String? {
        require(requestId.isNotBlank())
        val readiness = ChrootctlRuntimeResolver.resolve()
        return readiness.selectedPath?.takeIf { readiness.ready }
    }

    private fun appendSyntheticBridgeEvent(
        output: java.io.OutputStream,
        requestId: String,
        code: String,
        message: String,
    ) {
        val event = JSONObject()
            .put("protocolVersion", 1)
            .put("type", "bridge_process_failed")
            .put("operationId", "")
            .put("requestId", requestId)
            .put(
                "payload",
                JSONObject()
                    .put("code", code)
                    .put("message", message.take(2048)),
            )
        runCatching {
            output.write((event.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun chrootctlSuccess(requestId: String, data: JSONObject): String = JSONObject()
        .put("protocolVersion", 1)
        .put("requestId", requestId)
        .put("ok", true)
        .put("data", data)
        .put("warnings", org.json.JSONArray())
        .put("nextSteps", org.json.JSONArray())
        .toString()

    private fun chrootctlFailure(requestId: String, code: String, message: String): String = JSONObject()
        .put("protocolVersion", 1)
        .put("requestId", requestId.ifBlank { "unknown" })
        .put("ok", false)
        .put("data", JSONObject.NULL)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message.take(512))
                .put("details", JSONObject()),
        )
        .put("warnings", org.json.JSONArray())
        .put("nextSteps", org.json.JSONArray())
        .toString()

    private inline fun <T> requireRoot(block: () -> T): T {
        check(Os.getuid() == ROOT_UID) {
            "BKX privileged engine refused to run because the libsu service is not UID 0."
        }
        return block()
    }

    private companion object {
        const val ROOT_UID = 0
        const val ROOT_STATE_NAMESPACE = "go-root"
        const val CHROOTCTL_MAX_WORKERS = 2
        const val CHROOTCTL_MAX_QUEUED_WORK = 4
        const val CHROOTCTL_MAX_ARTIFACT_PATH_CHARS = 4096
        const val CHROOTCTL_ARTIFACT_COPY_BUFFER_BYTES = 256 * 1024
    }
}
