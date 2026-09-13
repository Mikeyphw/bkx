package com.mikeyphw.bkx.android.core.drive

import android.app.PendingIntent

enum class GoogleDriveConnectionStatus {
    Disconnected,
    Connecting,
    NeedsConsent,
    Connected,
    Disconnecting,
    Error,
}

enum class GoogleDriveRemoteReadiness {
    MissingConfiguration,
    MissingManagedRemote,
    MissingAccessToken,
    Ready,
}

data class GoogleDriveUiState(
    val status: GoogleDriveConnectionStatus = GoogleDriveConnectionStatus.Disconnected,
    val pendingConsent: PendingIntent? = null,
    val message: String? = null,
    val lastAuthorizedAtEpochMillis: Long? = null,
    val remoteReadiness: GoogleDriveRemoteReadiness = GoogleDriveRemoteReadiness.MissingConfiguration,
) {
    val connected: Boolean get() = status == GoogleDriveConnectionStatus.Connected && remoteReadiness == GoogleDriveRemoteReadiness.Ready
    val readyForRemoteOperations: Boolean get() = connected
    val busy: Boolean get() = status == GoogleDriveConnectionStatus.Connecting || status == GoogleDriveConnectionStatus.Disconnecting
    val rootUri: String get() = GoogleDriveRcloneConfig.rootUri
}

fun GoogleDriveRemoteReadiness.userMessage(): String = when (this) {
    GoogleDriveRemoteReadiness.MissingConfiguration -> "Google Drive is not connected."
    GoogleDriveRemoteReadiness.MissingManagedRemote -> "BKX's managed Google Drive remote is missing. Reconnect Google Drive before using cloud operations."
    GoogleDriveRemoteReadiness.MissingAccessToken -> "Google Drive access expired or was not stored. Reconnect Google Drive before using cloud operations."
    GoogleDriveRemoteReadiness.Ready -> "Google Drive is ready for BKX."
}
