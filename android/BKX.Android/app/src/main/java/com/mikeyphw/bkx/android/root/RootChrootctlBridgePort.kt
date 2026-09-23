package com.mikeyphw.bkx.android.root

import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgeCodec
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgeMode
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgePort
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgeRequest
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgeResponse
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlOperation
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlOperationEvent
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessPort
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlRunResult
import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChrootctlBridgeUnavailableException(message: String) : IllegalStateException(message)

/**
 * App-process typed chrootctl transport.
 *
 * All executable selection and process creation remains in the UID-0 RootService.
 * The app process can choose only one of the six BKX chroot integration operations.
 */
class RootChrootctlBridgePort internal constructor(
    private val rootClient: RootBkxServiceClient,
    private val readinessPort: ChrootctlReadinessPort,
    private val modeProvider: () -> RootAccessMode,
    private val operationLedger: ChrootctlOperationLedger,
) : ChrootctlBridgePort {
    override suspend fun query(request: ChrootctlBridgeRequest): ChrootctlBridgeResponse {
        requireMode(request, ChrootctlBridgeMode.Query)
        requireReady()
        val encoded = ChrootctlBridgeCodec.encodeRequest(request)
        return ChrootctlBridgeCodec.decodeResponse(rootClient.chrootctlQueryJson(encoded), request.requestId)
    }

    override suspend fun preview(request: ChrootctlBridgeRequest): ChrootctlBridgeResponse {
        requireMode(request, ChrootctlBridgeMode.Preview)
        requireReady()
        val encoded = ChrootctlBridgeCodec.encodeRequest(request)
        return ChrootctlBridgeCodec.decodeResponse(rootClient.chrootctlPreviewJson(encoded), request.requestId)
    }

    override suspend fun run(
        request: ChrootctlBridgeRequest,
        onEvent: suspend (ChrootctlOperationEvent) -> Unit,
    ): ChrootctlRunResult {
        requireMode(request, ChrootctlBridgeMode.Operation)
        requireReady()
        val encoded = ChrootctlBridgeCodec.encodeRequest(request)
        var operationId: String? = null
        var terminal: ChrootctlOperationEvent? = null
        val startRaw = try {
            rootClient.chrootctlStreamOperation(encoded) { rawEvent ->
                val event = ChrootctlBridgeCodec.decodeEvent(rawEvent, request.requestId)
                event.operationId.takeIf(String::isNotBlank)?.let { id ->
                    operationId = id
                    operationLedger.recordStarted(request.requestId, id)
                }
                if (event.terminal) {
                    terminal = event
                    event.operationId.takeIf(String::isNotBlank)?.let { operationLedger.recordTerminal(request.requestId, it) }
                }
                onEvent(event)
            }
        } catch (cancelled: CancellationException) {
            // Closing the Binder/PFD stream alone does not cancel chrootctl's
            // persistent operation. Explicitly cancel the authoritative
            // operation before propagating coroutine cancellation.
            val id = operationId ?: operationLedger.operationId(request.requestId)
            if (!id.isNullOrBlank()) {
                withContext(NonCancellable) {
                    runCatching { rootClient.chrootctlCancelOperationJson(id) }
                }
            }
            throw cancelled
        }
        val start = ChrootctlBridgeCodec.decodeResponse(startRaw, request.requestId)
        if (start.ok && terminal == null) {
            val interrupted = ChrootctlOperationEvent(
                type = "bridge_stream_interrupted",
                operationId = operationId.orEmpty(),
                requestId = request.requestId,
                payload = buildJsonObject {
                    put("message", "The chrootctl operation stream ended before a terminal event; reconcile using the persistent operation id when available.")
                },
            )
            terminal = interrupted
            onEvent(interrupted)
        }
        return ChrootctlRunResult(start = start, operationId = operationId ?: operationLedger.operationId(request.requestId), terminalEvent = terminal)
    }

    override suspend fun operation(operationId: String): ChrootctlBridgeResponse {
        requireReady()
        requireOperationId(operationId)
        return ChrootctlBridgeCodec.decodeResponse(rootClient.chrootctlOperationJson(operationId), operationId)
    }

    override suspend fun cancel(operationId: String): ChrootctlBridgeResponse {
        requireReady()
        requireOperationId(operationId)
        return ChrootctlBridgeCodec.decodeResponse(rootClient.chrootctlCancelOperationJson(operationId), operationId)
    }

    override suspend fun reconcile(operationId: String): ChrootctlBridgeResponse {
        requireReady()
        requireOperationId(operationId)
        return ChrootctlBridgeCodec.decodeResponse(rootClient.chrootctlReconcileOperationJson(operationId), operationId)
    }

    private suspend fun requireReady() {
        if (modeProvider() == RootAccessMode.Disabled) {
            throw ChrootctlBridgeUnavailableException("BKX root access is disabled; chrootctl operations require the root bridge.")
        }
        val readiness = try {
            readinessPort.probe()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw ChrootctlBridgeUnavailableException(error.message ?: "Unable to verify chrootctl readiness.")
        }
        if (!readiness.ready) {
            throw ChrootctlBridgeUnavailableException(readiness.detail)
        }
    }

    private fun requireMode(request: ChrootctlBridgeRequest, expected: ChrootctlBridgeMode) {
        val operation = requireNotNull(ChrootctlOperation.fromWireName(request.operation)) { "Unsupported BKX chrootctl operation." }
        require(operation.mode == expected) { "${operation.wireName} is ${operation.mode}, not $expected." }
    }

    private fun requireOperationId(operationId: String) {
        require(operationId.length in 1..128 && OPERATION_ID.matches(operationId)) { "Invalid chrootctl operation id." }
    }

    private companion object {
        val OPERATION_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
