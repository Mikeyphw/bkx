package com.mikeyphw.bkx.android.core.remotes

const val BKX_GOOGLE_DRIVE_REMOTE_NAME = "bkx-google-drive"

data class RcloneRemoteProfile(
    val name: String,
    val type: String,
    val managedByBkx: Boolean = name == BKX_GOOGLE_DRIVE_REMOTE_NAME,
) {
    val rootUri: String get() = "rclone://$name/"
}

data class RcloneConfigSnapshot(
    val configured: Boolean,
    val profiles: List<RcloneRemoteProfile>,
    val ignoredRemoteNames: List<String> = emptyList(),
)
