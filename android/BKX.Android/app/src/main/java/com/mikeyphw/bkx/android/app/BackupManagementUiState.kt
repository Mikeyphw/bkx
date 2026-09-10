package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleDefinition
import com.mikeyphw.bkx.android.core.repositories.RepositoryDefinition
import com.mikeyphw.bkx.android.core.repositories.SnapshotDescriptor
import com.mikeyphw.bkx.android.core.repositories.SnapshotDiffSummary
import com.mikeyphw.bkx.android.core.repositories.SnapshotNodeDescriptor
import com.mikeyphw.bkx.android.core.repositories.SnapshotOverwriteMode
import com.mikeyphw.bkx.android.core.repositories.SnapshotRestoreResult

data class SavedJobsUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val items: List<SavedJob> = emptyList(),
    val actionInProgress: String? = null,
    val lastActionMessage: String? = null,
    val scheduleStatus: ScreenStatus = ScreenStatus.Loading,
    val schedules: List<SavedJobScheduleDefinition> = emptyList(),
    val scheduleActionInProgress: String? = null,
    val scheduleMessage: String? = null,
)

data class RepositoriesUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val items: List<RepositoryDefinition> = emptyList(),
    val actionInProgress: String? = null,
    val messages: Map<String, String> = emptyMap(),
)

data class SnapshotsUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val repositoryId: String? = null,
    val items: List<SnapshotDescriptor> = emptyList(),
    val selectedSnapshot: SnapshotDescriptor? = null,
    val browseStatus: ScreenStatus = ScreenStatus.Empty("Select a snapshot to browse its contents."),
    val browsePath: String = "/",
    val entries: List<SnapshotNodeDescriptor> = emptyList(),
    val selectedRestorePaths: Set<String> = emptySet(),
    val restoreTarget: String = "/storage/emulated/0/BKX/Restores",
    val overwriteMode: SnapshotOverwriteMode = SnapshotOverwriteMode.Never,
    val restorePreview: SnapshotRestoreResult? = null,
    val restoreResult: SnapshotRestoreResult? = null,
    val diff: SnapshotDiffSummary? = null,
    val compareTargetId: String? = null,
    val actionInProgress: String? = null,
    val message: String? = null,
) {
    val canBrowse: Boolean get() = repositoryId != null && selectedSnapshot != null
    val restoreIncludes: List<String> get() = selectedRestorePaths.toList().sorted()
}

