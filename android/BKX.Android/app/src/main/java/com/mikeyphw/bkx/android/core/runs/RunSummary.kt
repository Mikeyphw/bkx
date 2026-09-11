package com.mikeyphw.bkx.android.core.runs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus {
    @SerialName("queued") Queued,
    @SerialName("running") Running,
    @SerialName("success") Success,
    @SerialName("failure") Failure,
    @SerialName("cancelled") Cancelled,
    @SerialName("interrupted") Interrupted,
}

@Serializable
enum class VerificationState {
    @SerialName("not_requested") NotRequested,
    @SerialName("pending") Pending,
    @SerialName("passed") Passed,
    @SerialName("failed") Failed,
}

@Serializable
data class RunArtifactSummary(
    val label: String,
    val displayName: String,
    val sha256: String? = null,
) {
    init {
        require(displayName.none { it == '/' || it == '\\' }) { "Run summaries must not persist artifact paths." }
    }
}

@Serializable
data class RunSummary(
    val requestId: String,
    val operation: String,
    val jobId: String? = null,
    val status: RunStatus,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val durationMillis: Long? = null,
    val artifacts: List<RunArtifactSummary> = emptyList(),
    val verification: VerificationState = VerificationState.NotRequested,
    val summary: String = "",
    val jobRevision: Long? = null,
    val phase: String? = null,
) {
    init {
        require(requestId.isNotBlank()) { "request id must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(summary.lines().none { it.startsWith("/") || it.contains(":\\") }) {
            "Run summaries must not persist sensitive filesystem paths."
        }
    }
}
