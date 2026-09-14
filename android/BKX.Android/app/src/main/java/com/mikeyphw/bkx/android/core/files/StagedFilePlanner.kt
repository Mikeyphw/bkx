package com.mikeyphw.bkx.android.core.files

class StagedFilePlanner {
    fun plan(selection: UserFileSelection, stagingPath: String, evidence: StagingEvidence): StagedFileRecord {
        ContentUriPathPolicy.requireNotContentUriPath(stagingPath)
        val restriction = RestrictedLocationPolicy.classify(stagingPath)
        if (restriction != null) {
            throw FileAccessPolicyException(restriction)
        }
        val requiredBytes = evidence.sizeBytes?.plus(STAGING_HEADROOM_BYTES)
        val availableBytes = evidence.availableBytes
        if (requiredBytes != null && availableBytes != null && availableBytes < requiredBytes) {
            throw FileAccessPolicyException(FileAccessIssue.InsufficientStagingCapacity(requiredBytes, availableBytes))
        }
        if (selection.grant.state != UriGrantState.Active) {
            throw FileAccessPolicyException(FileAccessIssue.GrantLost(selection.uri))
        }
        return StagedFileRecord(
            sourceUri = selection.uri,
            stagedPath = AndroidPathPolicy.canonicalize(stagingPath),
            sizeBytes = evidence.sizeBytes,
            sha256 = evidence.sha256,
            lifetime = StagingLifetime.PerRequest,
            cost = StagingCost(
                bytesToCopy = evidence.sizeBytes,
                requiresHashing = evidence.requiresHashing,
                userVisibleMessage = buildCostMessage(evidence),
            ),
            grantState = selection.grant.state,
        )
    }

    private fun buildCostMessage(evidence: StagingEvidence): String = when {
        evidence.sizeBytes == null -> "The selected content must be staged; Android did not report its size."
        evidence.requiresHashing -> "The selected content will be copied and hashed before BKX receives a staged path."
        else -> "The selected content will be copied to the configured staging location before BKX runs."
    }

    companion object {
        const val STAGING_HEADROOM_BYTES = 32L * 1024L * 1024L
    }
}

object RestrictedLocationPolicy {
    fun classify(path: String): FileAccessIssue? {
        if (ContentUriPathPolicy.isContentUri(path)) {
            return FileAccessIssue.ContentUriAsPathRejected(path)
        }
        if (AndroidPathPolicy.containsTraversal(path)) {
            return FileAccessIssue.RestrictedLocation(path, "path traversal segments are not allowed in staging destinations")
        }
        val normalized = AndroidPathPolicy.canonicalize(path)
        if (normalized.isBlank()) {
            return FileAccessIssue.UnavailableLocation(path, "staging location is blank")
        }
        if (!path.trim().replace('\\', '/').startsWith("/")) {
            return FileAccessIssue.UnavailableLocation(path, "staging location must be an absolute shared path")
        }
        return AndroidPathPolicy.restrictedWriteReason(normalized)?.let { reason ->
            FileAccessIssue.RestrictedLocation(normalized, reason)
        }
    }
}
