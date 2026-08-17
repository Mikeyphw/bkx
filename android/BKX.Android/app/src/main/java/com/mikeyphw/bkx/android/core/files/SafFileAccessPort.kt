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
)

object ContentUriPathPolicy {
    fun requireNotContentUriPath(candidate: String) {
        if (candidate.startsWith(CONTENT_URI_PREFIX)) {
            throw FileAccessPolicyException(FileAccessIssue.ContentUriAsPathRejected(candidate))
        }
    }
}

class FileAccessPolicyException(
    val issue: FileAccessIssue,
) : IllegalArgumentException(issue.message)
