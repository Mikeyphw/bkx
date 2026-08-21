package com.mikeyphw.bkx.android.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

sealed interface AllFilesAccessState {
    data object Granted : AllFilesAccessState
    data object NeedsGrant : AllFilesAccessState
    data object LegacyPlatform : AllFilesAccessState
}

class AndroidStorageAccessManager(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun hasAllFilesAccess(): Boolean = state() != AllFilesAccessState.NeedsGrant

    fun state(): AllFilesAccessState = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> AllFilesAccessState.LegacyPlatform
        Environment.isExternalStorageManager() -> AllFilesAccessState.Granted
        else -> AllFilesAccessState.NeedsGrant
    }


    fun discoverLocations(rootAvailable: Boolean): List<StorageLocation> {
        val locations = BkxStorageLocations.defaults(rootAvailable).toMutableList()
        val known = locations.mapTo(mutableSetOf()) { it.path }
        appContext.getExternalFilesDirs(null).forEachIndexed { index, directory ->
            val absolute = directory?.absolutePath ?: return@forEachIndexed
            val marker = "/Android/data/"
            val markerIndex = absolute.indexOf(marker)
            if (markerIndex <= 0) return@forEachIndexed
            val root = absolute.substring(0, markerIndex)
            if (root !in known) {
                known += root
                locations += StorageLocation(
                    id = "removable-$index",
                    label = "Removable storage",
                    path = root,
                    kind = StorageLocationKind.Removable,
                    requiresRoot = false,
                )
            }
        }
        return locations
    }

    fun grantIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openGrantSettings(): Boolean {
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
}
