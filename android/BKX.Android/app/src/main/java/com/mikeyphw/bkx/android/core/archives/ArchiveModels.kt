package com.mikeyphw.bkx.android.core.archives

import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.files.ContentUriPathPolicy

private const val DEFAULT_PAGE_SIZE = 100
private const val MAX_PAGE_SIZE = 500

data class ArchiveLocation(
    val archiveId: String,
    val displayName: String,
    val stagedPath: String,
) {
    init {
        require(archiveId.isNotBlank()) { "Archive id must not be blank." }
        require(displayName.isNotBlank()) { "Archive display name must not be blank." }
        require(stagedPath.isNotBlank()) { "Archive staged path must not be blank." }
        ContentUriPathPolicy.requireNotContentUriPath(stagedPath)
        require(stagedPath.startsWith("/") || stagedPath.startsWith("rclone://", ignoreCase = true)) {
            "Archive staged path must be an absolute filesystem path or a credential-free rclone:// location."
        }
    }
}

enum class ArchiveReadOnlyOperation(val operation: String) {
    List("archive.list"),
    Info("archive.info"),
    Browse("archive.browse"),
    Verify("archive.verify"),
}

data class ArchiveBrowsePageRequest(
    val archive: ArchiveLocation,
    val pageToken: String? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Archive browse page size must be between 1 and $MAX_PAGE_SIZE." }
        require(pageToken == null || pageToken.isNotBlank()) { "Archive browse page token must not be blank." }
        require(pageToken == null || pageToken.all { it.isDigit() }) {
            "Archive browse page token must be the numeric next-page offset returned by BKX."
        }
    }
}

data class ArchiveEntrySummary(
    val path: String,
    val type: ArchiveEntryType,
    val sizeBytes: Long? = null,
    val verified: Boolean? = null,
) {
    init {
        require(path.isNotBlank()) { "Archive entry path must not be blank." }
        require(!path.startsWith('/')) { "Archive entry path must be archive-relative." }
    }
}

enum class ArchiveEntryType { File, Directory, Symlink, Special }

data class ArchiveListItem(
    val archive: ArchiveLocation,
    val sizeBytes: Long? = null,
    val createdAtEpochMillis: Long? = null,
    val verification: ArchiveVerificationUiState = ArchiveVerificationUiState.NotStarted,
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0L) { "Archive list item size must not be negative." }
        require(createdAtEpochMillis == null || createdAtEpochMillis >= 0L) { "Archive creation timestamp must not be negative." }
    }
}

data class ArchiveInfoSummary(
    val archive: ArchiveLocation,
    val entryCount: Long? = null,
    val compressedSizeBytes: Long? = null,
    val uncompressedSizeBytes: Long? = null,
    val formatVersion: String? = null,
    val manifestHash: String? = null,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(entryCount == null || entryCount >= 0L) { "Archive entry count must not be negative." }
        require(compressedSizeBytes == null || compressedSizeBytes >= 0L) { "Archive compressed size must not be negative." }
        require(uncompressedSizeBytes == null || uncompressedSizeBytes >= 0L) { "Archive uncompressed size must not be negative." }
        require(formatVersion == null || formatVersion.isNotBlank()) { "Archive format version must not be blank." }
        require(manifestHash == null || manifestHash.isNotBlank()) { "Archive manifest hash must not be blank." }
        require(warnings.none { it.isBlank() }) { "Archive info warnings must not be blank." }
    }
}

data class ArchiveBrowsePage(
    val request: ArchiveBrowsePageRequest,
    val entries: List<ArchiveEntrySummary>,
    val nextPageToken: String? = null,
    val totalEntriesEstimate: Long? = null,
) {
    val isLargeArchive: Boolean = nextPageToken != null || (totalEntriesEstimate ?: 0L) > entries.size
}

sealed interface ArchiveWorkflowUiState<out T> {
    data object Idle : ArchiveWorkflowUiState<Nothing>
    data object Loading : ArchiveWorkflowUiState<Nothing>
    data class Content<T>(val value: T) : ArchiveWorkflowUiState<T>
    data class Failed(val errors: List<BridgeError>, val summary: String) : ArchiveWorkflowUiState<Nothing>
}

sealed interface ArchiveVerificationUiState {
    data object NotStarted : ArchiveVerificationUiState
    data class Passed(val summary: String) : ArchiveVerificationUiState
    data class Failed(val errors: List<BridgeError>, val summary: String) : ArchiveVerificationUiState
}

data class ArchiveReadOnlyUiState(
    val selectedArchive: ArchiveLocation? = null,
    val availableWorkflows: Set<ArchiveReadOnlyOperation> = ArchiveReadOnlyOperation.entries.toSet(),
    val listState: ArchiveWorkflowUiState<List<ArchiveListItem>> = ArchiveWorkflowUiState.Idle,
    val infoState: ArchiveWorkflowUiState<ArchiveInfoSummary> = ArchiveWorkflowUiState.Idle,
    val browsePage: ArchiveBrowsePage? = null,
    val verification: ArchiveVerificationUiState = ArchiveVerificationUiState.NotStarted,
) {
    val hasMoreBrowsePages: Boolean = browsePage?.nextPageToken != null
    val isLargeArchive: Boolean = browsePage?.isLargeArchive == true
}

fun BridgeResultEnvelope.toArchiveVerificationUiState(): ArchiveVerificationUiState = when (status) {
    BridgeResultStatus.Success -> ArchiveVerificationUiState.Passed(summary.ifBlank { "Archive verification passed." })
    BridgeResultStatus.Failure -> ArchiveVerificationUiState.Failed(errors = errors, summary = summary.ifBlank { "Archive verification failed." })
    BridgeResultStatus.Cancelled -> ArchiveVerificationUiState.Failed(
        errors = errors.ifEmpty { listOf(BridgeError("verification_cancelled", "Archive verification was cancelled.")) },
        summary = summary.ifBlank { "Archive verification did not complete." },
    )
}
