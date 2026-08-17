package com.mikeyphw.bkx.android.core.transfer

import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.files.ContentUriPathPolicy

private val SAFE_RELATIVE_PATH_REGEX = Regex("^(?!/)(?![A-Za-z]:)(?!.*\\u0000)(?!.*\\\\)(?!.*(?:^|/)\\.{1,2}(?:/|$))\\S(?:.*\\S)?/?$")

enum class TransferMode(val protocolValue: String) {
    Push("push"),
    Pull("pull"),
    Sync("sync"),
}

data class TransferEndpoint(
    val endpointId: String,
    val label: String,
    val location: String,
    val isRemote: Boolean = location.contains(":") && !location.startsWith("/") && !location.startsWith("content://"),
) {
    init {
        require(endpointId.isNotBlank()) { "Transfer endpoint id must not be blank." }
        require(label.isNotBlank()) { "Transfer endpoint label must not be blank." }
        require(location.isNotBlank()) { "Transfer endpoint location must not be blank." }
        ContentUriPathPolicy.requireNotContentUriPath(location)
    }
}

enum class TransferFilePolicy(val protocolValue: String) {
    Preserve("preserve"),
    Skip("skip"),
    Block("block"),
}

data class TransferTopologyDraft(
    val mode: TransferMode = TransferMode.Push,
    val sources: List<TransferEndpoint> = emptyList(),
    val destination: TransferEndpoint? = null,
    val verifyAfterTransfer: Boolean = true,
    val checksum: String? = null,
    val overwriteExisting: Boolean = false,
    val deleteExtraneousOnSync: Boolean = false,
    val symlinkPolicy: TransferFilePolicy = TransferFilePolicy.Block,
    val specialFilePolicy: TransferFilePolicy = TransferFilePolicy.Block,
) {
    fun validationIssues(): List<TransferValidationIssue> = buildList {
        if (sources.isEmpty()) add(TransferValidationIssue.MissingSource)
        if (destination == null) add(TransferValidationIssue.MissingDestination)
        if (mode == TransferMode.Sync && sources.size != 1) add(TransferValidationIssue.InvalidTopology("Sync requires exactly one source."))
        if (mode == TransferMode.Push && sources.any { it.isRemote }) add(TransferValidationIssue.InvalidTopology("Push sources must be staged local paths."))
        if (mode == TransferMode.Pull && destination?.isRemote == true) add(TransferValidationIssue.InvalidTopology("Pull destination must be a staged local path."))
        if (checksum != null && checksum.isBlank()) add(TransferValidationIssue.InvalidTopology("Checksum must not be blank when provided."))
        if (destination != null) {
            sources.forEach { source ->
                if (source.location == destination.location) {
                    add(TransferValidationIssue.OverlappingPaths("Transfer destination must not equal a source path."))
                }
            }
        }
    }

    fun requireValid() {
        val issues = validationIssues()
        require(issues.isEmpty()) { issues.joinToString { it.message } }
    }

    fun fingerprint(): String = listOf(
        mode.protocolValue,
        sources.joinToString("\u001F") { "${it.endpointId}:${it.location}:${it.isRemote}" },
        destination?.endpointId.orEmpty(),
        destination?.location.orEmpty(),
        verifyAfterTransfer.toString(),
        checksum.orEmpty(),
        overwriteExisting.toString(),
        deleteExtraneousOnSync.toString(),
        symlinkPolicy.protocolValue,
        specialFilePolicy.protocolValue,
    ).joinToString("\u001E").stableToken()
}

sealed interface TransferValidationIssue {
    val code: String
    val message: String

    data object MissingSource : TransferValidationIssue {
        override val code = "transfer_source_missing"
        override val message = "Select at least one transfer source."
    }

    data object MissingDestination : TransferValidationIssue {
        override val code = "transfer_destination_missing"
        override val message = "Select a transfer destination."
    }

    data class InvalidTopology(override val message: String) : TransferValidationIssue {
        override val code = "transfer_topology_invalid"
    }

    data class OverlappingPaths(override val message: String) : TransferValidationIssue {
        override val code = "transfer_overlap_blocked"
    }
}

data class TransferMappingSummary(
    val sourceLabel: String,
    val destinationLabel: String,
    val relativePath: String,
    val action: TransferMappingAction,
) {
    init {
        require(sourceLabel.isNotBlank()) { "Transfer mapping source label must not be blank." }
        require(destinationLabel.isNotBlank()) { "Transfer mapping destination label must not be blank." }
        require(SAFE_RELATIVE_PATH_REGEX.matches(relativePath)) { "Transfer mapping path must be relative and safe: $relativePath" }
    }
}

enum class TransferMappingAction { Create, Update, Skip, Delete }

data class TransferCollisionSummary(
    val collisions: List<String> = emptyList(),
    val overlaps: List<String> = emptyList(),
    val symlinkPolicy: TransferFilePolicy = TransferFilePolicy.Block,
    val specialFilePolicy: TransferFilePolicy = TransferFilePolicy.Block,
) {
    init {
        require(collisions.none { it.isBlank() }) { "Transfer collision summaries must not be blank." }
        require(overlaps.none { it.isBlank() }) { "Transfer overlap summaries must not be blank." }
    }

    val hasBlockingCollisions: Boolean = collisions.isNotEmpty() || overlaps.isNotEmpty()
}

data class TransferVerificationPlan(
    val enabled: Boolean = true,
    val checksum: String? = null,
    val expectedEvidence: List<String> = if (enabled) listOf("BKX verification result") else emptyList(),
) {
    init {
        require(checksum == null || checksum.isNotBlank()) { "Transfer checksum must not be blank." }
        require(expectedEvidence.none { it.isBlank() }) { "Transfer verification evidence entries must not be blank." }
    }
}

data class TransferPreviewSummary(
    val requestId: String,
    val draftFingerprint: String,
    val topologyLabel: String,
    val mappings: List<TransferMappingSummary>,
    val collisionSummary: TransferCollisionSummary,
    val verificationPlan: TransferVerificationPlan,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(requestId.isNotBlank()) { "Transfer preview request id must not be blank." }
        require(draftFingerprint.isNotBlank()) { "Transfer preview draft fingerprint must not be blank." }
        require(topologyLabel.isNotBlank()) { "Transfer topology label must not be blank." }
        require(warnings.none { it.isBlank() }) { "Transfer preview warnings must not be blank." }
    }

    val isExecutable: Boolean = !collisionSummary.hasBlockingCollisions
}

sealed interface TransferResultUiState {
    data object NotStarted : TransferResultUiState
    data class Succeeded(val summary: String) : TransferResultUiState
    data class PartialFailure(val errors: List<BridgeError>, val summary: String) : TransferResultUiState
    data class VerificationFailed(val errors: List<BridgeError>, val summary: String) : TransferResultUiState
    data class Failed(val errors: List<BridgeError>, val summary: String) : TransferResultUiState
    data class Cancelled(val summary: String) : TransferResultUiState
}

data class TransferUiState(
    val draft: TransferTopologyDraft = TransferTopologyDraft(),
    val preview: TransferPreviewSummary? = null,
    val result: TransferResultUiState = TransferResultUiState.NotStarted,
) {
    val validationIssues: List<TransferValidationIssue> = draft.validationIssues()
    val canPreview: Boolean = validationIssues.isEmpty()
    val canRun: Boolean = canRunTransfer(draft, preview)
}

fun canRunTransfer(draft: TransferTopologyDraft, preview: TransferPreviewSummary?): Boolean {
    if (draft.validationIssues().isNotEmpty()) return false
    if (preview == null) return false
    if (preview.draftFingerprint != draft.fingerprint()) return false
    return preview.isExecutable
}

fun BridgeResultEnvelope.toTransferResultUiState(): TransferResultUiState = when (status) {
    BridgeResultStatus.Success -> TransferResultUiState.Succeeded(summary.ifBlank { "Transfer completed." })
    BridgeResultStatus.Cancelled -> TransferResultUiState.Cancelled(summary.ifBlank { "Transfer was cancelled." })
    BridgeResultStatus.Failure -> when {
        errors.any { it.code.contains("partial", ignoreCase = true) } -> TransferResultUiState.PartialFailure(
            errors = errors,
            summary = summary.ifBlank { "Transfer completed with partial failures." },
        )
        errors.any { it.code.contains("verify", ignoreCase = true) || it.code.contains("checksum", ignoreCase = true) } -> TransferResultUiState.VerificationFailed(
            errors = errors,
            summary = summary.ifBlank { "Transfer verification failed." },
        )
        else -> TransferResultUiState.Failed(errors = errors, summary = summary.ifBlank { "Transfer failed." })
    }
}

private fun String.stableToken(): String {
    var hash = 1125899906842597L
    for (char in this) {
        hash = 31 * hash + char.code
    }
    val unsigned = hash.toULong().toString(16).padStart(16, '0')
    return unsigned.repeat(4).take(64)
}
