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

data class GoogleDriveUiState(
    val status: GoogleDriveConnectionStatus = GoogleDriveConnectionStatus.Disconnected,
    val pendingConsent: PendingIntent? = null,
    val message: String? = null,
    val lastAuthorizedAtEpochMillis: Long? = null,
) {
    val connected: Boolean get() = status == GoogleDriveConnectionStatus.Connected
    val busy: Boolean get() = status == GoogleDriveConnectionStatus.Connecting || status == GoogleDriveConnectionStatus.Disconnecting
    val rootUri: String get() = GoogleDriveRcloneConfig.rootUri
}
