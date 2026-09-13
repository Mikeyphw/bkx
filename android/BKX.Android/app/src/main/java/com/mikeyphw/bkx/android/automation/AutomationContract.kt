package com.mikeyphw.bkx.android.automation

import android.os.Bundle
import kotlinx.serialization.Serializable

object BkxAutomationContract {
    const val ACTION_PREFIX = "com.mikeyphw.bkx.android.automation."
    const val ACTION_BACKUP = ACTION_PREFIX + "BACKUP"
    const val ACTION_RESTORE = ACTION_PREFIX + "RESTORE"
    const val ACTION_VERIFY = ACTION_PREFIX + "VERIFY"
    const val ACTION_ARCHIVE_LIST = ACTION_PREFIX + "ARCHIVE_LIST"
    const val ACTION_ARCHIVE_INFO = ACTION_PREFIX + "ARCHIVE_INFO"
    const val ACTION_ARCHIVE_BROWSE = ACTION_PREFIX + "ARCHIVE_BROWSE"
    const val ACTION_TRANSFER = ACTION_PREFIX + "TRANSFER"
    const val ACTION_CANCEL = ACTION_PREFIX + "CANCEL"
    const val ACTION_STATUS = ACTION_PREFIX + "STATUS"
    const val ACTION_RUN_SAVED_JOB = ACTION_PREFIX + "RUN_SAVED_JOB"
    const val ACTION_CREATE_SNAPSHOT = ACTION_PREFIX + "CREATE_SNAPSHOT"
    const val ACTION_REPOSITORY_CHECK = ACTION_PREFIX + "REPOSITORY_CHECK"
    const val ACTION_RETENTION_APPLY = ACTION_PREFIX + "RETENTION_APPLY"
    const val ACTION_REPOSITORY_PRUNE = ACTION_PREFIX + "REPOSITORY_PRUNE"
    const val ACTION_SNAPSHOT_RESTORE = ACTION_PREFIX + "SNAPSHOT_RESTORE"

    const val EXTRA_ACCESS_TOKEN = ACTION_PREFIX + "extra.ACCESS_TOKEN"
    const val EXTRA_ACTION = ACTION_PREFIX + "extra.ACTION"
    const val EXTRA_REQUEST_ID = ACTION_PREFIX + "extra.REQUEST_ID"
    const val EXTRA_TARGET_REQUEST_ID = ACTION_PREFIX + "extra.TARGET_REQUEST_ID"
    const val EXTRA_SOURCE = ACTION_PREFIX + "extra.SOURCE"
    const val EXTRA_DESTINATION = ACTION_PREFIX + "extra.DESTINATION"
    const val EXTRA_ARCHIVE = ACTION_PREFIX + "extra.ARCHIVE"
    const val EXTRA_ARCHIVE_NAME = ACTION_PREFIX + "extra.ARCHIVE_NAME"
    const val EXTRA_INCLUDE = ACTION_PREFIX + "extra.INCLUDE"
    const val EXTRA_EXCLUDE = ACTION_PREFIX + "extra.EXCLUDE"
    const val EXTRA_INCLUDE_HIDDEN = ACTION_PREFIX + "extra.INCLUDE_HIDDEN"
    const val EXTRA_COMPRESSION = ACTION_PREFIX + "extra.COMPRESSION"
    const val EXTRA_SPLIT_BYTES = ACTION_PREFIX + "extra.SPLIT_BYTES"
    const val EXTRA_VERIFY_AFTER = ACTION_PREFIX + "extra.VERIFY_AFTER"
    const val EXTRA_RESTORE_SUBSET = ACTION_PREFIX + "extra.RESTORE_SUBSET"
    const val EXTRA_RESTORE_MODE = ACTION_PREFIX + "extra.RESTORE_MODE"
    const val EXTRA_RESTORE_CONFIRMATION = ACTION_PREFIX + "extra.RESTORE_CONFIRMATION"
    const val EXTRA_TRANSFER_MODE = ACTION_PREFIX + "extra.TRANSFER_MODE"
    const val EXTRA_OVERWRITE = ACTION_PREFIX + "extra.OVERWRITE"
    const val EXTRA_DELETE_EXTRANEOUS = ACTION_PREFIX + "extra.DELETE_EXTRANEOUS"
    const val EXTRA_CHECKSUM = ACTION_PREFIX + "extra.CHECKSUM"
    const val EXTRA_ROOTS = ACTION_PREFIX + "extra.ROOTS"
    const val EXTRA_MAX_DEPTH = ACTION_PREFIX + "extra.MAX_DEPTH"
    const val EXTRA_LIMIT = ACTION_PREFIX + "extra.LIMIT"
    const val EXTRA_PAGE_TOKEN = ACTION_PREFIX + "extra.PAGE_TOKEN"
    const val EXTRA_PAGE_SIZE = ACTION_PREFIX + "extra.PAGE_SIZE"
    const val EXTRA_WAIT_FOR_RESULT = ACTION_PREFIX + "extra.WAIT_FOR_RESULT"
    const val EXTRA_CALLBACK_PACKAGE = ACTION_PREFIX + "extra.CALLBACK_PACKAGE"
    const val EXTRA_CALLBACK_ACTION = ACTION_PREFIX + "extra.CALLBACK_ACTION"
    const val EXTRA_RESULT_JSON = ACTION_PREFIX + "extra.RESULT_JSON"
    const val EXTRA_DISPATCH_ID = ACTION_PREFIX + "extra.DISPATCH_ID"
    const val EXTRA_JOB_ID = ACTION_PREFIX + "extra.JOB_ID"
    const val EXTRA_JOB_OVERRIDES = ACTION_PREFIX + "extra.JOB_OVERRIDES"
    const val EXTRA_REPOSITORY_ID = ACTION_PREFIX + "extra.REPOSITORY_ID"
    const val EXTRA_SNAPSHOT_ID = ACTION_PREFIX + "extra.SNAPSHOT_ID"
    const val EXTRA_DRY_RUN = ACTION_PREFIX + "extra.DRY_RUN"
    const val EXTRA_CONFIRM_DESTRUCTIVE = ACTION_PREFIX + "extra.CONFIRM_DESTRUCTIVE"
    const val EXTRA_SNAPSHOT_OVERWRITE = ACTION_PREFIX + "extra.SNAPSHOT_OVERWRITE"
    const val EXTRA_SCHEDULE_ID = ACTION_PREFIX + "extra.SCHEDULE_ID"
    const val EXTRA_SCHEDULE_SIGNATURE = ACTION_PREFIX + "extra.SCHEDULE_SIGNATURE"
    const val EXTRA_EXPECTED_JOB_REVISION = ACTION_PREFIX + "extra.EXPECTED_JOB_REVISION"

    val directActions = setOf(
        ACTION_BACKUP,
        ACTION_RESTORE,
        ACTION_VERIFY,
        ACTION_ARCHIVE_LIST,
        ACTION_ARCHIVE_INFO,
        ACTION_ARCHIVE_BROWSE,
        ACTION_TRANSFER,
        ACTION_CANCEL,
        ACTION_STATUS,
        ACTION_RUN_SAVED_JOB,
        ACTION_CREATE_SNAPSHOT,
        ACTION_REPOSITORY_CHECK,
        ACTION_RETENTION_APPLY,
        ACTION_REPOSITORY_PRUNE,
        ACTION_SNAPSHOT_RESTORE,
    )
}

object LocalePluginContract {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
}

object TaskerPluginContract {
    const val EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES"
    const val EXTRA_HOST_CAPABILITIES = "net.dinglisch.android.tasker.extras.HOST_CAPABILITIES"
    const val EXTRA_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"
    const val EXTRA_VARIABLE_REPLACE_KEYS = "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS"
    const val EXTRA_REQUESTED_TIMEOUT = "net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT"
    const val EXTRA_COMPLETION_INTENT = "net.dinglisch.android.tasker.extras.COMPLETION_INTENT"
    const val EXTRA_RESULT_CODE = "net.dinglisch.android.tasker.extras.RESULT_CODE"

    const val RESULT_CODE_OK = android.app.Activity.RESULT_OK
    const val RESULT_CODE_FAILED = android.app.Activity.RESULT_FIRST_USER + 1
    const val RESULT_CODE_PENDING = android.app.Activity.RESULT_FIRST_USER + 2

    const val CAPABILITY_RETURN_VARIABLES = 2
    const val CAPABILITY_VARIABLE_REPLACEMENT = 8
    const val CAPABILITY_RELEVANT_VARIABLES = 16
    const val CAPABILITY_SYNCHRONOUS_EXECUTION = 32

    const val REQUEST_TIMEOUT_MILLIS = 3_600_000L
}

@Serializable
enum class AutomationAction(val wireValue: String, val label: String) {
    Backup("backup", "Backup"),
    Restore("restore", "Restore"),
    Verify("verify", "Verify archive"),
    ArchiveList("archive_list", "List archives"),
    ArchiveInfo("archive_info", "Archive info"),
    ArchiveBrowse("archive_browse", "Browse archive"),
    Transfer("transfer", "Transfer"),
    RunSavedJob("run_saved_job", "Run saved job"),
    CreateSnapshot("create_snapshot", "Create snapshot"),
    RepositoryCheck("repository_check", "Check repository"),
    RetentionApply("retention_apply", "Apply retention"),
    RepositoryPrune("repository_prune", "Prune repository"),
    SnapshotRestore("snapshot_restore", "Restore snapshot"),
    Cancel("cancel", "Cancel run"),
    Status("status", "Operation status");

    companion object {
        fun fromWire(value: String?): AutomationAction? = entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
        fun fromDirectAction(action: String?): AutomationAction? = when (action) {
            BkxAutomationContract.ACTION_BACKUP -> Backup
            BkxAutomationContract.ACTION_RESTORE -> Restore
            BkxAutomationContract.ACTION_VERIFY -> Verify
            BkxAutomationContract.ACTION_ARCHIVE_LIST -> ArchiveList
            BkxAutomationContract.ACTION_ARCHIVE_INFO -> ArchiveInfo
            BkxAutomationContract.ACTION_ARCHIVE_BROWSE -> ArchiveBrowse
            BkxAutomationContract.ACTION_TRANSFER -> Transfer
            BkxAutomationContract.ACTION_CANCEL -> Cancel
            BkxAutomationContract.ACTION_STATUS -> Status
            BkxAutomationContract.ACTION_RUN_SAVED_JOB -> RunSavedJob
            BkxAutomationContract.ACTION_CREATE_SNAPSHOT -> CreateSnapshot
            BkxAutomationContract.ACTION_REPOSITORY_CHECK -> RepositoryCheck
            BkxAutomationContract.ACTION_RETENTION_APPLY -> RetentionApply
            BkxAutomationContract.ACTION_REPOSITORY_PRUNE -> RepositoryPrune
            BkxAutomationContract.ACTION_SNAPSHOT_RESTORE -> SnapshotRestore
            else -> null
        }
    }
}

@Serializable
data class AutomationRequest(
    val action: AutomationAction,
    val requestId: String = "",
    val targetRequestId: String = "",
    val source: String = "",
    val destination: String = "",
    val archive: String = "",
    val archiveName: String = "",
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val includeHidden: Boolean = true,
    val compression: String = "gzip",
    val splitChunkSizeBytes: Long? = null,
    val verifyAfter: Boolean = true,
    val restoreSubset: List<String> = emptyList(),
    val restoreMode: String = "fresh_directory",
    val restoreConfirmation: String = "",
    val transferMode: String = "push",
    val overwrite: Boolean = false,
    val deleteExtraneous: Boolean = false,
    val checksum: String = "",
    val roots: List<String> = emptyList(),
    val maxDepth: Int = 4,
    val limit: Int = 500,
    val pageToken: String = "",
    val pageSize: Int = 100,
    val jobId: String = "",
    val jobOverrides: List<String> = emptyList(),
    val scheduleId: String = "",
    val scheduleSignature: String = "",
    val expectedJobRevision: Long = 0L,
    val repositoryId: String = "",
    val snapshotId: String = "",
    val dryRun: Boolean = true,
    val confirmDestructive: Boolean = false,
    val snapshotOverwrite: String = "never",
) {
    fun validate(): List<String> = buildList {
        fun validLocation(value: String): Boolean = value.startsWith("/") || Regex("^rclone://[A-Za-z0-9._-]+(?:/[^?#\\u0000]*)?$", RegexOption.IGNORE_CASE).matches(value)
        fun stableId(value: String): Boolean = Regex("^[a-z0-9][a-z0-9._-]{0,127}$").matches(value.trim())
        when (action) {
            AutomationAction.Backup -> {
                if (!source.startsWith("/")) add("Backup source must be an absolute local path.")
                if (!validLocation(destination)) add("Backup destination must be an absolute local path or credential-free rclone:// location.")
                if (compression.lowercase() !in setOf("none", "gzip", "zstd")) add("Compression must be none, gzip, or zstd.")
                if (splitChunkSizeBytes != null && splitChunkSizeBytes < 1024L * 1024L) add("Split size must be at least 1 MiB.")
            }
            AutomationAction.Restore -> {
                if (!validLocation(archive)) add("Restore archive must be an absolute local path or credential-free rclone:// location.")
                if (!destination.startsWith('/')) add("Restore destination must be an absolute local path.")
                if (restoreMode !in setOf("fresh_directory", "existing_directory")) add("Restore mode must be fresh_directory or existing_directory.")
            }
            AutomationAction.Verify, AutomationAction.ArchiveInfo, AutomationAction.ArchiveBrowse -> if (!validLocation(archive)) add("Archive must be an absolute local path or credential-free rclone:// location.")
            AutomationAction.ArchiveList -> {
                if (roots.any { !validLocation(it) }) add("Archive roots must be absolute local paths or credential-free rclone:// locations.")
                if (maxDepth !in 0..8) add("Archive max depth must be 0..8.")
                if (limit !in 1..2000) add("Archive list limit must be 1..2000.")
            }
            AutomationAction.Transfer -> {
                val transferSources = splitAutomationList(source)
                if (transferSources.isEmpty() || transferSources.any { !validLocation(it) }) add("Transfer sources must be absolute local paths or credential-free rclone:// locations.")
                if (!validLocation(destination)) add("Transfer destination must be an absolute local path or credential-free rclone:// location.")
                if (transferMode !in setOf("push", "pull", "sync")) add("Transfer mode must be push, pull, or sync.")
                if (transferMode == "sync" && !deleteExtraneous) add("Sync requires explicit delete_extraneous approval.")
            }
            AutomationAction.RunSavedJob -> {
                if (!stableId(jobId)) add("Saved job id is required and must use BKX stable-id syntax.")
                if (scheduleId.isNotBlank() && !stableId(scheduleId)) add("Schedule id must use BKX stable-id syntax.")
                if (expectedJobRevision < 0L) add("Expected job revision must not be negative.")
                if (repositoryId.isNotBlank() && !stableId(repositoryId)) add("Saved-job repository override must use BKX stable-id syntax.")
                jobOverrides.forEach { value ->
                    if (!value.contains('=') || value.substringBefore('=').trim().isBlank()) add("Saved-job overrides must use KEY=VALUE.")
                }
            }
            AutomationAction.CreateSnapshot -> {
                if (!stableId(repositoryId)) add("Repository id is required and must use BKX stable-id syntax.")
                val snapshotSources = splitAutomationList(source)
                if (snapshotSources.isEmpty() || snapshotSources.any { !it.startsWith("/") }) add("Snapshot sources must be absolute local paths.")
            }
            AutomationAction.RepositoryCheck -> if (!stableId(repositoryId)) add("Repository id is required and must use BKX stable-id syntax.")
            AutomationAction.RetentionApply, AutomationAction.RepositoryPrune -> {
                if (!stableId(repositoryId)) add("Repository id is required and must use BKX stable-id syntax.")
                if (!dryRun && !confirmDestructive) add("Real retention/prune changes require explicit destructive confirmation.")
            }
            AutomationAction.SnapshotRestore -> {
                if (!stableId(repositoryId)) add("Repository id is required and must use BKX stable-id syntax.")
                if (snapshotId.isBlank()) add("Snapshot id is required.")
                if (!destination.startsWith("/")) add("Snapshot restore destination must be an absolute local path.")
                if (snapshotOverwrite !in setOf("never", "if-changed", "if-newer", "always")) add("Snapshot overwrite mode must be never, if-changed, if-newer, or always.")
                if (!dryRun && !confirmDestructive) add("Real snapshot restore requires explicit confirmation.")
            }
            AutomationAction.Cancel, AutomationAction.Status -> if (targetRequestId.isBlank()) add("Target request id is required.")
        }
    }
}

@Serializable
data class AutomationResult(
    val requestId: String,
    val action: AutomationAction,
    val status: String,
    val operationRequestId: String = "",
    val summary: String = "",
    val errorCode: String = "",
    val errorMessage: String = "",
    val artifactPaths: List<String> = emptyList(),
    val artifactSha256: List<String> = emptyList(),
    val filesTotal: Long? = null,
    val bytesTotal: Long? = null,
    val jobId: String = "",
    val runId: String = "",
    val repositoryId: String = "",
    val snapshotId: String = "",
    val filesProcessed: Long? = null,
    val bytesProcessed: Long? = null,
    val bytesAdded: Long? = null,
    val bytesReused: Long? = null,
    val durationMillis: Long? = null,
) {
    val successful: Boolean get() = status == "success"

    fun taskerVariableValues(): Map<String, String> = linkedMapOf(
        "%bkx_request_id" to requestId,
        "%bkx_action" to action.wireValue,
        "%bkx_status" to status,
        "%bkx_success" to successful.toString(),
        "%bkx_operation_request_id" to operationRequestId,
        "%bkx_summary" to summary,
        "%bkx_message" to summary,
        "%bkx_error_code" to errorCode,
        "%bkx_error_message" to errorMessage,
        "%bkx_artifact_paths" to artifactPaths.joinToString("\n"),
        "%bkx_artifact_sha256" to artifactSha256.joinToString("\n"),
        "%bkx_files_total" to filesTotal?.toString().orEmpty(),
        "%bkx_bytes_total" to bytesTotal?.toString().orEmpty(),
        "%bkx_job_id" to jobId,
        "%bkx_run_id" to runId,
        "%bkx_repository_id" to repositoryId,
        "%bkx_snapshot_id" to snapshotId,
        "%bkx_files_processed" to filesProcessed?.toString().orEmpty(),
        "%bkx_bytes_processed" to bytesProcessed?.toString().orEmpty(),
        "%bkx_bytes_added" to bytesAdded?.toString().orEmpty(),
        "%bkx_bytes_reused" to bytesReused?.toString().orEmpty(),
        "%bkx_duration_ms" to durationMillis?.toString().orEmpty(),
    )

    fun taskerVariables(): Bundle = Bundle().apply {
        taskerVariableValues().forEach { (name, value) -> putString(name, value) }
    }

    companion object {
        val relevantVariables = arrayOf(
            "%bkx_request_id", "%bkx_action", "%bkx_status", "%bkx_success", "%bkx_operation_request_id", "%bkx_summary", "%bkx_message",
            "%bkx_error_code", "%bkx_error_message", "%bkx_artifact_paths", "%bkx_artifact_sha256",
            "%bkx_files_total", "%bkx_bytes_total", "%bkx_job_id", "%bkx_run_id",
            "%bkx_repository_id", "%bkx_snapshot_id",
            "%bkx_files_processed", "%bkx_bytes_processed", "%bkx_bytes_added",
            "%bkx_bytes_reused", "%bkx_duration_ms",
        )
    }
}

internal fun splitAutomationList(value: String?): List<String> = value.orEmpty()
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .toList()
