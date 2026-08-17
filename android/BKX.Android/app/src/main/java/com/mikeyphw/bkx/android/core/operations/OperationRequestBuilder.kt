package com.mikeyphw.bkx.android.core.operations

import com.mikeyphw.bkx.android.core.bridge.BridgeCancelEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OperationRequestBuilder {
    const val OPERATION_RUNS_LIST = "runs.list"
    const val OPERATION_RUNS_SHOW = "runs.show"
    const val OPERATION_RUN_CANCEL = "run.cancel"

    fun buildReconnectStatusRequest(
        requestId: String,
        targetRequestId: String,
        createdAtEpochMillis: Long,
    ): BridgeRequestEnvelope {
        require(targetRequestId.isNotBlank()) { "target request id must not be blank" }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_RUNS_SHOW,
            payload = buildJsonObject {
                put("target_request_id", targetRequestId)
                put("output_mode", "json")
                put("created_at_ms", createdAtEpochMillis)
            },
        )
    }

    fun buildActiveRunsRequest(
        requestId: String,
        createdAtEpochMillis: Long,
    ): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_RUNS_LIST,
        payload = buildJsonObject {
            put("status", "active")
            put("output_mode", "json")
            put("created_at_ms", createdAtEpochMillis)
        },
    )

    fun buildCancelRequest(
        cancelRequestId: String,
        targetRequestId: String,
        cancelFilePath: String,
        reason: String = "user_requested",
        createdAtEpochMillis: Long,
    ): BridgeRequestEnvelope {
        require(targetRequestId.isNotBlank()) { "target request id must not be blank" }
        require(cancelFilePath.isNotBlank()) { "cancel file path must not be blank" }
        return BridgeRequestEnvelope(
            requestId = cancelRequestId,
            operation = OPERATION_RUN_CANCEL,
            payload = buildJsonObject {
                put("target_request_id", targetRequestId)
                put("cancel_file_path", cancelFilePath)
                put("reason", reason)
                put("output_mode", "json")
                put("created_at_ms", createdAtEpochMillis)
            },
        )
    }

    fun buildCancelEnvelope(targetRequestId: String): BridgeCancelEnvelope = BridgeCancelEnvelope(
        protocolVersion = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
        requestId = targetRequestId,
        action = "cancel",
    )
}
