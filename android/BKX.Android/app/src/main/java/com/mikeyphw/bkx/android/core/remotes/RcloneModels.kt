package com.mikeyphw.bkx.android.core.remotes

data class RcloneRemoteProfile(
    val name: String,
    val type: String,
) {
    val rootUri: String get() = "rclone://$name/"
}

data class RcloneConfigSnapshot(
    val configured: Boolean,
    val profiles: List<RcloneRemoteProfile>,
    val ignoredRemoteNames: List<String> = emptyList(),
)
