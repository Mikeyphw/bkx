package com.mikeyphw.bkx.android.core.files

class StagedFilePlanner {
    fun plan(selection: UserFileSelection, stagingPath: String, evidence: StagingEvidence): StagedFileRecord {
        ContentUriPathPolicy.requireNotContentUriPath(stagingPath)
        val restriction = RestrictedLocationPolicy.classify(stagingPath)
        if (restriction != null) {
            throw FileAccessPolicyException(restriction)
        }
        if (selection.grant.state != UriGrantState.Active) {
            throw FileAccessPolicyException(FileAccessIssue.GrantLost(selection.uri))
        }
        return StagedFileRecord(
            sourceUri = selection.uri,
            stagedPath = stagingPath,
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
}

object RestrictedLocationPolicy {
    fun classify(path: String): FileAccessIssue? {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isBlank()) {
            return FileAccessIssue.UnavailableLocation(path, "staging location is blank")
        }
        if (!normalized.startsWith("/")) {
            return FileAccessIssue.UnavailableLocation(path, "staging location must be an absolute shared path")
        }
        if (normalized == "/" || normalized == "~") {
            return FileAccessIssue.RestrictedLocation(normalized, "root-level access is not a user-visible SAF grant")
        }
        val blockedSegments = listOf("/Android/data", "/Android/obb", "/proc", "/sys", "/dev")
        val blocked = blockedSegments.firstOrNull { segment ->
            normalized == segment || normalized.startsWith("$segment/")
        }
        return blocked?.let {
            FileAccessIssue.RestrictedLocation(it, "Android storage restrictions make this location unavailable to the companion app")
        }
    }
}
