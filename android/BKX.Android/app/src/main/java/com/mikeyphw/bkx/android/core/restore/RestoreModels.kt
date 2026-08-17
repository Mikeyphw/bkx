package com.mikeyphw.bkx.android.core.restore

import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.files.ContentUriPathPolicy

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private val SAFE_MEMBER_REGEX = Regex("^(?!/)(?![A-Za-z]:)(?!.*\\u0000)(?!.*\\\\)(?!.*(?:^|/)\\.{1,2}(?:/|$))\\S(?:.*\\S)?/?$")

data class RestoreDestinationSelection(
    val destinationId: String,
    val label: String,
    val stagedDirectoryPath: String,
) {
    init {
        require(destinationId.isNotBlank()) { "Restore destination id must not be blank." }
        require(label.isNotBlank()) { "Restore destination label must not be blank." }
        require(stagedDirectoryPath.isNotBlank()) { "Restore destination path must not be blank." }
        ContentUriPathPolicy.requireNotContentUriPath(stagedDirectoryPath)
        require(stagedDirectoryPath.startsWith("/")) { "Restore destination must be an absolute staged/shared path." }
    }
}

sealed interface RestoreSubsetSelection {
    val selectedPaths: List<String>

    data object All : RestoreSubsetSelection {
        override val selectedPaths: List<String> = emptyList()
    }

    data class Paths(override val selectedPaths: List<String>) : RestoreSubsetSelection {
        init {
            require(selectedPaths.isNotEmpty()) { "Selected restore paths must not be empty." }
            require(selectedPaths.distinct() == selectedPaths) { "Duplicate restore path selectors are not allowed." }
            selectedPaths.forEach { path ->
                require(path.isNotBlank()) { "Restore path selector must not be blank." }
                require(SAFE_MEMBER_REGEX.matches(path)) { "Restore path selector must be archive-relative and path-safe: $path" }
            }
        }
    }
}

enum class RestoreOutputMode(val protocolValue: String) {
    FreshDirectory("fresh_directory"),
    ExistingDirectory("existing_directory"),
}

data class RestoreDraft(
    val archive: ArchiveLocation? = null,
    val destination: RestoreDestinationSelection? = null,
    val subset: RestoreSubsetSelection = RestoreSubsetSelection.All,
    val outputMode: RestoreOutputMode = RestoreOutputMode.FreshDirectory,
    val verifyBeforeRestore: Boolean = true,
) {
    val freshDestination: Boolean = outputMode == RestoreOutputMode.FreshDirectory

    fun validationIssues(): List<RestoreValidationIssue> = buildList {
        if (archive == null) add(RestoreValidationIssue.MissingArchive)
        if (outputMode == RestoreOutputMode.ExistingDirectory && destination == null) {
            add(RestoreValidationIssue.MissingDestination)
        }
        if (destination != null && !destination.stagedDirectoryPath.startsWith("/")) {
            add(RestoreValidationIssue.UnsafeDestination("Restore destination must be an absolute staged/shared path."))
        }
        if (archive != null && archive.stagedPath == destination?.stagedDirectoryPath) {
            add(RestoreValidationIssue.UnsafeDestination("Restore destination must not be the same path as the selected archive."))
        }
    }

    fun requireValid() {
        val issues = validationIssues()
        require(issues.isEmpty()) { issues.joinToString { it.message } }
    }

    fun fingerprint(): String = listOf(
        archive?.archiveId.orEmpty(),
        archive?.stagedPath.orEmpty(),
        destination?.destinationId.orEmpty(),
        destination?.stagedDirectoryPath.orEmpty(),
        subset.selectedPaths.joinToString("\u001F"),
        outputMode.protocolValue,
        verifyBeforeRestore.toString(),
    ).joinToString("\u001E").sha256Like()
}

sealed interface RestoreValidationIssue {
    val code: String
    val message: String

    data object MissingArchive : RestoreValidationIssue {
        override val code = "restore_archive_missing"
        override val message = "Select an archive before previewing restore."
    }

    data object MissingDestination : RestoreValidationIssue {
        override val code = "restore_destination_missing"
        override val message = "Select a destination before restoring into an existing directory."
    }

    data class UnsafeDestination(override val message: String) : RestoreValidationIssue {
        override val code = "restore_destination_unsafe"
    }
}

data class RestoreOverwriteSummary(
    val paths: List<String> = emptyList(),
) {
    init {
        paths.forEach { path -> require(path.isNotBlank()) { "Overwrite path summary entries must not be blank." } }
    }

    val totalOverwrites: Int = paths.size
    val requiresConfirmation: Boolean = paths.isNotEmpty()
}

data class RestorePathSafetySummary(
    val unsafePaths: List<String> = emptyList(),
    val blockedLinks: List<String> = emptyList(),
    val blockedSpecialFiles: List<String> = emptyList(),
) {
    init {
        require(unsafePaths.none { it.isBlank() }) { "Unsafe path entries must not be blank." }
        require(blockedLinks.none { it.isBlank() }) { "Blocked link entries must not be blank." }
        require(blockedSpecialFiles.none { it.isBlank() }) { "Blocked special-file entries must not be blank." }
    }

    val hasBlocks: Boolean = unsafePaths.isNotEmpty() || blockedLinks.isNotEmpty() || blockedSpecialFiles.isNotEmpty()
}

data class RestorePreviewSummary(
    val requestId: String,
    val draftFingerprint: String,
    val planToken: String,
    val selectedPathCount: Int,
    val fullRestore: Boolean,
    val overwriteSummary: RestoreOverwriteSummary = RestoreOverwriteSummary(),
    val pathSafetySummary: RestorePathSafetySummary = RestorePathSafetySummary(),
    val verificationHash: String? = null,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(requestId.isNotBlank()) { "Restore preview request id must not be blank." }
        require(draftFingerprint.isNotBlank()) { "Restore preview draft fingerprint must not be blank." }
        require(planToken.isNotBlank()) { "Restore preview plan token must not be blank." }
        require(selectedPathCount >= 0) { "Selected restore path count must not be negative." }
        require(verificationHash == null || SHA256_REGEX.matches(verificationHash)) { "Restore verification hash must be lowercase SHA-256 when present." }
        require(warnings.none { it.isBlank() }) { "Restore preview warnings must not be blank." }
    }

    val requiresDestructiveConfirmation: Boolean = overwriteSummary.requiresConfirmation
    val isExecutable: Boolean = !pathSafetySummary.hasBlocks
}

data class RestoreExtractionConfirmation(
    val draftFingerprint: String,
    val planToken: String,
    val acknowledgedOverwriteCount: Int,
    val confirmationPhrase: String,
    val confirmedAtEpochMillis: Long,
) {
    init {
        require(draftFingerprint.isNotBlank()) { "Restore confirmation draft fingerprint must not be blank." }
        require(planToken.isNotBlank()) { "Restore confirmation plan token must not be blank." }
        require(acknowledgedOverwriteCount >= 0) { "Restore confirmation overwrite count must not be negative." }
        require(confirmationPhrase == CONFIRMATION_PHRASE) { "Type $CONFIRMATION_PHRASE to confirm destructive restore." }
        require(confirmedAtEpochMillis > 0L) { "Restore confirmation timestamp must be positive." }
    }

    fun matches(draft: RestoreDraft, preview: RestorePreviewSummary): Boolean =
        draftFingerprint == draft.fingerprint() &&
            draftFingerprint == preview.draftFingerprint &&
            planToken == preview.planToken &&
            acknowledgedOverwriteCount == preview.overwriteSummary.totalOverwrites

    companion object {
        const val CONFIRMATION_PHRASE = "RESTORE"
    }
}

data class RestoreUiState(
    val draft: RestoreDraft = RestoreDraft(),
    val preview: RestorePreviewSummary? = null,
    val confirmation: RestoreExtractionConfirmation? = null,
) {
    val validationIssues: List<RestoreValidationIssue> = draft.validationIssues()
    val canPreview: Boolean = validationIssues.isEmpty()
    val canExtract: Boolean = canExtractRestore(draft, preview, confirmation)
    val isFreshDestinationDefault: Boolean = draft.outputMode == RestoreOutputMode.FreshDirectory
}

fun canExtractRestore(
    draft: RestoreDraft,
    preview: RestorePreviewSummary?,
    confirmation: RestoreExtractionConfirmation?,
): Boolean {
    if (draft.validationIssues().isNotEmpty()) return false
    if (preview == null) return false
    if (preview.draftFingerprint != draft.fingerprint()) return false
    if (!preview.isExecutable) return false
    if (!preview.requiresDestructiveConfirmation) return true
    return confirmation?.matches(draft, preview) == true
}

fun BridgeResultEnvelope.toRestorePreviewSummary(draft: RestoreDraft): RestorePreviewSummary {
    draft.requireValid()
    val overwritePaths = errors.filter { it.code.contains("overwrite", ignoreCase = true) || it.code.contains("collision", ignoreCase = true) }.map { it.message }
    val unsafePaths = errors.filter { it.code.contains("path", ignoreCase = true) || it.code.contains("traversal", ignoreCase = true) }.map { it.message }
    val blockedLinks = errors.filter { it.code.contains("link", ignoreCase = true) || it.code.contains("symlink", ignoreCase = true) }.map { it.message }
    val blockedSpecialFiles = errors.filter { it.code.contains("special", ignoreCase = true) || it.code.contains("device", ignoreCase = true) }.map { it.message }
    return RestorePreviewSummary(
        requestId = requestId,
        draftFingerprint = draft.fingerprint(),
        planToken = artifacts.firstOrNull { it.label == "restore_plan" }?.sha256 ?: "$requestId:${draft.fingerprint()}",
        selectedPathCount = draft.subset.selectedPaths.size,
        fullRestore = draft.subset.selectedPaths.isEmpty(),
        overwriteSummary = RestoreOverwriteSummary(overwritePaths),
        pathSafetySummary = RestorePathSafetySummary(
            unsafePaths = unsafePaths,
            blockedLinks = blockedLinks,
            blockedSpecialFiles = blockedSpecialFiles,
        ),
        verificationHash = artifacts.firstOrNull { it.label == "archive_verification" }?.sha256,
        warnings = if (summary.isBlank()) emptyList() else listOf(summary),
    )
}

private fun String.sha256Like(): String {
    var hash = 1125899906842597L
    for (char in this) {
        hash = 31 * hash + char.code
    }
    val unsigned = hash.toULong().toString(16).padStart(16, '0')
    return unsigned.repeat(4).take(64)
}
