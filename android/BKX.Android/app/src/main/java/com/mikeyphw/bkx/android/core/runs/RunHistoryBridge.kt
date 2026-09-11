package com.mikeyphw.bkx.android.core.runs

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RunHistoryRequestBuilder {
    const val OPERATION_LIST = "runs.list"
    const val OPERATION_SHOW = "runs.show"

    fun list(requestId: String, limit: Int = 50): BridgeRequestEnvelope {
        require(limit > 0) { "run history limit must be positive" }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_LIST,
            payload = buildJsonObject { put("limit", limit) },
        )
    }

    fun show(requestId: String, runId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_SHOW,
        payload = buildJsonObject { put("run_id", runId.trim().also { require(it.isNotBlank()) { "run id must not be blank" } }) },
    )
}


@Serializable
private data class SharedOperationSpec(
    val operation: String,
)

@Serializable
private data class SharedRunRecord(
    @SerialName("run_id") val runId: String,
    val operation: String,
    val status: String,
    val phase: String = "",
    @SerialName("job_id") val jobId: String = "",
    @SerialName("job_revision") val jobRevision: Long = 0L,
    @SerialName("operation_spec_snapshot") val operationSpecSnapshot: SharedOperationSpec? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("files_count") val filesCount: Long? = null,
    @SerialName("bytes_processed") val bytesProcessed: Long? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

object RunHistoryBridgeCodec {
    private val runJson = Json { ignoreUnknownKeys = true }

    fun decodeArtifacts(artifacts: List<BridgeArtifact>): List<RunSummary> = artifacts.mapNotNull(::decodeArtifact)

    fun decodeArtifact(artifact: BridgeArtifact): RunSummary? {
        if (artifact.label != "run") return null
        val raw = artifact.metadata["run_json"] ?: return null
        val record = runCatching { runJson.decodeFromString<SharedRunRecord>(raw) }.getOrNull() ?: return null
        val started = record.startedAt.toEpochMillisOrNull() ?: return null
        val completed = record.completedAt?.toEpochMillisOrNull()
        return RunSummary(
            requestId = record.runId,
            operation = record.operationSpecSnapshot?.operation?.takeIf { it.isNotBlank() } ?: record.operation,
            status = record.status.toRunStatus(),
            startedAtEpochMillis = started,
            finishedAtEpochMillis = completed,
            durationMillis = completed?.let { (it - started).coerceAtLeast(0L) },
            verification = VerificationState.NotRequested,
            summary = record.errorMessage.orEmpty(),
            jobId = record.jobId.ifBlank { null },
            jobRevision = record.jobRevision.takeIf { it > 0L },
            phase = record.phase.ifBlank { null },
        )
    }

    private fun String.toRunStatus(): RunStatus = when (lowercase()) {
        "started" -> RunStatus.Running
        "success" -> RunStatus.Success
        "failed" -> RunStatus.Failure
        "cancelled" -> RunStatus.Cancelled
        "interrupted" -> RunStatus.Interrupted
        else -> RunStatus.Failure
    }

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(this).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}
