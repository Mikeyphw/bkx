package com.mikeyphw.bkx.android.core.files

interface SafFileAccessPort {
    fun persistGrant(selection: SafGrantRequest): PersistedUriGrantRecord
    fun validateGrant(grant: PersistedUriGrantRecord): FileAccessIssue?
    fun planStaging(selection: UserFileSelection, stagingPath: String, evidence: StagingEvidence): StagedFileRecord
}

data class SafGrantRequest(
    val uri: String,
    val kind: UserSelectionKind,
    val read: Boolean,
    val write: Boolean,
    val persistedAtEpochMillis: Long,
) {
    init {
        require(uri.startsWith(CONTENT_URI_PREFIX)) { "Only content:// URIs can be persisted through SAF." }
        require(read || write) { "Persisting a grant requires read and/or write access." }
    }
}

data class StagingEvidence(
    val sizeBytes: Long?,
    val sha256: String?,
    val requiresHashing: Boolean = sha256 == null,
    val availableBytes: Long? = null,
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0L) { "sizeBytes must be non-negative when reported." }
        require(availableBytes == null || availableBytes >= 0L) { "availableBytes must be non-negative when reported." }
    }
}

object ContentUriPathPolicy {
    fun isContentUri(candidate: String): Boolean = candidate.trim().lowercase().startsWith(CONTENT_URI_PREFIX)

    fun requireNotContentUriPath(candidate: String) {
        if (isContentUri(candidate)) {
            throw FileAccessPolicyException(FileAccessIssue.ContentUriAsPathRejected(candidate))
        }
    }
}

class FileAccessPolicyException(
    val issue: FileAccessIssue,
) : IllegalArgumentException(issue.message)
