package com.mikeyphw.bkx.android.core.operations

import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.rendering.BkxBridgeResponseEnvelope
import com.mikeyphw.bkx.android.core.rendering.BkxBridgeResponseError
import com.mikeyphw.bkx.android.core.rendering.BkxBridgeVerificationStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ActiveOperationStatus {
    @SerialName("queued") Queued,
    @SerialName("running") Running,
    @SerialName("cancelling") Cancelling,
    @SerialName("success") Success,
    @SerialName("failure") Failure,
    @SerialName("cancelled") Cancelled,
    @SerialName("interrupted") Interrupted,
    @SerialName("unknown") Unknown,
}

@Serializable
data class ActiveOperation(
    @SerialName("request_id") val requestId: String,
    val operation: String,
    val status: ActiveOperationStatus = ActiveOperationStatus.Queued,
    @SerialName("started_at_epoch_millis") val startedAtEpochMillis: Long,
    @SerialName("last_seen_epoch_millis") val lastSeenEpochMillis: Long = startedAtEpochMillis,
    @SerialName("last_phase") val lastPhase: String = "queued",
    @SerialName("last_message") val lastMessage: String = "Queued for BKX backend.",
    val progress: Double? = null,
    @SerialName("files_done") val filesDone: Long? = null,
    @SerialName("files_total") val filesTotal: Long? = null,
    @SerialName("bytes_done") val bytesDone: Long? = null,
    @SerialName("bytes_total") val bytesTotal: Long? = null,
    @SerialName("last_seq") val lastSeq: Long = 0,
    @SerialName("cancel_file_path") val cancelFilePath: String? = null,
    @SerialName("reconnect_token") val reconnectToken: String = requestId,
) {
    init {
        require(requestId.isNotBlank()) { "active operation request id must not be blank" }
        require(operation.isNotBlank()) { "active operation operation must not be blank" }
        require(progress == null || progress in 0.0..1.0) { "progress must be between 0.0 and 1.0" }
        require(cancelFilePath == null || cancelFilePath.isNotBlank()) { "cancel file path must not be blank when present" }
    }

    fun withProgress(event: OperationProgressEvent): ActiveOperation {
        require(event.requestId == requestId) { "progress event request id must match active operation" }
        require(event.seq > lastSeq) { "progress events must be consumed in increasing sequence order per request id" }
        return copy(
            status = ActiveOperationStatus.Running,
            lastSeenEpochMillis = event.observedAtEpochMillis,
            lastPhase = event.phase,
            lastMessage = event.message,
            progress = event.progress ?: progress,
            filesDone = event.filesDone ?: filesDone,
            filesTotal = event.filesTotal ?: filesTotal,
            bytesDone = event.bytesDone ?: bytesDone,
            bytesTotal = event.bytesTotal ?: bytesTotal,
            lastSeq = event.seq,
        )
    }

    fun asCancelling(cancelRequestId: String, nowEpochMillis: Long): ActiveOperation {
        require(cancelRequestId.isNotBlank()) { "cancel request id must not be blank" }
        return copy(
            status = ActiveOperationStatus.Cancelling,
            lastSeenEpochMillis = nowEpochMillis,
            lastPhase = "cancelling",
            lastMessage = "Cancellation requested with $cancelRequestId.",
        )
    }

    fun finished(status: ActiveOperationStatus, message: String, nowEpochMillis: Long): ActiveOperation = copy(
        status = status,
        lastSeenEpochMillis = nowEpochMillis,
        lastPhase = status.name.lowercase(),
        lastMessage = message,
        progress = if (status == ActiveOperationStatus.Success) 1.0 else progress,
    )
}

@Serializable
data class OperationProgressEvent(
    @SerialName("request_id") val requestId: String,
    @SerialName("run_id") val runId: String = requestId,
    @SerialName("job_id") val jobId: String? = null,
    val seq: Long,
    val phase: String,
    val message: String,
    val progress: Double? = null,
    @SerialName("files_done") val filesDone: Long? = null,
    @SerialName("files_total") val filesTotal: Long? = null,
    @SerialName("bytes_done") val bytesDone: Long? = null,
    @SerialName("bytes_total") val bytesTotal: Long? = null,
    @SerialName("observed_at_epoch_millis") val observedAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "progress request id must not be blank" }
        require(seq >= 0) { "progress seq must be non-negative" }
        require(phase.isNotBlank()) { "progress phase must not be blank" }
        require(message.isNotBlank()) { "progress message must not be blank" }
        require(progress == null || progress in 0.0..1.0) { "progress must be between 0.0 and 1.0" }
    }

    companion object {
        fun fromBridgeProgress(
            envelope: BridgeProgressEnvelope,
            observedAtEpochMillis: Long,
        ): OperationProgressEvent = OperationProgressEvent(
            requestId = envelope.requestId,
            runId = envelope.runId ?: envelope.requestId,
            jobId = envelope.jobId,
            seq = envelope.seq,
            phase = envelope.phase,
            message = envelope.message,
            progress = envelope.progress,
            filesDone = envelope.filesDone,
            filesTotal = envelope.filesTotal,
            bytesDone = envelope.bytesDone,
            bytesTotal = envelope.bytesTotal,
            observedAtEpochMillis = observedAtEpochMillis,
        )
    }
}

@Serializable
data class OperationRuntimeState(
    @SerialName("active_operations") val activeOperations: Map<String, ActiveOperation> = emptyMap(),
    @SerialName("progress_events") val progressEvents: Map<String, List<OperationProgressEvent>> = emptyMap(),
    @SerialName("failure_states") val failureStates: Map<String, OperationFailureUiState> = emptyMap(),
    @SerialName("focused_request_id") val focusedRequestId: String? = null,
) {
    init {
        require(focusedRequestId == null || focusedRequestId.isNotBlank()) { "focused request id must not be blank when present" }
    }

    fun activeFor(requestId: String): ActiveOperation? = activeOperations[requestId]

    val focusedOperation: ActiveOperation? = focusedRequestId?.let { activeOperations[it] }

    val hasActiveWork: Boolean = activeOperations.values.any {
        it.status == ActiveOperationStatus.Queued || it.status == ActiveOperationStatus.Running || it.status == ActiveOperationStatus.Cancelling
    }
}

@Serializable
data class OperationReconnectSnapshot(
    @SerialName("active_operations") val activeOperations: List<ActiveOperation>,
    @SerialName("captured_at_epoch_millis") val capturedAtEpochMillis: Long,
) {
    init {
        val ids = activeOperations.map { it.requestId }
        require(ids.distinct().size == ids.size) { "reconnect snapshot must not contain duplicate request ids" }
    }
}

@Serializable
enum class OperationFailureKind {
    @SerialName("backend_timeout") BackendTimeout,
    @SerialName("quota_exceeded") QuotaExceeded,
    @SerialName("process_died") ProcessDied,
    @SerialName("permission_denied") PermissionDenied,
    @SerialName("backend_not_ready") BackendNotReady,
    @SerialName("malformed_result") MalformedResult,
    @SerialName("saf_grant_lost") SafGrantLost,
    @SerialName("cancelled") Cancelled,
    @SerialName("interrupted") Interrupted,
    @SerialName("unknown") Unknown,
}

@Serializable
data class OperationFailureUiState(
    val kind: OperationFailureKind,
    val code: String,
    val title: String,
    val message: String,
    val actions: List<String> = emptyList(),
    @SerialName("retry_allowed") val retryAllowed: Boolean = true,
) {
    init {
        require(code.isNotBlank()) { "failure code must not be blank" }
        require(title.isNotBlank()) { "failure title must not be blank" }
        require(message.isNotBlank()) { "failure message must not be blank" }
    }
}

@Serializable
sealed interface OperationResultUiState {
    @Serializable
    @SerialName("not_started")
    data object NotStarted : OperationResultUiState

    @Serializable
    @SerialName("success")
    data class Success(val summary: String, val actions: List<String> = emptyList()) : OperationResultUiState

    @Serializable
    @SerialName("failed")
    data class Failed(val failure: OperationFailureUiState) : OperationResultUiState

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(val summary: String, val actions: List<String> = listOf("Review partial artifacts", "Start a new request")) : OperationResultUiState
}

object OperationFailureMapper {
    fun fromBridgeError(error: BridgeError): OperationFailureUiState = fromCodeAndMessage(
        code = error.code,
        message = error.message,
        explicitAction = error.action.takeIf { it.isNotBlank() },
    )

    fun fromBridgeResponseError(error: BkxBridgeResponseError): OperationFailureUiState = fromCodeAndMessage(
        code = error.code,
        message = error.message,
        explicitAction = null,
    )

    fun fromProtocolException(message: String): OperationFailureUiState = OperationFailureUiState(
        kind = OperationFailureKind.MalformedResult,
        code = "BKX_MALFORMED_RESULT",
        title = "Malformed BKX result",
        message = message,
        actions = listOf("Open engine diagnostics", "Retry after checking BKX runtime"),
        retryAllowed = false,
    )

    fun timeout(message: String = "BKX did not publish a final result before the Android timeout."): OperationFailureUiState = OperationFailureUiState(
        kind = OperationFailureKind.BackendTimeout,
        code = "BKX_BACKEND_TIMEOUT",
        title = "Backend timed out",
        message = message,
        actions = listOf("Poll status", "Cancel request", "Open engine diagnostics"),
    )

    fun quota(message: String = "Android refused more background work for this request."): OperationFailureUiState = OperationFailureUiState(
        kind = OperationFailureKind.QuotaExceeded,
        code = "BKX_ANDROID_QUOTA_EXCEEDED",
        title = "Background quota reached",
        message = message,
        actions = listOf("Retry later", "Open engine diagnostics"),
    )

    fun processDied(message: String = "The embedded BKX process ended before a final result was written."): OperationFailureUiState = OperationFailureUiState(
        kind = OperationFailureKind.ProcessDied,
        code = "BKX_PROCESS_DIED",
        title = "Backend process ended",
        message = message,
        actions = listOf("Poll status", "Retry operation", "Open engine diagnostics"),
    )

    fun interrupted(message: String = "BKX was interrupted before a trustworthy final result was committed."): OperationFailureUiState = OperationFailureUiState(
        kind = OperationFailureKind.Interrupted,
        code = "BKX_OPERATION_INTERRUPTED",
        title = "Operation interrupted",
        message = message,
        actions = listOf("Review partial artifacts", "Retry operation", "Open engine diagnostics"),
        retryAllowed = true,
    )

    fun fromBackendReadiness(readiness: BackendReadiness): OperationFailureUiState = when (readiness) {
        BackendReadiness.RootPermissionRequired -> OperationFailureUiState(
            kind = OperationFailureKind.PermissionDenied,
            code = "BKX_ROOT_PERMISSION_REQUIRED",
            title = readiness.title,
            message = readiness.message,
            actions = readiness.remediationSteps,
            retryAllowed = true,
        )
        is BackendReadiness.UnsupportedProtocol,
        is BackendReadiness.ProbeFailed,
        BackendReadiness.Checking -> OperationFailureUiState(
            kind = OperationFailureKind.BackendNotReady,
            code = readiness.code.uppercase(),
            title = readiness.title,
            message = readiness.message,
            actions = readiness.remediationSteps.ifEmpty { listOf("Open engine diagnostics", "Re-check backend") },
            retryAllowed = true,
        )
        is BackendReadiness.Ready -> OperationFailureUiState(
            kind = OperationFailureKind.Unknown,
            code = "BKX_CANCELLATION_NOT_ACCEPTED",
            title = "Cancellation was not accepted",
            message = "BKX engine was ready but did not accept the cancellation request.",
            actions = listOf("Poll status", "Open engine diagnostics"),
            retryAllowed = true,
        )
    }

    private fun fromCodeAndMessage(
        code: String,
        message: String,
        explicitAction: String?,
    ): OperationFailureUiState {
        val normalized = code.uppercase()
        val kind = when {
            normalized.contains("TIMEOUT") -> OperationFailureKind.BackendTimeout
            normalized.contains("QUOTA") -> OperationFailureKind.QuotaExceeded
            normalized.contains("PROCESS") || normalized.contains("EXIT") || normalized.contains("DIED") -> OperationFailureKind.ProcessDied
            normalized.contains("PERMISSION") || normalized.contains("ROOT_DENIED") || normalized.contains("ALL_FILES") -> OperationFailureKind.PermissionDenied
            normalized.contains("BACKEND_NOT_READY") || normalized.contains("ROOT_SERVICE") || normalized.contains("EMBEDDED") -> OperationFailureKind.BackendNotReady
            normalized.contains("MALFORMED") || normalized.contains("JSON") || normalized.contains("PROTOCOL") -> OperationFailureKind.MalformedResult
            normalized.contains("SAF") || normalized.contains("GRANT") -> OperationFailureKind.SafGrantLost
            normalized.contains("CANCEL") -> OperationFailureKind.Cancelled
            else -> OperationFailureKind.Unknown
        }
        val title = when (kind) {
            OperationFailureKind.BackendTimeout -> "Backend timed out"
            OperationFailureKind.QuotaExceeded -> "Background quota reached"
            OperationFailureKind.ProcessDied -> "Backend process ended"
            OperationFailureKind.PermissionDenied -> "Permission denied"
            OperationFailureKind.BackendNotReady -> "Backend not ready"
            OperationFailureKind.MalformedResult -> "Malformed BKX result"
            OperationFailureKind.SafGrantLost -> "File permission lost"
            OperationFailureKind.Cancelled -> "Request cancelled"
            OperationFailureKind.Interrupted -> "Operation interrupted"
            OperationFailureKind.Unknown -> "BKX operation failed"
        }
        val defaultActions = when (kind) {
            OperationFailureKind.BackendTimeout -> listOf("Poll status", "Cancel request")
            OperationFailureKind.QuotaExceeded -> listOf("Retry later", "Open engine diagnostics")
            OperationFailureKind.ProcessDied -> listOf("Poll status", "Retry operation")
            OperationFailureKind.PermissionDenied -> listOf("Grant the requested file/root permission", "Open engine diagnostics")
            OperationFailureKind.BackendNotReady -> listOf("Open engine diagnostics", "Re-check backend")
            OperationFailureKind.MalformedResult -> listOf("Open engine diagnostics", "Export diagnostics")
            OperationFailureKind.SafGrantLost -> listOf("Re-select files", "Open settings")
            OperationFailureKind.Cancelled -> listOf("Review partial artifacts", "Start a new request")
            OperationFailureKind.Interrupted -> listOf("Review partial artifacts", "Retry operation")
            OperationFailureKind.Unknown -> listOf("Open engine diagnostics", "Retry operation")
        }
        return OperationFailureUiState(
            kind = kind,
            code = code,
            title = title,
            message = message,
            actions = listOfNotNull(explicitAction) + defaultActions,
            retryAllowed = kind != OperationFailureKind.MalformedResult,
        )
    }
}

fun BridgeResultEnvelope.toOperationResultUiState(): OperationResultUiState = when (status) {
    BridgeResultStatus.Success -> OperationResultUiState.Success(summary.ifBlank { "BKX operation completed." })
    BridgeResultStatus.Cancelled -> OperationResultUiState.Cancelled(summary.ifBlank { "BKX operation was cancelled." })
    BridgeResultStatus.Failure -> OperationResultUiState.Failed(
        errors.firstOrNull()?.let(OperationFailureMapper::fromBridgeError)
            ?: OperationFailureUiState(
                kind = OperationFailureKind.Unknown,
                code = "BKX_OPERATION_FAILED",
                title = "BKX operation failed",
                message = summary.ifBlank { "BKX returned failure without a typed error." },
                actions = listOf("Open engine diagnostics", "Retry operation"),
            ),
    )
}

fun BkxBridgeResponseEnvelope.toOperationResultUiState(): OperationResultUiState = when {
    verification.status == BkxBridgeVerificationStatus.Failed -> OperationResultUiState.Failed(
        OperationFailureUiState(
            kind = OperationFailureKind.Unknown,
            code = "BKX_VERIFICATION_FAILED",
            title = "Verification failed",
            message = "BKX completed the operation but verification evidence failed.",
            actions = listOf("Review verification evidence", "Open run details"),
            retryAllowed = false,
        ),
    )
    ok -> OperationResultUiState.Success(operation.ifBlank { "BKX operation" } + " completed.")
    errors.any { it.code.contains("CANCEL", ignoreCase = true) } -> OperationResultUiState.Cancelled(
        errors.first().message,
    )
    else -> OperationResultUiState.Failed(
        errors.firstOrNull()?.let(OperationFailureMapper::fromBridgeResponseError)
            ?: OperationFailureUiState(
                kind = OperationFailureKind.Unknown,
                code = "BKX_OPERATION_FAILED",
                title = "BKX operation failed",
                message = "BKX returned ok=false without a typed error.",
                actions = listOf("Open engine diagnostics", "Retry operation"),
            ),
    )
}


fun OperationRuntimeState.bounded(
    maxOperations: Int = 64,
    maxEventsPerRequest: Int = 200,
): OperationRuntimeState {
    require(maxOperations > 0) { "maxOperations must be positive" }
    require(maxEventsPerRequest > 0) { "maxEventsPerRequest must be positive" }
    val keep = activeOperations.values
        .sortedWith(compareByDescending<ActiveOperation> { it.status == ActiveOperationStatus.Queued || it.status == ActiveOperationStatus.Running || it.status == ActiveOperationStatus.Cancelling }
            .thenByDescending { it.lastSeenEpochMillis })
        .take(maxOperations)
        .map { it.requestId }
        .toSet()
    return copy(
        activeOperations = activeOperations.filterKeys { it in keep },
        progressEvents = progressEvents
            .filterKeys { it in keep }
            .mapValues { (_key, events) -> events.takeLast(maxEventsPerRequest) },
        failureStates = failureStates.filterKeys { it in keep },
        focusedRequestId = focusedRequestId?.takeIf { it in keep },
    )
}
