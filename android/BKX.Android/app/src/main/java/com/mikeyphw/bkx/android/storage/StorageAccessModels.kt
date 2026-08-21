package com.mikeyphw.bkx.android.storage

import android.annotation.SuppressLint

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class StorageLocationKind {
    @SerialName("shared") Shared,
    @SerialName("removable") Removable,
    @SerialName("termux_home") TermuxHome,
    @SerialName("termux_prefix") TermuxPrefix,
    @SerialName("root_private") RootPrivate,
}

@Serializable
data class StorageLocation(
    val id: String,
    val label: String,
    val path: String,
    val kind: StorageLocationKind,
    val requiresRoot: Boolean,
)

// These are intentional canonical paths for foreign/root-owned storage, not this app's filesDir.
@SuppressLint("SdCardPath")
object BkxStorageLocations {
    const val SHARED_STORAGE = "/storage/emulated/0"
    const val TERMUX_FILES = "/data/data/com.termux/files"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_STORAGE = "/data/data/com.termux/files/home/storage"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val DATA_ADB = "/data/adb"
    const val APP_DATA_ROOT = "/data/user/0"

    fun defaults(rootAvailable: Boolean): List<StorageLocation> = buildList {
        add(StorageLocation("shared", "Internal storage", SHARED_STORAGE, StorageLocationKind.Shared, false))
        if (rootAvailable) addAll(rootPrivateDefaults())
    }

    fun rootPrivateDefaults(): List<StorageLocation> = listOf(
        StorageLocation("termux-files", "Termux files", TERMUX_FILES, StorageLocationKind.RootPrivate, true),
        StorageLocation("termux-home", "Termux home", TERMUX_HOME, StorageLocationKind.TermuxHome, true),
        StorageLocation("termux-storage", "Termux storage links", TERMUX_STORAGE, StorageLocationKind.TermuxHome, true),
        StorageLocation("termux-prefix", "Termux prefix", TERMUX_PREFIX, StorageLocationKind.TermuxPrefix, true),
        StorageLocation("data-adb", "Root modules and state", DATA_ADB, StorageLocationKind.RootPrivate, true),
        StorageLocation("app-data", "Application private data", APP_DATA_ROOT, StorageLocationKind.RootPrivate, true),
    )
}

// RootPathPolicy must recognize literal protected Android filesystem roots and the /sdcard compatibility alias.
@SuppressLint("SdCardPath")
object RootPathPolicy {
    fun isTermuxPrivate(path: String): Boolean = normalize(path).let {
        it == BkxStorageLocations.TERMUX_FILES || it.startsWith("${BkxStorageLocations.TERMUX_FILES}/")
    }

    fun requiresRoot(path: String): Boolean {
        val normalized = normalize(path)
        if (normalized.startsWith("/storage/") || normalized == "/sdcard" || normalized.startsWith("/sdcard/")) {
            return false
        }
        return normalized.startsWith("/data/") ||
            normalized.startsWith("/system/") ||
            normalized.startsWith("/vendor/") ||
            normalized.startsWith("/product/") ||
            normalized.startsWith("/metadata/") ||
            normalized.startsWith("/mnt/media_rw/")
    }

    private fun normalize(path: String): String = path.trim().replace('\\', '/').trimEnd('/').ifBlank { "/" }
}
