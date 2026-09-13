package com.mikeyphw.bkx.android.app.navigation

import com.mikeyphw.bkx.android.core.backend.BackendReadiness

enum class BkxDestination(val label: String, val shortLabel: String) {
    Home("Home", "Home"),
    Backup("Backup", "Backup"),
    Archives("Archives & restore", "Archives"),
    Activity("Activity", "Activity"),
    More("More", "More"),
}

sealed interface ScreenStatus {
    data object Loading : ScreenStatus
    data class Empty(val message: String) : ScreenStatus
    data class Error(val title: String, val message: String) : ScreenStatus
    data object Content : ScreenStatus
}

data class BackendToolState(
    val readiness: BackendReadiness,
    val runtimeLabel: String = "Embedded BKX / libsu root when granted",
    val protocolVersion: Int,
    val bkxVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val remediationActions: List<SetupRemediationAction> = readiness.toSetupActions(),
)

data class SetupRemediationAction(
    val code: String,
    val title: String,
    val steps: List<String>,
)

fun BackendReadiness.toSetupActions(): List<SetupRemediationAction> = when (this) {
    BackendReadiness.Checking -> listOf(
        SetupRemediationAction("wait_for_probe", "Checking BKX", listOf("The embedded engine check is still running.")),
    )
    is BackendReadiness.Ready -> emptyList()
    else -> listOf(
        SetupRemediationAction(code = code, title = title, steps = remediationSteps),
    )
}


enum class ArchiveDetailSection { Overview, Contents, Restore }

enum class BackupRoute { Overview, Jobs, Snapshots, Repositories, History }

enum class MoreRoute { Overview, GoogleDrive, Remotes, Transfer, Automation, Diagnostics }

data class BkxRouteKey(
    val destination: BkxDestination,
    val backupRoute: BackupRoute? = null,
    val archiveSection: ArchiveDetailSection? = null,
    val moreRoute: MoreRoute? = null,
) {
    val stableKey: String = listOfNotNull(
        destination.name,
        backupRoute?.name,
        archiveSection?.name,
        moreRoute?.name,
    ).joinToString("/")

    companion object {
        fun from(
            destination: BkxDestination,
            backupRoute: BackupRoute,
            archiveSection: ArchiveDetailSection,
            moreRoute: MoreRoute,
        ): BkxRouteKey = when (destination) {
            BkxDestination.Backup -> BkxRouteKey(destination, backupRoute = backupRoute)
            BkxDestination.Archives -> BkxRouteKey(destination, archiveSection = archiveSection)
            BkxDestination.More -> BkxRouteKey(destination, moreRoute = moreRoute)
            else -> BkxRouteKey(destination)
        }
    }
}

enum class BkxBoundEntityKind(val wireValue: String) {
    Archive("archive"),
    BackupDraft("backup_draft"),
    RestoreDraft("restore_draft"),
    TransferDraft("transfer_draft"),
    Repository("repository"),
    SnapshotRepository("snapshot_repository"),
    Snapshot("snapshot"),
    SnapshotBrowsePath("snapshot_browse_path"),
    Run("run"),
    FilesystemPath("filesystem_path"),
}

data class BkxResponseBinding(
    val requestId: String,
    val routeKey: BkxRouteKey,
    val routeGeneration: Long,
    val entityKind: BkxBoundEntityKind,
    val entityId: String,
    val draftFingerprint: String? = null,
) {
    init {
        require(requestId.isNotBlank()) { "Response binding request id must not be blank." }
        require(routeGeneration >= 0L) { "Response binding route generation must be non-negative." }
        require(entityId.isNotBlank()) { "Response binding entity id must not be blank." }
        require(draftFingerprint == null || draftFingerprint.isNotBlank()) { "Response binding draft fingerprint must not be blank." }
    }

    fun accepts(
        resultRequestId: String,
        currentRouteKey: BkxRouteKey,
        currentRouteGeneration: Long,
        currentEntityId: String?,
        currentDraftFingerprint: String? = null,
    ): Boolean {
        if (requestId != resultRequestId) return false
        if (routeKey.stableKey != currentRouteKey.stableKey) return false
        if (routeGeneration != currentRouteGeneration) return false
        if (entityId != currentEntityId) return false
        if (draftFingerprint != null && draftFingerprint != currentDraftFingerprint) return false
        return true
    }
}

data class BkxDeepLinkTarget(
    val destination: BkxDestination,
    val backupRoute: BackupRoute? = null,
    val moreRoute: MoreRoute? = null,
    val archiveSection: ArchiveDetailSection? = null,
    val entityId: String? = null,
) {
    init {
        if (backupRoute != null) require(destination == BkxDestination.Backup) { "Backup route deep links must target Backup." }
        if (moreRoute != null) require(destination == BkxDestination.More) { "More route deep links must target More." }
        if (archiveSection != null) require(destination == BkxDestination.Archives) { "Archive section deep links must target Archives." }
    }
}

object BkxDeepLinkCodec {
    fun parse(rawUri: String?): BkxDeepLinkTarget? {
        val raw = rawUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutScheme = raw
            .removePrefix("bkx://")
            .removePrefix("https://bkx.local/")
            .trim('/')
        if (withoutScheme.isBlank()) return BkxDeepLinkTarget(BkxDestination.Home)
        val segments = withoutScheme.split('/').filter { it.isNotBlank() }
        val first = segments.firstOrNull()?.lowercase() ?: return BkxDeepLinkTarget(BkxDestination.Home)
        return when (first) {
            "home" -> BkxDeepLinkTarget(BkxDestination.Home)
            "backup" -> BkxDeepLinkTarget(
                destination = BkxDestination.Backup,
                backupRoute = segments.getOrNull(1)?.toBackupRoute() ?: BackupRoute.Overview,
                entityId = segments.getOrNull(2),
            )
            "archives", "archive" -> BkxDeepLinkTarget(
                destination = BkxDestination.Archives,
                archiveSection = segments.getOrNull(1)?.toArchiveSection() ?: ArchiveDetailSection.Overview,
                entityId = segments.getOrNull(2),
            )
            "activity", "runs", "run" -> BkxDeepLinkTarget(BkxDestination.Activity, entityId = segments.getOrNull(1))
            "more" -> BkxDeepLinkTarget(
                destination = BkxDestination.More,
                moreRoute = segments.getOrNull(1)?.toMoreRoute() ?: MoreRoute.Overview,
                entityId = segments.getOrNull(2),
            )
            "transfer" -> BkxDeepLinkTarget(BkxDestination.More, moreRoute = MoreRoute.Transfer)
            "drive", "google-drive" -> BkxDeepLinkTarget(BkxDestination.More, moreRoute = MoreRoute.GoogleDrive)
            else -> null
        }
    }

    private fun String.toBackupRoute(): BackupRoute? = when (lowercase()) {
        "overview" -> BackupRoute.Overview
        "jobs", "saved-jobs" -> BackupRoute.Jobs
        "snapshots" -> BackupRoute.Snapshots
        "repositories", "repos" -> BackupRoute.Repositories
        "history", "runs" -> BackupRoute.History
        else -> null
    }

    private fun String.toMoreRoute(): MoreRoute? = when (lowercase()) {
        "overview" -> MoreRoute.Overview
        "google-drive", "drive" -> MoreRoute.GoogleDrive
        "remotes", "rclone" -> MoreRoute.Remotes
        "transfer", "transfers" -> MoreRoute.Transfer
        "automation", "tasker" -> MoreRoute.Automation
        "diagnostics" -> MoreRoute.Diagnostics
        else -> null
    }

    private fun String.toArchiveSection(): ArchiveDetailSection? = when (lowercase()) {
        "overview", "details" -> ArchiveDetailSection.Overview
        "contents", "browse" -> ArchiveDetailSection.Contents
        "restore" -> ArchiveDetailSection.Restore
        else -> null
    }
}
