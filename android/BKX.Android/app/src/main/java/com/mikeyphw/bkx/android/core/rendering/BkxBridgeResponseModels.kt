package com.mikeyphw.bkx.android.core.rendering

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class BkxBridgeResponseEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("request_id") val requestId: String,
    val ok: Boolean,
    val operation: String,
    val result: JsonObject? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<BkxBridgeResponseError> = emptyList(),
    val verification: BkxBridgeVerification,
    val artifacts: List<JsonObject> = emptyList(),
    @SerialName("completed_at_ms") val completedAtMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "response request id must not be blank" }
        require(operation.isNotBlank()) { "response operation must not be blank" }
        require(completedAtMillis >= 0) { "completed_at_ms must be non-negative" }
    }
}

@Serializable
data class BkxBridgeResponseError(
    val code: String,
    val message: String,
    val details: Map<String, JsonElement> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "error code must not be blank" }
        require(message.isNotBlank()) { "error message must not be blank" }
    }
}

@Serializable
data class BkxBridgeVerification(
    val status: BkxBridgeVerificationStatus,
    val evidence: List<JsonElement> = emptyList(),
)

@Serializable
enum class BkxBridgeVerificationStatus {
    @SerialName("passed") Passed,
    @SerialName("failed") Failed,
    @SerialName("not_applicable") NotApplicable,
    @SerialName("unknown") Unknown,
}
