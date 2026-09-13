package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.drive.GoogleDriveConnectionStatus
import com.mikeyphw.bkx.android.core.drive.userMessage

enum class BkxProductAction {
    ReviewBackup,
    StartBackup,
    PreviewTransfer,
    RunTransfer,
    PreviewRestore,
    ExecuteRestore,
    RunSavedJob,
    ReplayRun,
    DestructiveRepositoryAction,
    ConnectGoogleDrive,
    RefreshCatalog,
}

data class BkxActionGate(
    val allowed: Boolean,
    val reasonCode: String? = null,
    val message: String? = null,
) {
    companion object {
        val Allowed = BkxActionGate(allowed = true)
        fun blocked(reasonCode: String, message: String): BkxActionGate = BkxActionGate(false, reasonCode, message)
    }
}

data class BkxTruthSignal(
    val key: String,
    val label: String,
    val detail: String,
    val badge: String,
    val verified: Boolean,
)

data class BkxUiTruthSurface(
    val engine: BkxTruthSignal,
    val googleDrive: BkxTruthSignal,
    val operations: BkxTruthSignal,
)

fun BkxHomeUiState.uiTruthSurface(): BkxUiTruthSurface = BkxUiTruthSurface(
    engine = engineTruthSignal(),
    googleDrive = googleDriveTruthSignal(),
    operations = operationTruthSignal(),
)

fun BkxHomeUiState.actionGate(action: BkxProductAction): BkxActionGate {
    if (action == BkxProductAction.ConnectGoogleDrive) {
        return if (googleDrive.busy) {
            BkxActionGate.blocked("google_drive_busy", "Google Drive authorization is already in progress.")
        } else {
            BkxActionGate.Allowed
        }
    }

    if (readiness !is BackendReadiness.Ready) {
        return BkxActionGate.blocked("backend_unverified", "BKX engine readiness has not been verified yet.")
    }

    val blocksWhileActive = when (action) {
        BkxProductAction.ReviewBackup,
        BkxProductAction.StartBackup,
        BkxProductAction.PreviewTransfer,
        BkxProductAction.RunTransfer,
        BkxProductAction.PreviewRestore,
        BkxProductAction.ExecuteRestore,
        BkxProductAction.RunSavedJob,
        BkxProductAction.ReplayRun,
        BkxProductAction.DestructiveRepositoryAction -> true
        BkxProductAction.ConnectGoogleDrive,
        BkxProductAction.RefreshCatalog -> false
    }
    if (blocksWhileActive && operationRuntime.hasActiveWork) {
        return BkxActionGate.blocked("operation_busy", "Another BKX operation is active. Wait for it to finish or cancel it before starting this action.")
    }
    if (action == BkxProductAction.RefreshCatalog && isRefreshing) {
        return BkxActionGate.blocked("refresh_busy", "BKX is already refreshing this catalog.")
    }
    return BkxActionGate.Allowed
}

private fun BkxHomeUiState.engineTruthSignal(): BkxTruthSignal = when (val ready = readiness) {
    BackendReadiness.Checking -> BkxTruthSignal(
        key = "engine.checking",
        label = "Checking BKX engine",
        detail = "BKX has not finished proving engine readiness yet.",
        badge = "CHECKING",
        verified = false,
    )
    is BackendReadiness.Ready -> BkxTruthSignal(
        key = "engine.ready",
        label = if (ready.isRoot) "Engine verified with root" else "Engine verified",
        detail = if (ready.isRoot) {
            "The embedded BKX ${ready.backendVersion} engine is responding with root filesystem capability."
        } else {
            "The embedded BKX ${ready.backendVersion} engine is responding. Root-only locations stay hidden until root is granted."
        },
        badge = if (ready.isRoot) "ROOT VERIFIED" else "VERIFIED",
        verified = true,
    )
    else -> BkxTruthSignal(
        key = "engine.${ready.code}",
        label = ready.title,
        detail = ready.message,
        badge = "ATTENTION",
        verified = false,
    )
}

private fun BkxHomeUiState.googleDriveTruthSignal(): BkxTruthSignal = when {
    googleDrive.connected -> BkxTruthSignal(
        key = "drive.ready",
        label = "Google Drive verified",
        detail = "Authorization, access token, and BKX managed rclone remote are ready.",
        badge = "DRIVE VERIFIED",
        verified = true,
    )
    googleDrive.status == GoogleDriveConnectionStatus.Connected -> BkxTruthSignal(
        key = "drive.remote_not_ready",
        label = "Google Drive authorization needs remote setup",
        detail = googleDrive.remoteReadiness.userMessage(),
        badge = "ATTENTION",
        verified = false,
    )
    googleDrive.status == GoogleDriveConnectionStatus.Connecting || googleDrive.status == GoogleDriveConnectionStatus.Disconnecting -> BkxTruthSignal(
        key = "drive.working",
        label = "Google Drive update in progress",
        detail = googleDrive.message ?: googleDrive.remoteReadiness.userMessage(),
        badge = "WORKING",
        verified = false,
    )
    googleDrive.status == GoogleDriveConnectionStatus.NeedsConsent -> BkxTruthSignal(
        key = "drive.needs_consent",
        label = "Google Drive approval required",
        detail = googleDrive.message ?: "Approve Google Drive before BKX can create cloud archives.",
        badge = "APPROVAL",
        verified = false,
    )
    googleDrive.status == GoogleDriveConnectionStatus.Error -> BkxTruthSignal(
        key = "drive.error",
        label = "Google Drive needs attention",
        detail = googleDrive.message ?: googleDrive.remoteReadiness.userMessage(),
        badge = "ERROR",
        verified = false,
    )
    else -> BkxTruthSignal(
        key = "drive.off",
        label = "Google Drive not connected",
        detail = googleDrive.message ?: "Connect Google Drive before using BKX cloud destinations.",
        badge = "OFF",
        verified = false,
    )
}

private fun BkxHomeUiState.operationTruthSignal(): BkxTruthSignal = if (operationRuntime.hasActiveWork) {
    BkxTruthSignal(
        key = "operation.busy",
        label = "Operation active",
        detail = "BKX is already running work; duplicate destructive actions are blocked until it finishes or is cancelled.",
        badge = "BUSY",
        verified = false,
    )
} else {
    BkxTruthSignal(
        key = "operation.idle",
        label = "No active operation",
        detail = "No backup, restore, transfer, verify, prune, or replay work is currently active.",
        badge = "IDLE",
        verified = true,
    )
}
