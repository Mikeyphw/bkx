package com.mikeyphw.bkx.android.core.operations

import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeProtocolException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Parses BKX progress status-file snapshots or NDJSON progress streams and
 * converts only events for the expected request id into UI runtime events.
 */
class OperationProgressPoller(
    private val tracker: OperationProgressTracker = OperationProgressTracker(),
) {
    fun parseProgressEvents(
        rawProgress: String,
        expectedRequestId: String,
        observedAtEpochMillis: Long,
    ): List<OperationProgressEvent> {
        require(expectedRequestId.isNotBlank()) { "expected request id must not be blank" }
        val trimmed = rawProgress.trim()
        if (trimmed.isEmpty()) return emptyList()

        val envelopes = try {
            if (trimmed.startsWith("[")) {
                BridgeJson.json.decodeFromString<List<BridgeProgressEnvelope>>(trimmed)
            } else {
                trimmed.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { BridgeJson.json.decodeFromString<BridgeProgressEnvelope>(it) }
                    .toList()
            }
        } catch (exc: SerializationException) {
            throw BridgeProtocolException("Progress stream is malformed JSON.", exc)
        } catch (exc: IllegalArgumentException) {
            throw BridgeProtocolException("Progress stream is malformed JSON.", exc)
        }

        return envelopes.sortedBy { it.seq }.map { envelope ->
            BridgeJson.requireSupported(envelope.protocolVersion)
            require(envelope.requestId == expectedRequestId) {
                "progress event request id ${envelope.requestId} does not match expected request id $expectedRequestId"
            }
            OperationProgressEvent.fromBridgeProgress(envelope, observedAtEpochMillis)
        }
    }

    fun consumePolledProgress(
        state: OperationRuntimeState,
        rawProgress: String,
        expectedRequestId: String,
        observedAtEpochMillis: Long,
    ): OperationRuntimeState = parseProgressEvents(
        rawProgress = rawProgress,
        expectedRequestId = expectedRequestId,
        observedAtEpochMillis = observedAtEpochMillis,
    ).fold(state) { current, event -> tracker.consume(current, event) }
}
