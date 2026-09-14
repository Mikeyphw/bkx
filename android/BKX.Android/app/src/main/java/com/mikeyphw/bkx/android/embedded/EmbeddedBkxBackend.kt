package com.mikeyphw.bkx.android.embedded

import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.backend.BackendSubmission
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import java.io.File
import java.io.IOException
import com.mikeyphw.bkx.android.core.state.BkxAtomicFilePublisher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * In-process BackendPort backed by the canonical protocol-v1 embedded runtime.
 *
 * GO14 uses the packaged Go runtime for every supported Android ABI while preserving the
 * BridgeRequestEnvelope/BridgeResultEnvelope protocol-v1 contract.
 */
class EmbeddedBkxBackend(
    private val gateway: EmbeddedRuntimeGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackendPort {
    override suspend fun checkReadiness(): BackendReadiness = withContext(ioDispatcher) {
        try {
            val document = BridgeJson.json.decodeFromString<EmbeddedReadinessDocument>(gateway.readinessJson())
            if (document.protocolVersion != BridgeJson.SUPPORTED_PROTOCOL_VERSION) {
                BackendReadiness.UnsupportedProtocol(
                    expected = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
                    actual = document.protocolVersion,
                )
            } else if (document.runtime !in SUPPORTED_RUNTIMES) {
                BackendReadiness.ProbeFailed("Unexpected embedded BKX runtime '${document.runtime}'.")
            } else {
                BackendReadiness.Ready(
                    backendVersion = document.backendVersion,
                    bridgeProtocolVersion = document.protocolVersion,
                    capabilities = document.capabilities,
                    runtime = document.runtime,
                )
            }
        } catch (exc: RootAccessRequiredException) {
            BackendReadiness.RootPermissionRequired
        } catch (exc: Exception) {
            BackendReadiness.ProbeFailed("Embedded BKX startup failed: ${exc.message ?: exc::class.java.simpleName}")
        }
    }

    override suspend fun submit(
        request: BridgeRequestEnvelope,
        files: BridgeRequestFiles,
    ): BackendSubmission = withContext(ioDispatcher) {
        require(request.requestId == files.requestId) { "request id must match request file set" }
        val readiness = checkReadiness()
        if (readiness !is BackendReadiness.Ready) {
            return@withContext BackendSubmission(request.requestId, accepted = false, readiness = readiness)
        }

        try {
            val requestJson = BridgeJson.encodeRequest(request)
            // Publish the request descriptor before execution so long-running work has
            // durable route evidence even if Android is recreated before final result.
            publish(files.requestFilePath, requestJson)
            val resultJson = gateway.executeJson(requestJson)
            // Parse before publishing so malformed runtime output can never become a final result.
            BridgeJson.parseFinalResult(resultJson, expectedRequestId = request.requestId)
            publish(files.resultFilePath, resultJson)
            BackendSubmission(request.requestId, accepted = true, readiness = readiness)
        } catch (exc: Exception) {
            BackendSubmission(
                requestId = request.requestId,
                accepted = false,
                readiness = BackendReadiness.ProbeFailed(
                    "Embedded BKX execution failed before result publication: ${exc.message ?: exc::class.java.simpleName}",
                ),
            )
        }
    }

    override suspend fun progress(requestId: String): BridgeProgressEnvelope? = withContext(ioDispatcher) {
        try {
            BridgeJson.parseProgress(gateway.progressJson(requestId), expectedRequestId = requestId)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun cancel(requestId: String, cancelFilePath: String): BackendSubmission = withContext(ioDispatcher) {
        val readiness = checkReadiness()
        if (readiness !is BackendReadiness.Ready) {
            return@withContext BackendSubmission(requestId, accepted = false, readiness = readiness)
        }
        try {
            val result = gateway.cancelJson(requestId)
            // Validate the cancellation acknowledgement before publishing it.
            val acknowledgement = BridgeJson.parseFinalResult(result, expectedRequestId = requestId)
            publish(cancelFilePath, BridgeJson.encodeCancel(com.mikeyphw.bkx.android.core.bridge.BridgeCancelEnvelope(requestId = requestId)))
            BackendSubmission(requestId, accepted = acknowledgement.status == com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus.Success, readiness = readiness)
        } catch (exc: Exception) {
            BackendSubmission(
                requestId = requestId,
                accepted = false,
                readiness = BackendReadiness.ProbeFailed("Embedded cancellation failed: ${exc.message ?: exc::class.java.simpleName}"),
            )
        }
    }

    override suspend fun readFinalResult(rawResult: String): BridgeResultEnvelope =
        BridgeJson.parseFinalResult(rawResult)

    private fun publish(path: String, content: String) {
        val target = File(path)
        val parent = target.parentFile ?: throw IOException("Result path has no parent: $path")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create embedded request directory: ${parent.absolutePath}")
        }
        BkxAtomicFilePublisher.publishText(target, content)
    }

    private companion object {
        val SUPPORTED_RUNTIMES = setOf("go-native-android")
    }
}

@Serializable
private data class EmbeddedReadinessDocument(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("backend_version") val backendVersion: String,
    val runtime: String,
    @SerialName("python_version") val pythonVersion: String,
    val capabilities: Set<String>,
)
