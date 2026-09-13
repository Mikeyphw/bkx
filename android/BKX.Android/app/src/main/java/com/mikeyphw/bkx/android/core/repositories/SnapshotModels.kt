package com.mikeyphw.bkx.android.core.repositories

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotDescriptor(
    val id: String,
    @SerialName("short_id") val shortId: String = "",
    val time: String,
    val hostname: String = "",
    val username: String = "",
    val paths: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val size: ULong? = null,
) {
    init {
        require(id.isNotBlank()) { "snapshot id must not be blank" }
        require(time.isNotBlank()) { "snapshot time must not be blank" }
    }
}

enum class SnapshotOverwriteMode(val wireValue: String) {
    Always("always"),
    IfChanged("if-changed"),
    IfNewer("if-newer"),
    Never("never"),
}

data class SnapshotNodeDescriptor(
    val name: String,
    val type: String,
    val path: String,
    val size: ULong? = null,
    val permissions: String? = null,
    val modifiedAt: String? = null,
) {
    init {
        require(name.isNotBlank()) { "snapshot node name must not be blank" }
        require(type.isNotBlank()) { "snapshot node type must not be blank" }
        require(path.startsWith("/")) { "snapshot node path must be absolute within the snapshot" }
    }

    val isDirectory: Boolean get() = type == "dir"
    val isFile: Boolean get() = type == "file"
    val isSymlink: Boolean get() = type == "symlink"
}

data class SnapshotDiffEntry(
    val path: String,
    val modifier: String,
)

data class SnapshotDiffSummary(
    val entries: List<SnapshotDiffEntry> = emptyList(),
    val changedFiles: Long = 0L,
    val bytesAdded: ULong? = null,
    val bytesRemoved: ULong? = null,
    val truncated: Boolean = false,
    val limit: Int? = null,
)

data class SnapshotRestoreResult(
    val target: String,
    val snapshotId: String,
    val overwrite: String,
    val dryRun: Boolean,
    val filesRestored: Long = 0L,
    val filesSkipped: Long = 0L,
    val bytesRestored: Long = 0L,
    val bytesSkipped: Long = 0L,
    val totalFiles: Long = 0L,
    val totalBytes: Long = 0L,
)

