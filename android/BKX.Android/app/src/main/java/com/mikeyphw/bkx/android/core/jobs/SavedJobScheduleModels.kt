package com.mikeyphw.bkx.android.core.jobs

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SavedJobScheduleDefinition(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("schedule_id") val scheduleId: String,
    @SerialName("job_id") val jobId: String,
    val enabled: Boolean = true,
    @SerialName("interval_minutes") val intervalMinutes: Long,
    @SerialName("require_charging") val requireCharging: Boolean = false,
    val network: String = "none",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    init {
        require(scheduleId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$"))) { "invalid schedule id" }
        require(jobId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$"))) { "invalid job id" }
        require(intervalMinutes in 15L..525_600L) { "schedule interval must be 15..525600 minutes" }
        require(network == "none" || network == "any" || network == "unmetered") { "network must be none, any, or unmetered" }
    }
}

object SavedJobScheduleRequestBuilder {
    const val OPERATION_LIST = "schedules.list"
    const val OPERATION_PUT = "schedules.put"
    const val OPERATION_SET_ENABLED = "schedules.set_enabled"
    const val OPERATION_DELETE = "schedules.delete"

    fun list(requestId: String) = BridgeRequestEnvelope(requestId = requestId, operation = OPERATION_LIST)

    fun put(requestId: String, value: SavedJobScheduleDefinition) = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_PUT,
        payload = buildJsonObject {
            put("schedule_id", value.scheduleId)
            put("job_id", value.jobId)
            put("enabled", value.enabled)
            put("interval_minutes", value.intervalMinutes)
            put("require_charging", value.requireCharging)
            put("network", value.network)
        },
    )

    fun setEnabled(requestId: String, scheduleId: String, enabled: Boolean) = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_SET_ENABLED,
        payload = buildJsonObject {
            put("schedule_id", scheduleId)
            put("enabled", enabled)
        },
    )

    fun delete(requestId: String, scheduleId: String) = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_DELETE,
        payload = buildJsonObject { put("schedule_id", scheduleId) },
    )
}

object SavedJobScheduleBridgeCodec {
    fun decodeArtifacts(artifacts: List<BridgeArtifact>): List<SavedJobScheduleDefinition> = artifacts.mapNotNull { artifact ->
        if (artifact.label != "saved_job_schedule") return@mapNotNull null
        val raw = artifact.metadata["schedule_json"] ?: return@mapNotNull null
        runCatching { BridgeJson.json.decodeFromString<SavedJobScheduleDefinition>(raw) }.getOrNull()
    }
}
