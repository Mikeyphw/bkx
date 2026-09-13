package com.mikeyphw.bkx.android.storage

import android.annotation.SuppressLint

import com.mikeyphw.bkx.android.core.files.AndroidPathPolicy
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
    val usableForDirectFileAccess: Boolean = true,
    val unavailableReason: String? = null,
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
    fun canonicalize(path: String): String = AndroidPathPolicy.canonicalize(path).ifBlank { "/" }

    fun isTermuxPrivate(path: String): Boolean = AndroidPathPolicy.isTermuxPrivate(path)

    fun restrictedWriteReason(path: String): String? = AndroidPathPolicy.restrictedWriteReason(path)

    fun requiresRoot(path: String): Boolean = AndroidPathPolicy.rootSensitiveReason(path) != null && !AndroidPathPolicy.isSharedStorage(path)
}
