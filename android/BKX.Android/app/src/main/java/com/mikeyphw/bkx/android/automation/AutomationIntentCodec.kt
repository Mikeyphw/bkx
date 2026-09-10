package com.mikeyphw.bkx.android.automation

import android.content.Intent
import android.os.Bundle
import java.util.UUID

object AutomationIntentCodec {
    fun fromDirectIntent(intent: Intent): AutomationRequest? = AutomationAction.fromDirectAction(intent.action)?.let { action ->
        fromBundle(action, intent.extras ?: Bundle.EMPTY)
    }

    fun fromTaskerBundle(bundle: Bundle): AutomationRequest? = AutomationAction.fromWire(bundle.getString(BkxAutomationContract.EXTRA_ACTION))?.let { action ->
        fromBundle(action, bundle)
    }

    fun fromBundle(action: AutomationAction, bundle: Bundle): AutomationRequest = AutomationRequest(
        action = action,
        requestId = bundle.getString(BkxAutomationContract.EXTRA_REQUEST_ID).orEmpty().trim(),
        targetRequestId = bundle.getString(BkxAutomationContract.EXTRA_TARGET_REQUEST_ID).orEmpty().trim(),
        source = bundle.getString(BkxAutomationContract.EXTRA_SOURCE).orEmpty().trim(),
        destination = bundle.getString(BkxAutomationContract.EXTRA_DESTINATION).orEmpty().trim(),
        archive = bundle.getString(BkxAutomationContract.EXTRA_ARCHIVE).orEmpty().trim(),
        archiveName = bundle.getString(BkxAutomationContract.EXTRA_ARCHIVE_NAME).orEmpty().trim(),
        includePatterns = splitAutomationList(bundle.getString(BkxAutomationContract.EXTRA_INCLUDE)),
        excludePatterns = splitAutomationList(bundle.getString(BkxAutomationContract.EXTRA_EXCLUDE)),
        includeHidden = bundle.booleanCompat(BkxAutomationContract.EXTRA_INCLUDE_HIDDEN, true),
        compression = bundle.getString(BkxAutomationContract.EXTRA_COMPRESSION).orEmpty().ifBlank { "gzip" }.lowercase(),
        splitChunkSizeBytes = bundle.longCompat(BkxAutomationContract.EXTRA_SPLIT_BYTES)?.takeIf { it > 0L },
        verifyAfter = bundle.booleanCompat(BkxAutomationContract.EXTRA_VERIFY_AFTER, true),
        restoreSubset = splitAutomationList(bundle.getString(BkxAutomationContract.EXTRA_RESTORE_SUBSET)),
        restoreMode = bundle.getString(BkxAutomationContract.EXTRA_RESTORE_MODE).orEmpty().ifBlank { "fresh_directory" }.lowercase(),
        restoreConfirmation = bundle.getString(BkxAutomationContract.EXTRA_RESTORE_CONFIRMATION).orEmpty().trim(),
        transferMode = bundle.getString(BkxAutomationContract.EXTRA_TRANSFER_MODE).orEmpty().ifBlank { "push" }.lowercase(),
        overwrite = bundle.booleanCompat(BkxAutomationContract.EXTRA_OVERWRITE, false),
        deleteExtraneous = bundle.booleanCompat(BkxAutomationContract.EXTRA_DELETE_EXTRANEOUS, false),
        checksum = bundle.getString(BkxAutomationContract.EXTRA_CHECKSUM).orEmpty().trim(),
        roots = splitAutomationList(bundle.getString(BkxAutomationContract.EXTRA_ROOTS)),
        maxDepth = bundle.intCompat(BkxAutomationContract.EXTRA_MAX_DEPTH) ?: 4,
        limit = bundle.intCompat(BkxAutomationContract.EXTRA_LIMIT) ?: 500,
        pageToken = bundle.getString(BkxAutomationContract.EXTRA_PAGE_TOKEN).orEmpty().trim(),
        pageSize = bundle.intCompat(BkxAutomationContract.EXTRA_PAGE_SIZE) ?: 100,
        jobId = bundle.getString(BkxAutomationContract.EXTRA_JOB_ID).orEmpty().trim(),
        jobOverrides = splitAutomationList(bundle.getString(BkxAutomationContract.EXTRA_JOB_OVERRIDES)),
        repositoryId = bundle.getString(BkxAutomationContract.EXTRA_REPOSITORY_ID).orEmpty().trim(),
        snapshotId = bundle.getString(BkxAutomationContract.EXTRA_SNAPSHOT_ID).orEmpty().trim(),
        dryRun = bundle.booleanCompat(BkxAutomationContract.EXTRA_DRY_RUN, true),
        confirmDestructive = bundle.booleanCompat(BkxAutomationContract.EXTRA_CONFIRM_DESTRUCTIVE, false),
        snapshotOverwrite = bundle.getString(BkxAutomationContract.EXTRA_SNAPSHOT_OVERWRITE).orEmpty().ifBlank { "never" }.lowercase(),
    )

    fun normalizeRequestId(request: AutomationRequest): AutomationRequest = if (request.requestId.isBlank()) {
        request.copy(requestId = "automation-${UUID.randomUUID()}")
    } else request

    fun toPluginBundle(request: AutomationRequest, accessToken: String, waitForResult: Boolean): Bundle = Bundle().apply {
        putString(BkxAutomationContract.EXTRA_ACCESS_TOKEN, accessToken)
        putString(BkxAutomationContract.EXTRA_ACTION, request.action.wireValue)
        putString(BkxAutomationContract.EXTRA_REQUEST_ID, request.requestId)
        putString(BkxAutomationContract.EXTRA_TARGET_REQUEST_ID, request.targetRequestId)
        putString(BkxAutomationContract.EXTRA_SOURCE, request.source)
        putString(BkxAutomationContract.EXTRA_DESTINATION, request.destination)
        putString(BkxAutomationContract.EXTRA_ARCHIVE, request.archive)
        putString(BkxAutomationContract.EXTRA_ARCHIVE_NAME, request.archiveName)
        putString(BkxAutomationContract.EXTRA_INCLUDE, request.includePatterns.joinToString("\n"))
        putString(BkxAutomationContract.EXTRA_EXCLUDE, request.excludePatterns.joinToString("\n"))
        putBoolean(BkxAutomationContract.EXTRA_INCLUDE_HIDDEN, request.includeHidden)
        putString(BkxAutomationContract.EXTRA_COMPRESSION, request.compression)
        request.splitChunkSizeBytes?.let { putLong(BkxAutomationContract.EXTRA_SPLIT_BYTES, it) }
        putBoolean(BkxAutomationContract.EXTRA_VERIFY_AFTER, request.verifyAfter)
        putString(BkxAutomationContract.EXTRA_RESTORE_SUBSET, request.restoreSubset.joinToString("\n"))
        putString(BkxAutomationContract.EXTRA_RESTORE_MODE, request.restoreMode)
        putString(BkxAutomationContract.EXTRA_RESTORE_CONFIRMATION, request.restoreConfirmation)
        putString(BkxAutomationContract.EXTRA_TRANSFER_MODE, request.transferMode)
        putBoolean(BkxAutomationContract.EXTRA_OVERWRITE, request.overwrite)
        putBoolean(BkxAutomationContract.EXTRA_DELETE_EXTRANEOUS, request.deleteExtraneous)
        putString(BkxAutomationContract.EXTRA_CHECKSUM, request.checksum)
        putString(BkxAutomationContract.EXTRA_ROOTS, request.roots.joinToString("\n"))
        putInt(BkxAutomationContract.EXTRA_MAX_DEPTH, request.maxDepth)
        putInt(BkxAutomationContract.EXTRA_LIMIT, request.limit)
        putString(BkxAutomationContract.EXTRA_PAGE_TOKEN, request.pageToken)
        putInt(BkxAutomationContract.EXTRA_PAGE_SIZE, request.pageSize)
        putString(BkxAutomationContract.EXTRA_JOB_ID, request.jobId)
        putString(BkxAutomationContract.EXTRA_JOB_OVERRIDES, request.jobOverrides.joinToString("\n"))
        putString(BkxAutomationContract.EXTRA_REPOSITORY_ID, request.repositoryId)
        putString(BkxAutomationContract.EXTRA_SNAPSHOT_ID, request.snapshotId)
        putBoolean(BkxAutomationContract.EXTRA_DRY_RUN, request.dryRun)
        putBoolean(BkxAutomationContract.EXTRA_CONFIRM_DESTRUCTIVE, request.confirmDestructive)
        putString(BkxAutomationContract.EXTRA_SNAPSHOT_OVERWRITE, request.snapshotOverwrite)
        putBoolean(BkxAutomationContract.EXTRA_WAIT_FOR_RESULT, waitForResult)
        putString(
            TaskerPluginContract.EXTRA_VARIABLE_REPLACE_KEYS,
            listOf(
                BkxAutomationContract.EXTRA_REQUEST_ID,
                BkxAutomationContract.EXTRA_TARGET_REQUEST_ID,
                BkxAutomationContract.EXTRA_SOURCE,
                BkxAutomationContract.EXTRA_DESTINATION,
                BkxAutomationContract.EXTRA_ARCHIVE,
                BkxAutomationContract.EXTRA_ARCHIVE_NAME,
                BkxAutomationContract.EXTRA_INCLUDE,
                BkxAutomationContract.EXTRA_EXCLUDE,
                BkxAutomationContract.EXTRA_COMPRESSION,
                BkxAutomationContract.EXTRA_RESTORE_SUBSET,
                BkxAutomationContract.EXTRA_RESTORE_MODE,
                BkxAutomationContract.EXTRA_RESTORE_CONFIRMATION,
                BkxAutomationContract.EXTRA_TRANSFER_MODE,
                BkxAutomationContract.EXTRA_CHECKSUM,
                BkxAutomationContract.EXTRA_ROOTS,
                BkxAutomationContract.EXTRA_PAGE_TOKEN,
                BkxAutomationContract.EXTRA_JOB_ID,
                BkxAutomationContract.EXTRA_JOB_OVERRIDES,
                BkxAutomationContract.EXTRA_REPOSITORY_ID,
                BkxAutomationContract.EXTRA_SNAPSHOT_ID,
                BkxAutomationContract.EXTRA_SNAPSHOT_OVERWRITE,
            ).joinToString(" "),
        )
    }
}

@Suppress("DEPRECATION")
private fun Bundle.valueCompat(key: String): Any? = get(key)

private fun Bundle.booleanCompat(key: String, default: Boolean): Boolean = when (val value = valueCompat(key)) {
    is Boolean -> value
    is String -> when (value.trim().lowercase()) { "1", "true", "yes", "on" -> true; "0", "false", "no", "off" -> false; else -> default }
    is Number -> value.toInt() != 0
    else -> default
}
private fun Bundle.longCompat(key: String): Long? = when (val value = valueCompat(key)) { is Number -> value.toLong(); is String -> value.trim().toLongOrNull(); else -> null }
private fun Bundle.intCompat(key: String): Int? = when (val value = valueCompat(key)) { is Number -> value.toInt(); is String -> value.trim().toIntOrNull(); else -> null }
