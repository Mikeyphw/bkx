package com.mikeyphw.bkx.android.core.files

/** Product-level selector backed by BKX filesystem.list, including root paths. */
enum class FilesystemSelectionTarget(val folderOnly: Boolean, val allowCurrentFolder: Boolean) {
    BackupSource(false, true),
    BackupDestination(true, true),
    Archive(false, false),
    RestoreDestination(true, true),
    TransferSource(false, true),
    TransferDestination(true, true),
}

enum class FilesystemEntryKind { Directory, File, Symlink, Special, Unreadable }

data class FilesystemBrowserEntry(
    val path: String,
    val name: String,
    val kind: FilesystemEntryKind,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
) {
    val selectable: Boolean get() = kind == FilesystemEntryKind.Directory || kind == FilesystemEntryKind.File
}

data class FilesystemBrowserUiState(
    val open: Boolean = false,
    val target: FilesystemSelectionTarget? = null,
    val currentPath: String = "/storage/emulated/0",
    val entries: List<FilesystemBrowserEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val showHidden: Boolean = true,
) {
    val canUseCurrentFolder: Boolean get() = open && target?.allowCurrentFolder == true && currentPath.startsWith("/")
}
