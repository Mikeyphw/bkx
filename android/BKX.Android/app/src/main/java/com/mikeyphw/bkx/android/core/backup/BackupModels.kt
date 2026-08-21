package com.mikeyphw.bkx.android.core.backup

import com.mikeyphw.bkx.android.core.files.ContentUriPathPolicy

private const val MIN_SPLIT_SIZE_BYTES = 1024L * 1024L

data class BackupSourceSelection(
    val sourceId: String,
    val label: String,
    val stagedPath: String,
    val originalUri: String? = null,
) {
    init {
        require(sourceId.isNotBlank()) { "Backup source id must not be blank." }
        require(label.isNotBlank()) { "Backup source label must not be blank." }
        require(stagedPath.isNotBlank()) { "Backup source staged path must not be blank." }
        ContentUriPathPolicy.requireNotContentUriPath(stagedPath)
    }
}

data class BackupDestinationSelection(
    val destinationId: String,
    val label: String,
    val stagedDirectoryPath: String,
) {
    init {
        require(destinationId.isNotBlank()) { "Backup destination id must not be blank." }
        require(label.isNotBlank()) { "Backup destination label must not be blank." }
        require(stagedDirectoryPath.isNotBlank()) { "Backup destination path must not be blank." }
        ContentUriPathPolicy.requireNotContentUriPath(stagedDirectoryPath)
    }
}

data class BackupFilterRules(
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val includeHidden: Boolean = true,
    val followSymlinks: Boolean = false,
) {
    init {
        require(includePatterns.none { it.isBlank() }) { "Include filters must not contain blank patterns." }
        require(excludePatterns.none { it.isBlank() }) { "Exclude filters must not contain blank patterns." }
    }
}

enum class BackupCompression(val protocolValue: String) {
    None("none"),
    Zstd("zstd"),
    Gzip("gzip"),
}

data class BackupSplitPolicy(
    val enabled: Boolean = false,
    val chunkSizeBytes: Long? = null,
) {
    init {
        if (enabled) {
            require(chunkSizeBytes != null && chunkSizeBytes >= MIN_SPLIT_SIZE_BYTES) {
                "Enabled split policy requires chunk size of at least $MIN_SPLIT_SIZE_BYTES bytes."
            }
        }
        if (!enabled) {
            require(chunkSizeBytes == null) { "Disabled split policy must not carry a chunk size." }
        }
    }
}

data class BackupVerificationPolicy(
    val verifyAfterCreate: Boolean = true,
    val expectedHashAlgorithm: String = "sha256",
) {
    init {
        if (verifyAfterCreate) {
            require(expectedHashAlgorithm.isNotBlank()) { "Verification hash algorithm must not be blank." }
        }
    }
}

data class BackupPlanDraft(
    val source: BackupSourceSelection? = null,
    val destination: BackupDestinationSelection? = null,
    val filters: BackupFilterRules = BackupFilterRules(),
    val compression: BackupCompression = BackupCompression.Gzip,
    val split: BackupSplitPolicy = BackupSplitPolicy(),
    val verification: BackupVerificationPolicy = BackupVerificationPolicy(),
) {
    fun validationIssues(): List<BackupPlanIssue> = buildList {
        if (source == null) add(BackupPlanIssue.MissingSource)
        if (destination == null) add(BackupPlanIssue.MissingDestination)
        if (source != null && destination != null && source.stagedPath == destination.stagedDirectoryPath) add(BackupPlanIssue.SourceEqualsDestination)
        if (filters.followSymlinks) add(BackupPlanIssue.FollowSymlinksUnsupported)
    }

    fun requireValid() {
        val issues = validationIssues()
        require(issues.isEmpty()) { issues.joinToString { it.message } }
    }

    fun fingerprint(): String = listOf(
        source?.sourceId.orEmpty(),
        source?.stagedPath.orEmpty(),
        destination?.destinationId.orEmpty(),
        destination?.stagedDirectoryPath.orEmpty(),
        filters.includePatterns.joinToString("\u001f"),
        filters.excludePatterns.joinToString("\u001f"),
        filters.includeHidden.toString(),
        filters.followSymlinks.toString(),
        compression.protocolValue,
        split.enabled.toString(),
        split.chunkSizeBytes?.toString().orEmpty(),
        verification.verifyAfterCreate.toString(),
        verification.expectedHashAlgorithm,
    ).joinToString("\u001e")

    companion object {
        fun fromCliIntent(intent: BackupCliIntent): BackupPlanDraft = BackupPlanDraft(
            source = BackupSourceSelection(
                sourceId = "cli-source",
                label = intent.sourcePath.substringAfterLast('/').ifBlank { intent.sourcePath },
                stagedPath = intent.sourcePath,
            ),
            destination = BackupDestinationSelection(
                destinationId = "cli-destination",
                label = intent.destinationPath.substringAfterLast('/').ifBlank { intent.destinationPath },
                stagedDirectoryPath = intent.destinationPath,
            ),
            filters = BackupFilterRules(
                includePatterns = intent.includePatterns,
                excludePatterns = intent.excludePatterns,
                includeHidden = intent.includeHidden,
                followSymlinks = intent.followSymlinks,
            ),
            compression = intent.compression,
            split = BackupSplitPolicy(
                enabled = intent.splitChunkSizeBytes != null,
                chunkSizeBytes = intent.splitChunkSizeBytes,
            ),
            verification = BackupVerificationPolicy(verifyAfterCreate = intent.verifyAfterCreate),
        )
    }
}

data class BackupCliIntent(
    val sourcePath: String,
    val destinationPath: String,
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val includeHidden: Boolean = true,
    val followSymlinks: Boolean = false,
    val compression: BackupCompression = BackupCompression.Gzip,
    val splitChunkSizeBytes: Long? = null,
    val verifyAfterCreate: Boolean = true,
)

sealed interface BackupPlanIssue {
    val code: String
    val message: String

    data object MissingSource : BackupPlanIssue {
        override val code: String = "missing_source"
        override val message: String = "Choose a backup source before planning."
    }

    data object MissingDestination : BackupPlanIssue {
        override val code: String = "missing_destination"
        override val message: String = "Choose a backup destination before planning."
    }

    data object SourceEqualsDestination : BackupPlanIssue {
        override val code: String = "source_equals_destination"
        override val message: String = "Source and destination must not resolve to the same staged path."
    }

    data object FollowSymlinksUnsupported : BackupPlanIssue {
        override val code: String = "follow_symlinks_unsupported"
        override val message: String = "BKX Android skips symbolic links for safe root backups; following links is not supported."
    }
}

enum class BackupWarningSeverity { Info, Warning, Blocker }

data class BackupPlanWarning(
    val code: String,
    val message: String,
    val severity: BackupWarningSeverity = BackupWarningSeverity.Warning,
)

data class BackupVerificationExpectation(
    val code: String,
    val message: String,
    val requiredBeforeSuccess: Boolean = true,
)

data class BackupPlanPreview(
    val requestId: String,
    val planToken: String,
    val draftFingerprint: String,
    val warnings: List<BackupPlanWarning> = emptyList(),
    val verificationExpectations: List<BackupVerificationExpectation> = emptyList(),
) {
    val isExecutable: Boolean = warnings.none { it.severity == BackupWarningSeverity.Blocker }
}

data class BackupPlanningUiState(
    val draft: BackupPlanDraft = BackupPlanDraft(),
    val validationIssues: List<BackupPlanIssue> = draft.validationIssues(),
    val planRequestId: String? = null,
    val preview: BackupPlanPreview? = null,
) {
    val canRequestPlan: Boolean = validationIssues.isEmpty()
    val canCreateBackup: Boolean = preview != null && preview.isExecutable && preview.draftFingerprint == draft.fingerprint()

    companion object {
        fun withPreview(draft: BackupPlanDraft, preview: BackupPlanPreview): BackupPlanningUiState = BackupPlanningUiState(
            draft = draft,
            validationIssues = draft.validationIssues(),
            planRequestId = preview.requestId,
            preview = preview,
        )
    }
}
