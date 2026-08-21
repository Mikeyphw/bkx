package com.mikeyphw.bkx.android.core.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class BridgeRequestEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
    @SerialName("request_id") val requestId: String,
    val operation: String,
    val payload: JsonObject = buildJsonObject { },
)

@Serializable
data class BridgeResultEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("request_id") val requestId: String,
    val status: BridgeResultStatus,
    val summary: String = "",
    val errors: List<BridgeError> = emptyList(),
    val artifacts: List<BridgeArtifact> = emptyList(),
    val metrics: BridgeMetrics? = null,
)

@Serializable
enum class BridgeResultStatus {
    @SerialName("success") Success,
    @SerialName("failure") Failure,
    @SerialName("cancelled") Cancelled,
}

@Serializable
data class BridgeMetrics(
    @SerialName("duration_ms") val durationMillis: Long = 0L,
) {
    init {
        require(durationMillis >= 0L) { "duration_ms must be non-negative" }
    }
}

@Serializable
data class BridgeError(
    val code: String,
    val message: String,
    val action: String = "",
)

@Serializable
data class BridgeArtifact(
    val label: String,
    val path: String,
    val sha256: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("modified_at_epoch_millis") val modifiedAtEpochMillis: Long? = null,
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0L) { "artifact size_bytes must be non-negative" }
        require(modifiedAtEpochMillis == null || modifiedAtEpochMillis >= 0L) { "artifact modified_at_epoch_millis must be non-negative" }
    }
}

@Serializable
data class BridgeProgressEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
    @SerialName("request_id") val requestId: String,
    val seq: Long,
    val phase: String,
    val message: String,
    val progress: Double? = null,
)

@Serializable
data class BridgeCancelEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
    @SerialName("request_id") val requestId: String,
    val action: String = "cancel",
)

data class BridgeRequestFiles(
    val requestId: String,
    val requestFilePath: String,
    val resultFilePath: String,
    val progressFilePath: String,
    val cancelFilePath: String,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(requestFilePath.isNotBlank()) { "requestFilePath must not be blank" }
        require(resultFilePath.isNotBlank()) { "resultFilePath must not be blank" }
        require(progressFilePath.isNotBlank()) { "progressFilePath must not be blank" }
        require(cancelFilePath.isNotBlank()) { "cancelFilePath must not be blank" }
    }
}
