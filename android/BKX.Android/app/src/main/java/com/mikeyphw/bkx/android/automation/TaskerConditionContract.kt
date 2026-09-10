package com.mikeyphw.bkx.android.automation

import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BkxTaskerEventType(val id: String, val label: String) {
    Any("any", "Any BKX event"),
    OperationStarted("operation_started", "Operation started"),
    ProgressMilestone("progress_milestone", "Progress milestone"),
    OperationCompleted("operation_completed", "Operation completed"),
    BackupCompleted("backup_completed", "Backup completed"),
    RestoreCompleted("restore_completed", "Restore completed"),
    VerifyCompleted("verify_completed", "Verify completed"),
    TransferCompleted("transfer_completed", "Transfer completed"),
    ArchiveCreated("archive_created", "Archive created"),
    OperationFailed("operation_failed", "Operation failed"),
    OperationCancelled("operation_cancelled", "Operation cancelled"),
    JobStarted("job_started", "Saved job started"),
    JobCompleted("job_completed", "Saved job completed"),
    JobFailed("job_failed", "Saved job failed"),
    JobCancelled("job_cancelled", "Saved job cancelled"),
    SnapshotCreated("snapshot_created", "Snapshot created"),
    RepositoryCheckPassed("repository_check_passed", "Repository check passed"),
    RepositoryCheckFailed("repository_check_failed", "Repository check failed"),
    RepositoryNeedsAttention("repository_needs_attention", "Repository needs attention"),
    RetentionApplied("retention_applied", "Retention applied"),
    RepositoryPruned("repository_pruned", "Repository pruned"),
    GoogleDriveAuthorizationRequired("google_drive_authorization_required", "Google Drive authorization required"),
    RemoteUnavailable("remote_unavailable", "Remote unavailable"),
    RemoteRecovered("remote_recovered", "Remote recovered");

    companion object {
        fun fromId(value: String?): BkxTaskerEventType = entries.firstOrNull { it.id == value } ?: Any
    }
}

@Serializable
enum class BkxTaskerStateType(val id: String, val label: String) {
    AnyOperationRunning("any_operation_running", "Any operation running"),
    BackupRunning("backup_running", "Backup running"),
    RestoreRunning("restore_running", "Restore running"),
    VerifyRunning("verify_running", "Verify running"),
    TransferRunning("transfer_running", "Transfer running"),
    JobRunning("job_running", "Saved job running"),
    RepositoryHealthy("repository_healthy", "Repository healthy"),
    RepositoryNeedsAttention("repository_needs_attention", "Repository needs attention"),
    LastJobSucceeded("last_job_succeeded", "Last saved job succeeded"),
    LastJobFailed("last_job_failed", "Last saved job failed"),
    LastJobCancelled("last_job_cancelled", "Last saved job cancelled"),
    GoogleDriveConnected("google_drive_connected", "Google Drive connected"),
    GoogleDriveNeedsAuthorization("google_drive_needs_authorization", "Google Drive needs authorization"),
    RemoteAvailable("remote_available", "Remote available"),
    LastOperationSucceeded("last_operation_succeeded", "Last operation succeeded"),
    LastOperationFailed("last_operation_failed", "Last operation failed"),
    LastOperationCancelled("last_operation_cancelled", "Last operation cancelled");

    companion object {
        fun fromId(value: String?): BkxTaskerStateType = entries.firstOrNull { it.id == value } ?: AnyOperationRunning
    }
}

@Serializable
enum class BkxTaskerDriveState {
    @SerialName("unknown") Unknown,
    @SerialName("connecting") Connecting,
    @SerialName("connected") Connected,
    @SerialName("needs_authorization") NeedsAuthorization,
    @SerialName("error") Error,
    @SerialName("disconnected") Disconnected,
}

@TaskerInputRoot
class BkxTaskerEventInput @JvmOverloads constructor(
    @field:TaskerInputField("event_type", labelResIdName = "tasker_event_type") var eventType: String = BkxTaskerEventType.Any.id,
    @field:TaskerInputField("operation", labelResIdName = "tasker_filter_operation") var operation: String = "",
    @field:TaskerInputField("remote_filter", labelResIdName = "tasker_filter_remote") var remoteFilter: String = "any",
    @field:TaskerInputField("remote_name", labelResIdName = "tasker_filter_remote_name") var remoteName: String = "",
    @field:TaskerInputField("request_id_pattern", labelResIdName = "tasker_filter_request_id") var requestIdPattern: String = "",
    @field:TaskerInputField("error_code", labelResIdName = "tasker_filter_error_code") var errorCode: String = "",
    @field:TaskerInputField("job_id", labelResIdName = "tasker_filter_job_id") var jobId: String = "",
    @field:TaskerInputField("repository_id", labelResIdName = "tasker_filter_repository_id") var repositoryId: String = "",
    @field:TaskerInputField("snapshot_id", labelResIdName = "tasker_filter_snapshot_id") var snapshotId: String = "",
    @field:TaskerInputField("progress_cadence", labelResIdName = "tasker_progress_cadence") var progressCadence: String = "recommended",
)

@TaskerInputRoot
class BkxTaskerStateInput @JvmOverloads constructor(
    @field:TaskerInputField("state_type", labelResIdName = "tasker_state_type") var stateType: String = BkxTaskerStateType.AnyOperationRunning.id,
    @field:TaskerInputField("operation", labelResIdName = "tasker_filter_operation") var operation: String = "",
    @field:TaskerInputField("remote_filter", labelResIdName = "tasker_filter_remote") var remoteFilter: String = "any",
    @field:TaskerInputField("remote_name", labelResIdName = "tasker_filter_remote_name") var remoteName: String = "",
    @field:TaskerInputField("request_id_pattern", labelResIdName = "tasker_filter_request_id") var requestIdPattern: String = "",
    @field:TaskerInputField("error_code", labelResIdName = "tasker_filter_error_code") var errorCode: String = "",
    @field:TaskerInputField("job_id", labelResIdName = "tasker_filter_job_id") var jobId: String = "",
    @field:TaskerInputField("repository_id", labelResIdName = "tasker_filter_repository_id") var repositoryId: String = "",
    @field:TaskerInputField("snapshot_id", labelResIdName = "tasker_filter_snapshot_id") var snapshotId: String = "",
)

@TaskerInputRoot
class BkxTaskerEventUpdate @JvmOverloads constructor(
    @field:TaskerInputField("event") var event: String = "",
    @field:TaskerInputField("operation") var operation: String = "",
    @field:TaskerInputField("request_id") var requestId: String = "",
    @field:TaskerInputField("status") var status: String = "",
    @field:TaskerInputField("progress") var progress: String = "",
    @field:TaskerInputField("files_done") var filesDone: String = "",
    @field:TaskerInputField("files_total") var filesTotal: String = "",
    @field:TaskerInputField("bytes_done") var bytesDone: String = "",
    @field:TaskerInputField("bytes_total") var bytesTotal: String = "",
    @field:TaskerInputField("archive") var archive: String = "",
    @field:TaskerInputField("remote") var remote: String = "",
    @field:TaskerInputField("error_code") var errorCode: String = "",
    @field:TaskerInputField("error") var error: String = "",
    @field:TaskerInputField("phase") var phase: String = "",
    @field:TaskerInputField("timestamp") var timestamp: String = "",
    @field:TaskerInputField("job_id") var jobId: String = "",
    @field:TaskerInputField("run_id") var runId: String = "",
    @field:TaskerInputField("repository_id") var repositoryId: String = "",
    @field:TaskerInputField("snapshot_id") var snapshotId: String = "",
)

@TaskerOutputObject
class BkxTaskerConditionOutput @JvmOverloads constructor(
    @get:TaskerOutputVariable("bkx_event") var event: String? = null,
    @get:TaskerOutputVariable("bkx_state") var state: String? = null,
    @get:TaskerOutputVariable("bkx_operation") var operation: String? = null,
    @get:TaskerOutputVariable("bkx_request_id") var requestId: String? = null,
    @get:TaskerOutputVariable("bkx_status") var status: String? = null,
    @get:TaskerOutputVariable("bkx_progress") var progress: String? = null,
    @get:TaskerOutputVariable("bkx_files_done") var filesDone: String? = null,
    @get:TaskerOutputVariable("bkx_files_total") var filesTotal: String? = null,
    @get:TaskerOutputVariable("bkx_bytes_done") var bytesDone: String? = null,
    @get:TaskerOutputVariable("bkx_bytes_total") var bytesTotal: String? = null,
    @get:TaskerOutputVariable("bkx_archive") var archive: String? = null,
    @get:TaskerOutputVariable("bkx_remote") var remote: String? = null,
    @get:TaskerOutputVariable("bkx_error_code") var errorCode: String? = null,
    @get:TaskerOutputVariable("bkx_error") var error: String? = null,
    @get:TaskerOutputVariable("bkx_phase") var phase: String? = null,
    @get:TaskerOutputVariable("bkx_timestamp") var timestamp: String? = null,
    @get:TaskerOutputVariable("bkx_job_id") var jobId: String? = null,
    @get:TaskerOutputVariable("bkx_run_id") var runId: String? = null,
    @get:TaskerOutputVariable("bkx_repository_id") var repositoryId: String? = null,
    @get:TaskerOutputVariable("bkx_snapshot_id") var snapshotId: String? = null,
)

@Serializable
data class BkxTaskerSignal(
    val event: String,
    val operation: String = "",
    val requestId: String = "",
    val status: String = "",
    val progressPercent: Int? = null,
    val filesDone: Long? = null,
    val filesTotal: Long? = null,
    val bytesDone: Long? = null,
    val bytesTotal: Long? = null,
    val archive: String = "",
    val remotes: List<String> = emptyList(),
    val errorCode: String = "",
    val error: String = "",
    val phase: String = "",
    val timestampEpochMillis: Long,
    val terminal: Boolean = false,
    val jobId: String = "",
    val runId: String = "",
    val repositoryId: String = "",
    val snapshotId: String = "",
)

internal fun BkxTaskerSignal.toUpdate(): BkxTaskerEventUpdate = BkxTaskerEventUpdate(
    event = event,
    operation = operation,
    requestId = requestId,
    status = status,
    progress = progressPercent?.toString().orEmpty(),
    filesDone = filesDone?.toString().orEmpty(),
    filesTotal = filesTotal?.toString().orEmpty(),
    bytesDone = bytesDone?.toString().orEmpty(),
    bytesTotal = bytesTotal?.toString().orEmpty(),
    archive = archive,
    remote = remotes.joinToString(","),
    errorCode = errorCode,
    error = error,
    phase = phase,
    timestamp = timestampEpochMillis.toString(),
    jobId = jobId,
    runId = runId,
    repositoryId = repositoryId,
    snapshotId = snapshotId,
)

internal fun BkxTaskerEventUpdate.toSignal(): BkxTaskerSignal = BkxTaskerSignal(
    event = event,
    operation = operation,
    requestId = requestId,
    status = status,
    progressPercent = progress.toIntOrNull(),
    filesDone = filesDone.toLongOrNull(),
    filesTotal = filesTotal.toLongOrNull(),
    bytesDone = bytesDone.toLongOrNull(),
    bytesTotal = bytesTotal.toLongOrNull(),
    archive = archive,
    remotes = remote.split(',').map(String::trim).filter(String::isNotBlank),
    errorCode = errorCode,
    error = error,
    phase = phase,
    timestampEpochMillis = timestamp.toLongOrNull() ?: System.currentTimeMillis(),
    terminal = event in setOf(
        "operation_completed", "backup_completed", "restore_completed", "verify_completed", "transfer_completed",
        "operation_failed", "operation_cancelled", "job_completed", "job_failed", "job_cancelled",
        "snapshot_created", "repository_check_passed", "repository_check_failed", "retention_applied", "repository_pruned",
    ),
    jobId = jobId,
    runId = runId,
    repositoryId = repositoryId,
    snapshotId = snapshotId,
)

internal fun BkxTaskerSignal.toOutput(state: String? = null): BkxTaskerConditionOutput = BkxTaskerConditionOutput(
    event = event.ifBlank { null },
    state = state,
    operation = operation.ifBlank { null },
    requestId = requestId.ifBlank { null },
    status = status.ifBlank { null },
    progress = progressPercent?.toString(),
    filesDone = filesDone?.toString(), filesTotal = filesTotal?.toString(),
    bytesDone = bytesDone?.toString(), bytesTotal = bytesTotal?.toString(),
    archive = archive.ifBlank { null },
    remote = remotes.joinToString(",").ifBlank { null },
    errorCode = errorCode.ifBlank { null }, error = error.ifBlank { null },
    phase = phase.ifBlank { null }, timestamp = timestampEpochMillis.toString(),
    jobId = jobId.ifBlank { null }, runId = runId.ifBlank { null },
    repositoryId = repositoryId.ifBlank { null }, snapshotId = snapshotId.ifBlank { null },
)
