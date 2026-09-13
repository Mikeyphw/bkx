package com.mikeyphw.bkx.android.core.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UserSelectionKind {
    @SerialName("document") Document,
    @SerialName("tree") Tree,
}

@Serializable
enum class UriGrantState {
    @SerialName("active") Active,
    @SerialName("lost") Lost,
    @SerialName("not_persisted") NotPersisted,
}

@Serializable
data class PersistedUriGrantRecord(
    val uri: String,
    val kind: UserSelectionKind,
    val read: Boolean,
    val write: Boolean,
    val state: UriGrantState,
    val persistedAtEpochMillis: Long,
) {
    init {
        require(uri.startsWith(CONTENT_URI_PREFIX)) { "SAF grants must record content:// URIs only." }
        require(read || write) { "A persisted SAF grant must carry at least one permission bit." }
    }
}

@Serializable
data class UserFileSelection(
    val uri: String,
    val kind: UserSelectionKind,
    val displayName: String,
    val grant: PersistedUriGrantRecord,
    val selectedAtEpochMillis: Long,
) {
    init {
        require(uri.startsWith(CONTENT_URI_PREFIX)) { "User-visible file selections must stay as content:// URIs." }
        require(grant.uri == uri) { "Grant URI must match selected URI." }
    }
}

@Serializable
enum class StagingLifetime {
    @SerialName("per_request") PerRequest,
    @SerialName("until_user_clears") UntilUserClears,
}

@Serializable
data class StagingCost(
    val bytesToCopy: Long?,
    val requiresHashing: Boolean,
    val userVisibleMessage: String,
)

@Serializable
data class StagedFileRecord(
    val sourceUri: String,
    val stagedPath: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val lifetime: StagingLifetime,
    val cost: StagingCost,
    val grantState: UriGrantState,
    val leaseId: String? = null,
    val leaseExpiresAtEpochMillis: Long? = null,
) {
    init {
        require(sourceUri.startsWith(CONTENT_URI_PREFIX)) { "The original SAF source must remain a content:// URI." }
        require(!ContentUriPathPolicy.isContentUri(stagedPath)) { "A staged path must not be a content URI." }
        require(stagedPath.startsWith("/")) { "A staged path must be an explicit shared filesystem path." }
        require(sha256 == null || SHA256_REGEX.matches(sha256)) { "sha256 must be lowercase hex when present." }
        require(leaseId == null || leaseId.isNotBlank()) { "leaseId must be non-blank when present." }
        require(leaseExpiresAtEpochMillis == null || leaseExpiresAtEpochMillis > 0L) { "lease expiry must be positive when present." }
    }
}

@Serializable
sealed interface FileAccessIssue {
    val code: String
    val title: String
    val message: String
    val remediationSteps: List<String>

    @Serializable
    data class ContentUriAsPathRejected(val uri: String) : FileAccessIssue {
        override val code = "content_uri_as_path_rejected"
        override val title = "Content URI is not a path"
        override val message = "Android returned a content URI. BKX needs a staged/shared path, not this URI string."
        override val remediationSteps = listOf("Stage the selected content first, then pass only the staged path to BKX.")
    }

    @Serializable
    data class GrantLost(val uri: String) : FileAccessIssue {
        override val code = "saf_grant_lost"
        override val title = "File permission was lost"
        override val message = "The selected document or tree grant is no longer available."
        override val remediationSteps = listOf("Pick the file or folder again so Android grants access deliberately.")
    }

    @Serializable
    data class RestrictedLocation(val label: String, val reason: String) : FileAccessIssue {
        override val code = "restricted_location"
        override val title = "Restricted Android location"
        override val message = "$label is blocked: $reason"
        override val remediationSteps = listOf("Choose a user-visible document or tree through the Android file picker.")
    }

    @Serializable
    data class UnavailableLocation(val path: String, val reason: String) : FileAccessIssue {
        override val code = "unavailable_location"
        override val title = "Unavailable staging location"
        override val message = "$path is unavailable: $reason"
        override val remediationSteps = listOf("Select a different staging location in Settings.")
    }

    @Serializable
    data class InsufficientStagingCapacity(val requiredBytes: Long, val availableBytes: Long) : FileAccessIssue {
        override val code = "insufficient_staging_capacity"
        override val title = "Not enough staging space"
        override val message = "BKX needs $requiredBytes bytes available for provider staging, but Android reported $availableBytes bytes."
        override val remediationSteps = listOf("Free storage space or choose a smaller provider selection before running BKX.")
    }
}

const val CONTENT_URI_PREFIX = "content://"
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
