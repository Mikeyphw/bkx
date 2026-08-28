package com.mikeyphw.bkx.android.storage

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.net.toUri
import java.io.File

sealed interface AllFilesAccessState {
    data object Granted : AllFilesAccessState
    data object NeedsGrant : AllFilesAccessState
    data object LegacyPlatform : AllFilesAccessState
}

class AndroidStorageAccessManager(
    context: Context,
) {
    private val appContext = context.applicationContext ?: context

    fun hasAllFilesAccess(): Boolean = state() != AllFilesAccessState.NeedsGrant

    fun state(): AllFilesAccessState = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> AllFilesAccessState.LegacyPlatform
        Environment.isExternalStorageManager() -> AllFilesAccessState.Granted
        else -> AllFilesAccessState.NeedsGrant
    }

    /**
     * Discover actual shared/removable volumes rather than hard-coding a single
     * emulated-storage path. Root-private locations are appended only when the
     * currently selected execution route is privileged.
     */
    fun discoverLocations(rootAvailable: Boolean): List<StorageLocation> {
        val locations = mutableListOf<StorageLocation>()
        val seen = mutableSetOf<String>()

        fun add(location: StorageLocation) {
            val normalized = location.path.trimEnd('/').ifBlank { "/" }
            if (seen.add(normalized)) locations += location.copy(path = normalized)
        }

        add(StorageLocation("shared", "Internal storage", BkxStorageLocations.SHARED_STORAGE, StorageLocationKind.Shared, false))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = appContext.getSystemService(StorageManager::class.java)
            manager?.storageVolumes.orEmpty().forEachIndexed { index, volume ->
                val directory = volume.directory ?: return@forEachIndexed
                val path = directory.absolutePath
                if (path == BkxStorageLocations.SHARED_STORAGE || path == "/storage/self/primary") return@forEachIndexed
                add(
                    StorageLocation(
                        id = "volume-$index",
                        label = runCatching { volume.getDescription(appContext) }.getOrNull().orEmpty().ifBlank {
                            if (volume.isRemovable) "Removable storage" else "Storage volume"
                        },
                        path = path,
                        kind = if (volume.isRemovable) StorageLocationKind.Removable else StorageLocationKind.Shared,
                        requiresRoot = false,
                    ),
                )
            }
        }

        // API 24-29 fallback and an additional compatibility source on vendor
        // builds which do not expose a directory on StorageVolume.
        appContext.getExternalFilesDirs(null).forEachIndexed { index, directory ->
            val root = directory?.volumeRootFromAppExternalDir() ?: return@forEachIndexed
            if (root == BkxStorageLocations.SHARED_STORAGE) return@forEachIndexed
            add(
                StorageLocation(
                    id = "external-$index",
                    label = "Removable storage",
                    path = root,
                    kind = StorageLocationKind.Removable,
                    requiresRoot = false,
                ),
            )
        }

        if (rootAvailable) {
            BkxStorageLocations.rootPrivateDefaults().forEach(::add)
        }
        return locations
    }

    /** Bounded roots used by archive.list. We search BKX folders, not whole disks. */
    fun archiveSearchRoots(rootAvailable: Boolean): List<String> = discoverLocations(rootAvailable)
        .filter { !it.requiresRoot || rootAvailable }
        .mapNotNull { location ->
            when (location.kind) {
                StorageLocationKind.Shared,
                StorageLocationKind.Removable -> File(location.path, "BKX").absolutePath
                StorageLocationKind.TermuxHome,
                StorageLocationKind.TermuxPrefix,
                StorageLocationKind.RootPrivate -> null
            }
        }
        .distinct()

    fun grantIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openGrantSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intent = grantIntent() ?: return false
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrElse {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
        }
    }

    private fun File.volumeRootFromAppExternalDir(): String? {
        val absolute = absolutePath
        val marker = "/Android/data/"
        val markerIndex = absolute.indexOf(marker)
        return absolute.takeIf { markerIndex > 0 }?.substring(0, markerIndex)
    }
}
