package com.mikeyphw.bkx.android.app

import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.bkx.android.app.navigation.BackendToolState
import com.mikeyphw.bkx.android.app.navigation.ArchiveDetailSection
import com.mikeyphw.bkx.android.app.navigation.BackupRoute
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.MoreRoute
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
import com.mikeyphw.bkx.android.app.drive.GoogleDriveAuthorizationManager
import com.mikeyphw.bkx.android.app.drive.GoogleDriveAuthorizationOutcome
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator.OperationOutcome
import com.mikeyphw.bkx.android.core.archives.ArchiveBrowsePage
import com.mikeyphw.bkx.android.core.archives.ArchiveBrowsePageRequest
import com.mikeyphw.bkx.android.core.archives.ArchiveEntrySummary
import com.mikeyphw.bkx.android.core.archives.ArchiveEntryType
import com.mikeyphw.bkx.android.core.archives.ArchiveInfoSummary
import com.mikeyphw.bkx.android.core.archives.ArchiveListItem
import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.archives.ArchiveReadOnlyUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveRequestBuilder
import com.mikeyphw.bkx.android.core.archives.ArchiveWorkflowUiState
import com.mikeyphw.bkx.android.core.archives.toArchiveVerificationUiState
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.backup.BackupPlanPreview
import com.mikeyphw.bkx.android.core.backup.BackupPlanWarning
import com.mikeyphw.bkx.android.core.backup.BackupPlanningUiState
import com.mikeyphw.bkx.android.core.backup.BackupRequestBuilder
import com.mikeyphw.bkx.android.core.backup.BackupVerificationExpectation
import com.mikeyphw.bkx.android.core.backup.BackupWarningSeverity
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.drive.GoogleDriveConnectionStatus
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRemoteReadiness
import com.mikeyphw.bkx.android.core.drive.userMessage
import com.mikeyphw.bkx.android.core.drive.GoogleDriveUiState
import com.mikeyphw.bkx.android.core.files.FilesystemBrowserEntry
import com.mikeyphw.bkx.android.core.files.FilesystemBrowserUiState
import com.mikeyphw.bkx.android.core.files.FilesystemEntryKind
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.jobs.SavedJobBridgeCodec
import com.mikeyphw.bkx.android.core.jobs.SavedJobDraft
import com.mikeyphw.bkx.android.core.jobs.SavedJobRequestBuilder
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleBridgeCodec
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleDefinition
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleRequestBuilder
import com.mikeyphw.bkx.android.core.files.ProviderStagingPort
import com.mikeyphw.bkx.android.core.operations.OperationClassifier
import com.mikeyphw.bkx.android.core.operations.OperationFailureMapper
import com.mikeyphw.bkx.android.core.operations.OperationFailureKind
import com.mikeyphw.bkx.android.core.operations.OperationFailureUiState
import com.mikeyphw.bkx.android.core.operations.OperationProgressTracker
import com.mikeyphw.bkx.android.core.operations.OperationReconnectSnapshot
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.core.operations.OperationRuntimeState
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.operations.toOperationResultUiState
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.restore.RestoreDraft
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestorePreviewSummary
import com.mikeyphw.bkx.android.core.restore.RestoreRequestBuilder
import com.mikeyphw.bkx.android.core.restore.RestoreUiState
import com.mikeyphw.bkx.android.core.restore.toRestorePreviewSummary
import com.mikeyphw.bkx.android.core.repositories.RepositoryBridgeCodec
import com.mikeyphw.bkx.android.core.repositories.RepositoryCreateSpec
import com.mikeyphw.bkx.android.core.repositories.RepositoryDefinition
import com.mikeyphw.bkx.android.core.repositories.RepositoryRequestBuilder
import com.mikeyphw.bkx.android.core.repositories.RetentionPolicy
import com.mikeyphw.bkx.android.core.repositories.SnapshotBridgeCodec
import com.mikeyphw.bkx.android.core.repositories.SnapshotDescriptor
import com.mikeyphw.bkx.android.core.repositories.SnapshotOverwriteMode
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationPolicy
import com.mikeyphw.bkx.android.core.runs.RunHistoryBridgeCodec
import com.mikeyphw.bkx.android.core.runs.RunHistoryRequestBuilder
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.BkxSettings
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferPreviewSummary
import com.mikeyphw.bkx.android.core.transfer.TransferRequestBuilder
import com.mikeyphw.bkx.android.core.transfer.toTransferPreviewSummary
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import com.mikeyphw.bkx.android.core.transfer.TransferUiState
import com.mikeyphw.bkx.android.core.transfer.toTransferResultUiState
import com.mikeyphw.bkx.android.core.usecase.CheckBackendReadinessUseCase
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class OperationSurfaceFeedback(
    val requestId: String,
    val operation: String,
    val startedAtEpochMillis: Long,
    val result: OperationResultUiState = OperationResultUiState.NotStarted,
    val completedAtEpochMillis: Long? = null,
)

data class BkxUiMessage(
    val id: Long,
    val text: String,
)

data class BkxHomeUiState(
    val title: String = "BKX",
    val readiness: BackendReadiness = BackendReadiness.Checking,
    val isRefreshing: Boolean = false,
    val selectedDestination: BkxDestination = BkxDestination.Home,
    val destinations: List<BkxDestination> = BkxDestination.entries,
    val backupRoute: BackupRoute = BackupRoute.Overview,
    val savedJobs: SavedJobsUiState = SavedJobsUiState(),
    val repositories: RepositoriesUiState = RepositoriesUiState(),
    val snapshots: SnapshotsUiState = SnapshotsUiState(),
    val archiveDetailSection: ArchiveDetailSection = ArchiveDetailSection.Overview,
    val moreRoute: MoreRoute = MoreRoute.Overview,
    val recentRuns: List<RunSummary> = emptyList(),
    val backupPlanning: BackupPlanningUiState = BackupPlanningUiState(),
    val archiveReadOnly: ArchiveReadOnlyUiState = ArchiveReadOnlyUiState(),
    val restore: RestoreUiState = RestoreUiState(),
    val transfer: TransferUiState = TransferUiState(),
    val operationRuntime: OperationRuntimeState = OperationRuntimeState(),
    val operationResult: OperationResultUiState = OperationResultUiState.NotStarted,
    val operationFeedback: Map<BkxDestination, OperationSurfaceFeedback> = emptyMap(),
    val uiMessage: BkxUiMessage? = null,
    val filesystemBrowser: FilesystemBrowserUiState = FilesystemBrowserUiState(),
    val providerImportInProgress: Boolean = false,
    val providerImportMessage: String? = null,
    val googleDrive: GoogleDriveUiState = GoogleDriveUiState(),
    val settings: BkxSettings = BkxSettings(),
    val screenStatuses: Map<BkxDestination, ScreenStatus> = initialScreenStatuses(),
) {
    val backendToolState: BackendToolState = BackendToolState(
        readiness = readiness,
        runtimeLabel = when (val ready = readiness as? BackendReadiness.Ready) {
            null -> "Embedded BKX"
            else -> if (ready.isRoot) "Embedded BKX · root" else "Embedded BKX · app privileges"
        },
        protocolVersion = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
        bkxVersion = (readiness as? BackendReadiness.Ready)?.backendVersion,
        capabilities = (readiness as? BackendReadiness.Ready)?.capabilities?.sorted().orEmpty(),
    )

    val rootAvailable: Boolean get() = (readiness as? BackendReadiness.Ready)?.isRoot == true
    val activeOperationCount: Int get() = operationRuntime.activeWork().size
}

sealed interface BkxHomeEvent {
    data object RefreshBackend : BkxHomeEvent
    data object RefreshRuns : BkxHomeEvent
    data class SelectDestination(val destination: BkxDestination) : BkxHomeEvent
    data class ShowArchiveSection(val section: ArchiveDetailSection) : BkxHomeEvent
    data object CloseArchiveDetails : BkxHomeEvent
    data class OpenMoreRoute(val route: MoreRoute) : BkxHomeEvent
    data object CloseMoreRoute : BkxHomeEvent

    data class OpenBackupRoute(val route: BackupRoute) : BkxHomeEvent
    data object RefreshSavedJobs : BkxHomeEvent
    data object RefreshRepositories : BkxHomeEvent
    data class CreateSavedJob(val draft: SavedJobDraft) : BkxHomeEvent
    data class UpdateSavedJob(val jobId: String, val expectedRevision: Long, val draft: SavedJobDraft) : BkxHomeEvent
    data class CloneSavedJob(val sourceJobId: String, val targetJobId: String, val name: String) : BkxHomeEvent
    data class DeleteSavedJob(val jobId: String) : BkxHomeEvent
    data class PlanSavedJob(val jobId: String) : BkxHomeEvent
    data class RunSavedJob(val jobId: String) : BkxHomeEvent
    data class ReplayRun(val runId: String, val jobId: String? = null) : BkxHomeEvent
    data object RefreshSavedJobSchedules : BkxHomeEvent
    data class PutSavedJobSchedule(val schedule: SavedJobScheduleDefinition) : BkxHomeEvent
    data class SetSavedJobScheduleEnabled(val scheduleId: String, val enabled: Boolean) : BkxHomeEvent
    data class DeleteSavedJobSchedule(val scheduleId: String) : BkxHomeEvent
    data class AddRepository(val spec: RepositoryCreateSpec) : BkxHomeEvent
    data class InitializeRepository(val repositoryId: String) : BkxHomeEvent
    data class CheckRepository(val repositoryId: String) : BkxHomeEvent
    data class PreviewRepositoryPrune(val repositoryId: String) : BkxHomeEvent
    data class PruneRepository(val repositoryId: String) : BkxHomeEvent
    data class SetRepositoryRetention(val repositoryId: String, val policy: RetentionPolicy) : BkxHomeEvent
    data class PreviewRetentionApply(val repositoryId: String) : BkxHomeEvent
    data class ApplyRetention(val repositoryId: String) : BkxHomeEvent
    data class RemoveRepository(val repositoryId: String) : BkxHomeEvent

    data class SelectSnapshotRepository(val repositoryId: String) : BkxHomeEvent
    data object RefreshSnapshots : BkxHomeEvent
    data class SelectSnapshot(val snapshot: SnapshotDescriptor) : BkxHomeEvent
    data class BrowseSnapshotPath(val path: String) : BkxHomeEvent
    data class ToggleSnapshotRestorePath(val path: String) : BkxHomeEvent
    data object ClearSnapshotRestorePaths : BkxHomeEvent
    data class SetSnapshotRestoreTarget(val path: String) : BkxHomeEvent
    data class SetSnapshotOverwriteMode(val mode: SnapshotOverwriteMode) : BkxHomeEvent
    data object CompareSnapshotWithPrevious : BkxHomeEvent
    data object PreviewSnapshotRestore : BkxHomeEvent
    data object ExecuteSnapshotRestore : BkxHomeEvent
    data class ForgetSnapshot(val snapshotId: String) : BkxHomeEvent

    data class UpdateBackupDraft(val draft: BackupPlanDraft) : BkxHomeEvent
    data class ApplyBackupPlanPreview(val preview: BackupPlanPreview) : BkxHomeEvent
    data object RequestBackupPlan : BkxHomeEvent
    data object CreateBackup : BkxHomeEvent

    data class SelectArchive(val archive: ArchiveLocation) : BkxHomeEvent
    data class ApplyArchiveList(val archives: List<ArchiveListItem>) : BkxHomeEvent
    data class ApplyArchiveInfo(val info: ArchiveInfoSummary) : BkxHomeEvent
    data class ApplyArchiveBrowsePage(val page: ArchiveBrowsePage) : BkxHomeEvent
    data class ApplyArchiveVerification(val result: BridgeResultEnvelope) : BkxHomeEvent
    data object RefreshArchives : BkxHomeEvent
    data class LoadArchiveDetails(val archive: ArchiveLocation) : BkxHomeEvent
    data class BrowseArchive(val archive: ArchiveLocation, val pageToken: String? = null) : BkxHomeEvent
    data class VerifyArchive(val archive: ArchiveLocation) : BkxHomeEvent
    data class PrepareRestore(val archive: ArchiveLocation) : BkxHomeEvent

    data class UpdateRestoreDraft(val draft: RestoreDraft) : BkxHomeEvent
    data class ApplyRestorePreview(val preview: RestorePreviewSummary) : BkxHomeEvent
    data class ConfirmRestoreExtraction(val confirmation: RestoreExtractionConfirmation) : BkxHomeEvent
    data object RequestRestorePreview : BkxHomeEvent
    data object ExecuteRestore : BkxHomeEvent

    data class UpdateTransferDraft(val draft: TransferTopologyDraft) : BkxHomeEvent
    data class ApplyTransferPreview(val preview: TransferPreviewSummary) : BkxHomeEvent
    data class ApplyTransferResult(val result: BridgeResultEnvelope) : BkxHomeEvent
    data object RequestTransferPreview : BkxHomeEvent
    data object RunTransfer : BkxHomeEvent

    data class OpenFilesystemBrowser(val target: FilesystemSelectionTarget, val initialPath: String) : BkxHomeEvent
    data class NavigateFilesystem(val path: String) : BkxHomeEvent
    data object ToggleFilesystemHidden : BkxHomeEvent
    data class SelectFilesystemPath(val path: String, val isDirectory: Boolean) : BkxHomeEvent
    data object CloseFilesystemBrowser : BkxHomeEvent
    data class ImportProviderBackupTree(val uri: String) : BkxHomeEvent
    data class ImportProviderArchive(val uri: String) : BkxHomeEvent
    data class ImportProviderTransferTree(val uri: String) : BkxHomeEvent

    data object ConnectGoogleDrive : BkxHomeEvent
    data object RefreshGoogleDrive : BkxHomeEvent
    data object GoogleDriveConsentLaunched : BkxHomeEvent
    data class CompleteGoogleDriveAuthorization(val data: Intent?) : BkxHomeEvent
    data object DisconnectGoogleDrive : BkxHomeEvent

    data class UpdateSettings(val settings: BkxSettings) : BkxHomeEvent
    data class ConsumeUiMessage(val id: Long) : BkxHomeEvent

    data class TrackOperationStarted(
        val requestId: String,
        val operation: String,
        val startedAtEpochMillis: Long,
        val cancelFilePath: String? = null,
    ) : BkxHomeEvent
    data class ConsumeOperationProgress(val progress: BridgeProgressEnvelope, val observedAtEpochMillis: Long) : BkxHomeEvent
    data class ApplyOperationFailure(val requestId: String, val failure: OperationFailureUiState, val failedAtEpochMillis: Long) : BkxHomeEvent
    data class RequestOperationCancellation(val requestId: String, val cancelRequestId: String, val requestedAtEpochMillis: Long) : BkxHomeEvent
    data class SubmitOperationCancellation(
        val requestId: String,
        val cancelFilePath: String,
        val cancelRequestId: String,
        val requestedAtEpochMillis: Long,
    ) : BkxHomeEvent
    data class ReconnectOperations(val snapshot: OperationReconnectSnapshot) : BkxHomeEvent
    data class FocusActiveOperation(val requestId: String) : BkxHomeEvent
}

class BkxAppViewModel(
    private val backendPort: BackendPort,
    private val runSummaryStore: RunSummaryStore,
    private val operationStateStore: OperationStateStore,
    private val settingsStore: SettingsStore,
    private val providerStagingPort: ProviderStagingPort? = null,
    private val googleDriveAuthorizationManager: GoogleDriveAuthorizationManager? = null,
    private val archiveRootsProvider: (Boolean) -> List<String> = { emptyList() },
    private val requestBaseDirectoryPath: String = defaultRequestBaseDirectory(),
    private val operationCoordinator: BkxOperationCoordinator = BkxOperationCoordinator(
        context = null,
        backendPort = backendPort,
        runSummaryStore = runSummaryStore,
        operationStateStore = operationStateStore,
        requestBaseDirectoryPath = requestBaseDirectoryPath,
    ),
) : ViewModel() {
    private val checkBackendReadiness = CheckBackendReadinessUseCase(backendPort)
    private val operationProgressTracker = OperationProgressTracker()
    private val backupRequestBuilder = BackupRequestBuilder()
    private val archiveRequestBuilder = ArchiveRequestBuilder()
    private val restoreRequestBuilder = RestoreRequestBuilder()
    private val transferRequestBuilder = TransferRequestBuilder()
    private val savedJobRequestBuilder = SavedJobRequestBuilder
    private val savedJobScheduleRequestBuilder = SavedJobScheduleRequestBuilder
    private val repositoryRequestBuilder = RepositoryRequestBuilder
    private val requestSequence = AtomicLong(0L)
    private val messageSequence = AtomicLong(0L)
    private val _state = MutableStateFlow(BkxHomeUiState())
    val state: StateFlow<BkxHomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            operationCoordinator.runtimeState.collect { runtime ->
                _state.update { current ->
                    val focus = current.operationRuntime.focusedRequestId
                    current.copy(operationRuntime = if (focus == null) runtime else runtime.copy(focusedRequestId = focus))
                }
            }
        }
        refreshGoogleDriveAuthorization(allowConsent = false)
        refreshBackend()
        // Startup is cache-first. Canonical Go history sync is triggered by explicit/history/post-operation refreshes.
        publishCachedRuns()
        refreshOperations()
        refreshSettings()
        refreshSavedJobs()
        refreshSavedJobSchedules()
        refreshRepositories()
    }

    fun onEvent(event: BkxHomeEvent) {
        when (event) {
            BkxHomeEvent.RefreshBackend -> refreshBackend()
            BkxHomeEvent.RefreshRuns -> refreshRuns()
            is BkxHomeEvent.SelectDestination -> _state.update { current ->
                current.copy(
                    selectedDestination = event.destination,
                    moreRoute = if (event.destination == BkxDestination.More) current.moreRoute else MoreRoute.Overview,
                )
            }
            is BkxHomeEvent.ShowArchiveSection -> _state.update { it.copy(archiveDetailSection = event.section) }
            BkxHomeEvent.CloseArchiveDetails -> _state.update { current ->
                current.copy(
                    archiveReadOnly = current.archiveReadOnly.copy(
                        selectedArchive = null,
                        infoState = ArchiveWorkflowUiState.Idle,
                        browsePage = null,
                        verification = com.mikeyphw.bkx.android.core.archives.ArchiveVerificationUiState.NotStarted,
                    ),
                    restore = RestoreUiState(),
                    archiveDetailSection = ArchiveDetailSection.Overview,
                )
            }
            is BkxHomeEvent.OpenMoreRoute -> _state.update { it.copy(moreRoute = event.route) }
            BkxHomeEvent.CloseMoreRoute -> _state.update { it.copy(moreRoute = MoreRoute.Overview) }

            is BkxHomeEvent.OpenBackupRoute -> {
                _state.update { it.copy(backupRoute = event.route) }
                if (event.route == BackupRoute.Snapshots) ensureSnapshotRepositoryAndRefresh()
                if (event.route == BackupRoute.History) refreshRuns()
            }
            BkxHomeEvent.RefreshSavedJobs -> refreshSavedJobs()
            BkxHomeEvent.RefreshRepositories -> refreshRepositories()
            is BkxHomeEvent.CreateSavedJob -> createSavedJob(event.draft)
            is BkxHomeEvent.UpdateSavedJob -> updateSavedJob(event.jobId, event.expectedRevision, event.draft)
            is BkxHomeEvent.CloneSavedJob -> cloneSavedJob(event.sourceJobId, event.targetJobId, event.name)
            is BkxHomeEvent.DeleteSavedJob -> deleteSavedJob(event.jobId)
            is BkxHomeEvent.PlanSavedJob -> executeSavedJob(event.jobId, plan = true)
            is BkxHomeEvent.RunSavedJob -> executeSavedJob(event.jobId, plan = false)
            is BkxHomeEvent.ReplayRun -> replayRun(event.runId, event.jobId)
            BkxHomeEvent.RefreshSavedJobSchedules -> refreshSavedJobSchedules()
            is BkxHomeEvent.PutSavedJobSchedule -> putSavedJobSchedule(event.schedule)
            is BkxHomeEvent.SetSavedJobScheduleEnabled -> setSavedJobScheduleEnabled(event.scheduleId, event.enabled)
            is BkxHomeEvent.DeleteSavedJobSchedule -> deleteSavedJobSchedule(event.scheduleId)
            is BkxHomeEvent.AddRepository -> addRepository(event.spec)
            is BkxHomeEvent.InitializeRepository -> initializeRepository(event.repositoryId)
            is BkxHomeEvent.CheckRepository -> checkRepository(event.repositoryId)
            is BkxHomeEvent.PreviewRepositoryPrune -> pruneRepository(event.repositoryId, dryRun = true)
            is BkxHomeEvent.PruneRepository -> pruneRepository(event.repositoryId, dryRun = false)
            is BkxHomeEvent.SetRepositoryRetention -> setRepositoryRetention(event.repositoryId, event.policy)
            is BkxHomeEvent.PreviewRetentionApply -> applyRetention(event.repositoryId, dryRun = true)
            is BkxHomeEvent.ApplyRetention -> applyRetention(event.repositoryId, dryRun = false)
            is BkxHomeEvent.RemoveRepository -> removeRepository(event.repositoryId)

            is BkxHomeEvent.SelectSnapshotRepository -> selectSnapshotRepository(event.repositoryId)
            BkxHomeEvent.RefreshSnapshots -> refreshSnapshots()
            is BkxHomeEvent.SelectSnapshot -> selectSnapshot(event.snapshot)
            is BkxHomeEvent.BrowseSnapshotPath -> browseSnapshotPath(event.path)
            is BkxHomeEvent.ToggleSnapshotRestorePath -> toggleSnapshotRestorePath(event.path)
            BkxHomeEvent.ClearSnapshotRestorePaths -> _state.update { current -> current.copy(snapshots = current.snapshots.copy(selectedRestorePaths = emptySet(), restorePreview = null, restoreResult = null)) }
            is BkxHomeEvent.SetSnapshotRestoreTarget -> setSnapshotRestoreTarget(event.path)
            is BkxHomeEvent.SetSnapshotOverwriteMode -> _state.update { current -> current.copy(snapshots = current.snapshots.copy(overwriteMode = event.mode, restorePreview = null)) }
            BkxHomeEvent.CompareSnapshotWithPrevious -> compareSnapshotWithPrevious()
            BkxHomeEvent.PreviewSnapshotRestore -> runSnapshotRestore(dryRun = true)
            BkxHomeEvent.ExecuteSnapshotRestore -> runSnapshotRestore(dryRun = false)
            is BkxHomeEvent.ForgetSnapshot -> forgetSnapshot(event.snapshotId)

            is BkxHomeEvent.UpdateBackupDraft -> _state.update { current ->
                current.copy(backupPlanning = BackupPlanningUiState(draft = event.draft))
            }
            is BkxHomeEvent.ApplyBackupPlanPreview -> _state.update { current ->
                current.copy(backupPlanning = BackupPlanningUiState.withPreview(current.backupPlanning.draft, event.preview))
            }
            BkxHomeEvent.RequestBackupPlan -> requestBackupPlan()
            BkxHomeEvent.CreateBackup -> createBackup()

            is BkxHomeEvent.SelectArchive -> _state.update { current ->
                current.copy(
                    archiveReadOnly = current.archiveReadOnly.copy(selectedArchive = event.archive),
                    archiveDetailSection = ArchiveDetailSection.Overview,
                )
            }
            is BkxHomeEvent.ApplyArchiveList -> _state.update { current ->
                current.copy(archiveReadOnly = current.archiveReadOnly.copy(listState = ArchiveWorkflowUiState.Content(event.archives)))
            }
            is BkxHomeEvent.ApplyArchiveInfo -> _state.update { current ->
                current.copy(
                    archiveReadOnly = current.archiveReadOnly.copy(
                        selectedArchive = event.info.archive,
                        infoState = ArchiveWorkflowUiState.Content(event.info),
                    ),
                )
            }
            is BkxHomeEvent.ApplyArchiveBrowsePage -> _state.update { current ->
                current.copy(archiveReadOnly = current.archiveReadOnly.copy(browsePage = event.page))
            }
            is BkxHomeEvent.ApplyArchiveVerification -> _state.update { current ->
                current.copy(archiveReadOnly = current.archiveReadOnly.copy(verification = event.result.toArchiveVerificationUiState()))
            }
            BkxHomeEvent.RefreshArchives -> refreshArchives()
            is BkxHomeEvent.LoadArchiveDetails -> loadArchiveDetails(event.archive)
            is BkxHomeEvent.BrowseArchive -> {
                _state.update { it.copy(archiveDetailSection = ArchiveDetailSection.Contents) }
                browseArchive(event.archive, event.pageToken)
            }
            is BkxHomeEvent.VerifyArchive -> verifyArchive(event.archive)
            is BkxHomeEvent.PrepareRestore -> prepareRestore(event.archive)

            is BkxHomeEvent.UpdateRestoreDraft -> _state.update { current ->
                current.copy(restore = RestoreUiState(draft = event.draft))
            }
            is BkxHomeEvent.ApplyRestorePreview -> _state.update { current ->
                current.copy(restore = current.restore.copy(preview = event.preview, confirmation = null))
            }
            is BkxHomeEvent.ConfirmRestoreExtraction -> _state.update { current ->
                current.copy(restore = current.restore.copy(confirmation = event.confirmation))
            }
            BkxHomeEvent.RequestRestorePreview -> requestRestorePreview()
            BkxHomeEvent.ExecuteRestore -> executeRestore()

            is BkxHomeEvent.UpdateTransferDraft -> _state.update { current ->
                current.copy(transfer = TransferUiState(draft = event.draft))
            }
            is BkxHomeEvent.ApplyTransferPreview -> _state.update { current ->
                current.copy(transfer = current.transfer.copy(preview = event.preview))
            }
            is BkxHomeEvent.ApplyTransferResult -> _state.update { current ->
                current.copy(transfer = current.transfer.copy(result = event.result.toTransferResultUiState()))
            }
            BkxHomeEvent.RequestTransferPreview -> requestTransferPreview()
            BkxHomeEvent.RunTransfer -> runTransfer()

            is BkxHomeEvent.OpenFilesystemBrowser -> openFilesystemBrowser(event.target, event.initialPath)
            is BkxHomeEvent.NavigateFilesystem -> loadFilesystem(event.path)
            BkxHomeEvent.ToggleFilesystemHidden -> {
                _state.update { current -> current.copy(filesystemBrowser = current.filesystemBrowser.copy(showHidden = !current.filesystemBrowser.showHidden)) }
                loadFilesystem(_state.value.filesystemBrowser.currentPath)
            }
            is BkxHomeEvent.SelectFilesystemPath -> selectFilesystemPath(event.path, event.isDirectory)
            BkxHomeEvent.CloseFilesystemBrowser -> _state.update { it.copy(filesystemBrowser = FilesystemBrowserUiState()) }
            is BkxHomeEvent.ImportProviderBackupTree -> importProviderBackupTree(event.uri)
            is BkxHomeEvent.ImportProviderArchive -> importProviderArchive(event.uri)
            is BkxHomeEvent.ImportProviderTransferTree -> importProviderTransferTree(event.uri)

            BkxHomeEvent.ConnectGoogleDrive -> refreshGoogleDriveAuthorization(allowConsent = true)
            BkxHomeEvent.RefreshGoogleDrive -> refreshGoogleDriveAuthorization(allowConsent = false)
            BkxHomeEvent.GoogleDriveConsentLaunched -> _state.update { current ->
                current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Connecting, pendingConsent = null, message = "Waiting for Google Drive authorization…"))
            }
            is BkxHomeEvent.CompleteGoogleDriveAuthorization -> completeGoogleDriveAuthorization(event.data)
            BkxHomeEvent.DisconnectGoogleDrive -> disconnectGoogleDrive()

            is BkxHomeEvent.UpdateSettings -> updateSettings(event.settings)
            is BkxHomeEvent.ConsumeUiMessage -> _state.update { current ->
                if (current.uiMessage?.id == event.id) current.copy(uiMessage = null) else current
            }

            is BkxHomeEvent.TrackOperationStarted -> updateOperationRuntime { runtime ->
                operationProgressTracker.trackStarted(
                    state = runtime,
                    requestId = event.requestId,
                    operation = event.operation,
                    startedAtEpochMillis = event.startedAtEpochMillis,
                    cancelFilePath = event.cancelFilePath,
                )
            }
            is BkxHomeEvent.ConsumeOperationProgress -> updateOperationRuntime { runtime ->
                operationProgressTracker.consume(runtime, event.progress, event.observedAtEpochMillis)
            }
            is BkxHomeEvent.ApplyOperationFailure -> {
                updateOperationRuntime { runtime ->
                    operationProgressTracker.fail(runtime, event.requestId, event.failure, event.failedAtEpochMillis)
                }
                _state.update { current -> current.copy(operationResult = OperationResultUiState.Failed(event.failure)) }
            }
            is BkxHomeEvent.RequestOperationCancellation -> updateOperationRuntime { runtime ->
                operationProgressTracker.markCancelling(runtime, event.requestId, event.cancelRequestId, event.requestedAtEpochMillis)
            }
            is BkxHomeEvent.SubmitOperationCancellation -> submitOperationCancellation(event)
            is BkxHomeEvent.ReconnectOperations -> updateOperationRuntime {
                operationProgressTracker.reconnect(event.snapshot)
            }
            is BkxHomeEvent.FocusActiveOperation -> updateOperationRuntimeAndSelectActivity { runtime ->
                operationProgressTracker.focus(runtime, event.requestId)
            }
        }
    }

    private fun requestBackupPlan() {
        val draft = _state.value.backupPlanning.draft
        if (draft.validationIssues().isNotEmpty()) return
        val request = backupRequestBuilder.buildPlanRequest(nextRequestId("backup-plan"), draft)
        executeRequest(request, recordInHistory = false) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val token = result.artifacts.firstOrNull { it.label == "backup_plan" }?.sha256 ?: result.requestId
                val preview = BackupPlanPreview(
                    requestId = result.requestId,
                    planToken = token,
                    draftFingerprint = draft.fingerprint(),
                    filesTotal = result.metrics?.filesTotal,
                    bytesTotal = result.metrics?.bytesTotal,
                    warnings = result.errors.map {
                        BackupPlanWarning(it.code, it.message, BackupWarningSeverity.Warning)
                    },
                    verificationExpectations = if (draft.verification.verifyAfterCreate) {
                        listOf(BackupVerificationExpectation("sha256", "Verify the completed archive before reporting success."))
                    } else {
                        emptyList()
                    },
                )
                _state.update { current -> current.copy(backupPlanning = BackupPlanningUiState.withPreview(draft, preview)) }
            }
        }
    }

    private fun createBackup() {
        val planning = _state.value.backupPlanning
        if (!planning.canCreateBackup) return
        val request = backupRequestBuilder.buildCreateRequest(nextRequestId("backup-create"), planning.draft, planning.preview)
        executeRequest(request, recordInHistory = true, feedbackDestination = BkxDestination.Backup) { result ->
            if (result.status == BridgeResultStatus.Success) refreshArchives()
        }
    }

    private fun refreshArchives() {
        val roots = buildList {
            addAll(archiveRootsProvider(_state.value.rootAvailable))
            if (_state.value.googleDrive.connected) add(GoogleDriveRcloneConfig.rootUri)
        }
        val request = archiveRequestBuilder.buildListRequest(nextRequestId("archives-list"), roots = roots)
        _state.update { current ->
            current.copy(
                archiveReadOnly = current.archiveReadOnly.copy(listState = ArchiveWorkflowUiState.Loading),
                screenStatuses = current.screenStatuses + (BkxDestination.Archives to ScreenStatus.Loading),
            )
        }
        executeRequest(request, recordInHistory = false, deliverFailureResults = true) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val archives = result.artifacts
                    .filter { it.label == "archive" && (it.path.startsWith("/") || it.path.startsWith("rclone://", ignoreCase = true)) }
                    .distinctBy { it.path }
                    .map { artifact ->
                        ArchiveListItem(
                            archive = archiveLocation(artifact.path),
                            sizeBytes = artifact.sizeBytes,
                            createdAtEpochMillis = artifact.modifiedAtEpochMillis,
                            verification = com.mikeyphw.bkx.android.core.archives.ArchiveVerificationUiState.NotStarted,
                        )
                    }
                _state.update { current ->
                    current.copy(
                        archiveReadOnly = current.archiveReadOnly.copy(listState = ArchiveWorkflowUiState.Content(archives)),
                        screenStatuses = current.screenStatuses + (
                            BkxDestination.Archives to if (archives.isEmpty()) {
                                ScreenStatus.Empty("No BKX archives have been indexed yet.")
                            } else {
                                ScreenStatus.Content
                            }
                        ),
                    )
                }
            } else {
                _state.update { current ->
                    current.copy(
                        archiveReadOnly = current.archiveReadOnly.copy(
                            listState = ArchiveWorkflowUiState.Failed(result.errors, result.summary),
                        ),
                        screenStatuses = current.screenStatuses + (
                            BkxDestination.Archives to ScreenStatus.Error("Could not load archives", result.summary)
                        ),
                    )
                }
            }
        }
    }

    private fun loadArchiveDetails(archive: ArchiveLocation) {
        _state.update { current ->
            current.copy(
                archiveReadOnly = current.archiveReadOnly.copy(
                    selectedArchive = archive,
                    infoState = ArchiveWorkflowUiState.Loading,
                    browsePage = null,
                ),
                archiveDetailSection = ArchiveDetailSection.Overview,
            )
        }
        val request = archiveRequestBuilder.buildInfoRequest(nextRequestId("archive-info"), archive)
        executeRequest(request, recordInHistory = false, deliverFailureResults = true) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val match = Regex("Archive: (\\d+) entries, (\\d+) uncompressed bytes, format ([^.]+)").find(result.summary)
                val info = ArchiveInfoSummary(
                    archive = archive,
                    entryCount = match?.groupValues?.getOrNull(1)?.toLongOrNull(),
                    uncompressedSizeBytes = match?.groupValues?.getOrNull(2)?.toLongOrNull(),
                    formatVersion = match?.groupValues?.getOrNull(3)?.trim(),
                    warnings = result.errors.map { it.message },
                )
                _state.update { current ->
                    current.copy(archiveReadOnly = current.archiveReadOnly.copy(infoState = ArchiveWorkflowUiState.Content(info)))
                }
            } else {
                _state.update { current ->
                    current.copy(archiveReadOnly = current.archiveReadOnly.copy(infoState = ArchiveWorkflowUiState.Failed(result.errors, result.summary)))
                }
            }
        }
    }

    private fun browseArchive(archive: ArchiveLocation, pageToken: String?) {
        val requestPage = ArchiveBrowsePageRequest(archive = archive, pageToken = pageToken)
        val request = archiveRequestBuilder.buildBrowseRequest(nextRequestId("archive-browse"), requestPage)
        executeRequest(
            request = request,
            recordInHistory = false,
            onFailure = { failure ->
                _state.update { current -> current.copy(archiveReadOnly = current.archiveReadOnly.copy(browsePage = null, infoState = ArchiveWorkflowUiState.Failed(emptyList(), failure.message))) }
            },
        ) { result ->
            val entries = result.artifacts.mapNotNull { artifact ->
                when (artifact.label) {
                    "archive_entry" -> ArchiveEntrySummary(artifact.path, ArchiveEntryType.File, sizeBytes = artifact.sizeBytes)
                    "archive_entry_dir" -> ArchiveEntrySummary(artifact.path.trimEnd('/'), ArchiveEntryType.Directory, sizeBytes = artifact.sizeBytes)
                    "archive_entry_symlink" -> ArchiveEntrySummary(artifact.path, ArchiveEntryType.Symlink, sizeBytes = artifact.sizeBytes)
                    "archive_entry_special" -> ArchiveEntrySummary(artifact.path, ArchiveEntryType.Special, sizeBytes = artifact.sizeBytes)
                    else -> null
                }
            }
            val next = result.artifacts.firstOrNull { it.label == "archive_next_page" }?.path?.substringAfterLast('/')
            val total = Regex("of (\\d+) matching entries").find(result.summary)?.groupValues?.getOrNull(1)?.toLongOrNull()
            _state.update { current ->
                current.copy(
                    archiveReadOnly = current.archiveReadOnly.copy(
                        selectedArchive = archive,
                        browsePage = ArchiveBrowsePage(requestPage, entries, nextPageToken = next, totalEntriesEstimate = total),
                    ),
                )
            }
        }
    }

    private fun verifyArchive(archive: ArchiveLocation) {
        val request = archiveRequestBuilder.buildVerifyRequest(nextRequestId("archive-verify"), archive)
        executeRequest(request, recordInHistory = true, feedbackDestination = BkxDestination.Archives, deliverFailureResults = true) { result ->
            _state.update { current ->
                current.copy(
                    archiveReadOnly = current.archiveReadOnly.copy(
                        selectedArchive = archive,
                        verification = result.toArchiveVerificationUiState(),
                    ),
                )
            }
        }
    }

    private fun prepareRestore(archive: ArchiveLocation) {
        val current = _state.value.restore.draft
        _state.update { state ->
            state.copy(
                selectedDestination = BkxDestination.Archives,
                archiveReadOnly = state.archiveReadOnly.copy(selectedArchive = archive),
                archiveDetailSection = ArchiveDetailSection.Restore,
                restore = RestoreUiState(draft = current.copy(archive = archive)),
            )
        }
    }

    private fun requestRestorePreview() {
        val draft = _state.value.restore.draft
        if (draft.validationIssues().isNotEmpty()) return
        val request = restoreRequestBuilder.buildPreviewRequest(nextRequestId("restore-preview"), draft)
        executeRequest(
            request = request,
            recordInHistory = false,
            onFailure = { failure ->
                _state.update { current -> current.copy(restore = current.restore.copy(message = failure.message, preview = null, confirmation = null)) }
            },
        ) { result ->
            _state.update { current -> current.copy(restore = current.restore.copy(preview = result.toRestorePreviewSummary(draft), confirmation = null)) }
        }
    }

    private fun executeRestore() {
        val restore = _state.value.restore
        if (!restore.canExtract) return
        val request = restoreRequestBuilder.buildExtractRequest(
            nextRequestId("restore-extract"),
            restore.draft,
            restore.preview,
            restore.confirmation,
        )
        executeRequest(request, recordInHistory = true, feedbackDestination = BkxDestination.Archives)
    }

    private fun requestTransferPreview() {
        val draft = _state.value.transfer.draft
        if (draft.validationIssues().isNotEmpty()) return
        val request = transferRequestBuilder.buildPreviewRequest(nextRequestId("transfer-plan"), draft)
        executeRequest(
            request = request,
            recordInHistory = false,
            onFailure = { failure ->
                _state.update { current -> current.copy(transfer = current.transfer.copy(message = failure.message, preview = null)) }
            },
        ) { result ->
            val preview = result.toTransferPreviewSummary(draft)
            _state.update { current -> current.copy(transfer = current.transfer.copy(preview = preview)) }
        }
    }

    private fun runTransfer() {
        val transfer = _state.value.transfer
        if (!transfer.canRun) return
        val request = transferRequestBuilder.buildRunRequest(nextRequestId("transfer-run"), transfer.draft, transfer.preview)
        executeRequest(request, recordInHistory = true, feedbackDestination = BkxDestination.More, deliverFailureResults = true) { result ->
            _state.update { current -> current.copy(transfer = current.transfer.copy(result = result.toTransferResultUiState())) }
        }
    }

    private fun executeRequest(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
        feedbackDestination: BkxDestination? = null,
        deliverFailureResults: Boolean = false,
        onFailure: (OperationFailureUiState) -> Unit = {},
        onResult: (BridgeResultEnvelope) -> Unit = {},
    ) {
        if (requestUsesManagedGoogleDrive(request) && googleDriveAuthorizationManager != null) {
            authorizeGoogleDriveForOperation { authorized ->
                if (authorized) {
                    executePreparedRequest(request, recordInHistory, feedbackDestination, deliverFailureResults, onFailure, onResult)
                } else {
                    onFailure(
                        OperationFailureUiState(
                            kind = OperationFailureKind.PermissionDenied,
                            code = "google_drive_authorization_required",
                            title = "Google Drive authorization required",
                            message = "Reconnect Google Drive before running this operation.",
                        ),
                    )
                }
            }
            return
        }
        executePreparedRequest(request, recordInHistory, feedbackDestination, deliverFailureResults, onFailure, onResult)
    }

    private fun executePreparedRequest(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
        feedbackDestination: BkxDestination? = null,
        deliverFailureResults: Boolean = false,
        onFailure: (OperationFailureUiState) -> Unit = {},
        onResult: (BridgeResultEnvelope) -> Unit = {},
    ) {
        val startedAt = System.currentTimeMillis()
        if (feedbackDestination != null) {
            _state.update { current ->
                current.copy(
                    operationFeedback = current.operationFeedback + (
                        feedbackDestination to OperationSurfaceFeedback(
                            requestId = request.requestId,
                            operation = request.operation,
                            startedAtEpochMillis = startedAt,
                        )
                    ),
                    uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), "${operationLabelForMessage(request.operation)} started"),
                )
            }
        }
        viewModelScope.launch {
            when (val outcome = operationCoordinator.execute(request, recordInHistory)) {
                is OperationOutcome.Completed -> {
                    val result = outcome.result
                    val uiResult = result.toOperationResultUiState()
                    if (feedbackDestination != null) {
                        updateOperationFeedback(feedbackDestination, request, startedAt, uiResult)
                    }
                    if (result.status == BridgeResultStatus.Success || deliverFailureResults) {
                        onResult(result)
                    } else {
                        onFailure(OperationFailureMapper.fromFinalResult(result))
                    }
                    refreshOperations()
                    if (recordInHistory) refreshRuns()
                }
                is OperationOutcome.Failed -> {
                    onFailure(outcome.failure)
                    if (feedbackDestination != null) {
                        updateOperationFeedback(
                            feedbackDestination,
                            request,
                            startedAt,
                            OperationResultUiState.Failed(outcome.failure),
                        )
                    }
                    refreshOperations()
                }
                is OperationOutcome.CancellationAccepted -> refreshOperations()
            }
        }
    }

    private fun updateOperationFeedback(
        destination: BkxDestination,
        request: BridgeRequestEnvelope,
        startedAt: Long,
        result: OperationResultUiState,
    ) {
        val finishedAt = System.currentTimeMillis()
        _state.update { current ->
            current.copy(
                operationResult = result,
                operationFeedback = current.operationFeedback + (
                    destination to OperationSurfaceFeedback(
                        requestId = request.requestId,
                        operation = request.operation,
                        startedAtEpochMillis = startedAt,
                        result = result,
                        completedAtEpochMillis = finishedAt,
                    )
                ),
                uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), operationResultMessage(request.operation, result)),
            )
        }
    }

    private fun operationLabelForMessage(operation: String): String = when (operation) {
        "backup.create" -> "Backup"
        "archive.verify" -> "Archive verification"
        "restore.extract" -> "Restore"
        "transfer.run" -> "Transfer"
        "jobs.run" -> "Saved job"
        "jobs.plan" -> "Job plan"
        "repository.init" -> "Repository initialization"
        "repository.check" -> "Repository check"
        "repository.prune" -> "Repository prune"
        "retention.apply" -> "Retention"
        "snapshots.restore" -> "Snapshot restore"
        "snapshots.forget" -> "Snapshot removal"
        else -> "BKX operation"
    }

    private fun operationResultMessage(operation: String, result: OperationResultUiState): String = when (result) {
        OperationResultUiState.NotStarted -> "${operationLabelForMessage(operation)} started"
        is OperationResultUiState.Success -> "${operationLabelForMessage(operation)} completed"
        is OperationResultUiState.Cancelled -> "${operationLabelForMessage(operation)} cancelled"
        is OperationResultUiState.Failed -> "${operationLabelForMessage(operation)} failed: ${result.failure.title}"
    }

    private fun submitOperationCancellation(event: BkxHomeEvent.SubmitOperationCancellation) {
        viewModelScope.launch {
            when (val outcome = operationCoordinator.cancel(event.requestId, event.cancelRequestId)) {
                is OperationOutcome.Failed -> {
                    _state.update { current -> current.copy(operationResult = OperationResultUiState.Failed(outcome.failure)) }
                }
                else -> Unit
            }
            refreshOperations()
        }
    }

    private fun openFilesystemBrowser(target: FilesystemSelectionTarget, initialPath: String) {
        val remote = initialPath.startsWith("rclone://", ignoreCase = true)
        val path = when {
            initialPath.startsWith("/") -> initialPath
            remote && target.allowRemote -> initialPath
            else -> "/storage/emulated/0"
        }
        val folderOnlyOverride = if (target == FilesystemSelectionTarget.TransferSource && _state.value.transfer.draft.mode == com.mikeyphw.bkx.android.core.transfer.TransferMode.Sync) true else null
        _state.update { current ->
            current.copy(filesystemBrowser = FilesystemBrowserUiState(open = true, target = target, currentPath = path, loading = true, folderOnlyOverride = folderOnlyOverride))
        }
        loadFilesystem(path)
    }

    private fun loadFilesystem(path: String) {
        val browser = _state.value.filesystemBrowser
        val remote = path.startsWith("rclone://", ignoreCase = true)
        if (!browser.open || (!path.startsWith("/") && !(remote && browser.target?.allowRemote == true))) return
        _state.update { current -> current.copy(filesystemBrowser = current.filesystemBrowser.copy(currentPath = path, loading = true, error = null)) }
        val request = BridgeRequestEnvelope(
            requestId = nextRequestId("filesystem-list"),
            operation = "filesystem.list",
            payload = buildJsonObject {
                put("path", path)
                put("show_hidden", _state.value.filesystemBrowser.showHidden)
                put("limit", 500)
            },
        )
        executeRequest(request, recordInHistory = false, deliverFailureResults = true) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val entries = result.artifacts.map { artifact ->
                    FilesystemBrowserEntry(
                        path = artifact.path,
                        name = filesystemDisplayName(artifact.path),
                        kind = when (artifact.label) {
                            "directory" -> FilesystemEntryKind.Directory
                            "file" -> FilesystemEntryKind.File
                            "symlink" -> FilesystemEntryKind.Symlink
                            "special" -> FilesystemEntryKind.Special
                            else -> FilesystemEntryKind.Unreadable
                        },
                        sizeBytes = artifact.sizeBytes,
                        modifiedAtEpochMillis = artifact.modifiedAtEpochMillis,
                    )
                }
                _state.update { current -> current.copy(filesystemBrowser = current.filesystemBrowser.copy(entries = entries, loading = false, error = null)) }
            } else {
                _state.update { current -> current.copy(filesystemBrowser = current.filesystemBrowser.copy(loading = false, error = result.summary.ifBlank { "Could not list this path." })) }
            }
        }
    }

    private fun selectFilesystemPath(path: String, isDirectory: Boolean) {
        val browser = _state.value.filesystemBrowser
        val target = browser.target ?: return
        if (browser.folderOnly && !isDirectory) return
        when (target) {
            FilesystemSelectionTarget.BackupSource -> {
                val draft = _state.value.backupPlanning.draft
                onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = BackupSourceSelection("path-${path.hashCode()}", filesystemDisplayName(path), path))))
            }
            FilesystemSelectionTarget.BackupDestination -> {
                val draft = _state.value.backupPlanning.draft
                onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(destination = BackupDestinationSelection("path-${path.hashCode()}", filesystemDisplayName(path), path))))
            }
            FilesystemSelectionTarget.Archive -> {
                if (isDirectory) { loadFilesystem(path); return }
                val archive = archiveLocation(path)
                _state.update { it.copy(filesystemBrowser = FilesystemBrowserUiState()) }
                loadArchiveDetails(archive)
                return
            }
            FilesystemSelectionTarget.RestoreDestination -> {
                val draft = _state.value.restore.draft
                onEvent(BkxHomeEvent.UpdateRestoreDraft(draft.copy(destination = RestoreDestinationSelection("path-${path.hashCode()}", filesystemDisplayName(path), path))))
            }
            FilesystemSelectionTarget.SnapshotRestoreDestination -> {
                onEvent(BkxHomeEvent.SetSnapshotRestoreTarget(path))
            }
            FilesystemSelectionTarget.TransferSource -> {
                val draft = _state.value.transfer.draft
                val remote = path.startsWith("rclone://", ignoreCase = true)
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = listOf(TransferEndpoint("path-${path.hashCode()}", filesystemDisplayName(path), path, isRemote = remote)))))
            }
            FilesystemSelectionTarget.TransferDestination -> {
                val draft = _state.value.transfer.draft
                val remote = path.startsWith("rclone://", ignoreCase = true)
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(destination = TransferEndpoint("path-${path.hashCode()}", filesystemDisplayName(path), path, isRemote = remote))))
            }
        }
        _state.update { it.copy(filesystemBrowser = FilesystemBrowserUiState()) }
    }

    private fun importProviderBackupTree(uri: String) = stageProvider("backup source") {
        val staged = providerStagingPort?.stageTree(uri) ?: error("Provider staging is unavailable.")
        val draft = _state.value.backupPlanning.draft
        _state.update { current ->
            current.copy(
                backupPlanning = BackupPlanningUiState(draft = draft.copy(source = BackupSourceSelection("provider-${staged.stagedPath.hashCode()}", staged.displayName, staged.stagedPath, staged.sourceUri))),
                providerImportInProgress = false,
                providerImportMessage = "Imported ${staged.filesCopied} provider files into temporary BKX staging.",
            )
        }
    }

    private fun importProviderArchive(uri: String) = stageProvider("archive") {
        val staged = providerStagingPort?.stageDocument(uri) ?: error("Provider staging is unavailable.")
        val archive = archiveLocation(staged.stagedPath)
        _state.update { current -> current.copy(providerImportInProgress = false, providerImportMessage = "Imported ${staged.displayName} from the document provider.") }
        loadArchiveDetails(archive)
    }

    private fun importProviderTransferTree(uri: String) = stageProvider("transfer source") {
        val staged = providerStagingPort?.stageTree(uri) ?: error("Provider staging is unavailable.")
        val draft = _state.value.transfer.draft
        _state.update { current ->
            current.copy(
                transfer = TransferUiState(draft = draft.copy(sources = listOf(TransferEndpoint("provider-${staged.stagedPath.hashCode()}", staged.displayName, staged.stagedPath)))),
                providerImportInProgress = false,
                providerImportMessage = "Imported ${staged.filesCopied} provider files for transfer.",
            )
        }
    }

    private fun stageProvider(label: String, block: suspend () -> Unit) {
        _state.update { it.copy(providerImportInProgress = true, providerImportMessage = "Importing $label…") }
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                _state.update { it.copy(providerImportInProgress = false, providerImportMessage = "Could not import $label: ${error.message ?: error::class.java.simpleName}") }
            }
        }
    }

    private fun refreshGoogleDriveAuthorization(allowConsent: Boolean) {
        val manager = googleDriveAuthorizationManager ?: return
        val readiness = manager.managedRemoteReadiness()
        if (readiness != GoogleDriveRemoteReadiness.Ready && !allowConsent) {
            _state.update { it.copy(googleDrive = GoogleDriveUiState(remoteReadiness = readiness, message = readiness.userMessage())) }
            return
        }
        _state.update { current ->
            current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Connecting, pendingConsent = null, message = "Checking Google Drive authorization…"))
        }
        manager.authorize { outcome -> applyGoogleDriveOutcome(outcome, allowConsent) }
    }

    private fun completeGoogleDriveAuthorization(data: Intent?) {
        val manager = googleDriveAuthorizationManager ?: return
        _state.update { current -> current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Connecting, pendingConsent = null)) }
        manager.completeAuthorization(data) { outcome -> applyGoogleDriveOutcome(outcome, allowConsent = false) }
    }

    private fun disconnectGoogleDrive() {
        val manager = googleDriveAuthorizationManager ?: return
        _state.update { current -> current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Disconnecting, pendingConsent = null, message = "Revoking Google Drive access…")) }
        manager.revoke { result ->
            result.onSuccess {
                _state.update { it.copy(googleDrive = GoogleDriveUiState(message = "Google Drive disconnected and BKX access revoked.")) }
                refreshBackend()
                refreshArchives()
            }.onFailure { error ->
                _state.update { current -> current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Error, message = error.message ?: "Could not revoke Google Drive access.")) }
            }
        }
    }

    private fun applyGoogleDriveOutcome(outcome: GoogleDriveAuthorizationOutcome, allowConsent: Boolean) {
        when (outcome) {
            GoogleDriveAuthorizationOutcome.Connected -> {
                _state.update { current ->
                    current.copy(googleDrive = GoogleDriveUiState(status = GoogleDriveConnectionStatus.Connected, message = "Google Drive is ready for BKX backup and restore.", lastAuthorizedAtEpochMillis = System.currentTimeMillis(), remoteReadiness = GoogleDriveRemoteReadiness.Ready))
                }
                refreshBackend()
            }
            is GoogleDriveAuthorizationOutcome.NeedsConsent -> {
                _state.update { current ->
                    current.copy(googleDrive = current.googleDrive.copy(
                        status = GoogleDriveConnectionStatus.NeedsConsent,
                        pendingConsent = outcome.pendingIntent.takeIf { allowConsent },
                        message = if (allowConsent) "Google Drive needs your approval." else "Google Drive needs to be reconnected before cloud operations can continue.",
                    ))
                }
            }
            is GoogleDriveAuthorizationOutcome.Failed -> _state.update { current ->
                val readiness = googleDriveAuthorizationManager?.managedRemoteReadiness() ?: current.googleDrive.remoteReadiness
                current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Error, pendingConsent = null, message = outcome.message, remoteReadiness = readiness))
            }
        }
    }

    private fun authorizeGoogleDriveForOperation(onReady: (Boolean) -> Unit) {
        val manager = googleDriveAuthorizationManager ?: run { onReady(true); return }
        _state.update { current -> current.copy(googleDrive = current.googleDrive.copy(status = GoogleDriveConnectionStatus.Connecting, pendingConsent = null, message = "Refreshing Google Drive access…")) }
        manager.authorize { outcome ->
            when (outcome) {
                GoogleDriveAuthorizationOutcome.Connected -> {
                    applyGoogleDriveOutcome(outcome, allowConsent = false)
                    onReady(true)
                }
                is GoogleDriveAuthorizationOutcome.NeedsConsent -> {
                    applyGoogleDriveOutcome(outcome, allowConsent = true)
                    _state.update { current -> current.copy(uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), "Reconnect Google Drive to continue.")) }
                    onReady(false)
                }
                is GoogleDriveAuthorizationOutcome.Failed -> {
                    applyGoogleDriveOutcome(outcome, allowConsent = false)
                    _state.update { current -> current.copy(uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), outcome.message)) }
                    onReady(false)
                }
            }
        }
    }

    private fun requestUsesManagedGoogleDrive(request: BridgeRequestEnvelope): Boolean {
        return GoogleDriveRcloneConfig.containsManagedRemoteReference(request.payload.toString())
    }

    private fun refreshSavedJobs() {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(status = ScreenStatus.Loading)) }
        val request = savedJobRequestBuilder.list(nextRequestId("jobs-list"))
        executeRequest(
            request = request,
            recordInHistory = false,
            onFailure = { failure ->
                _state.update { current ->
                    current.copy(savedJobs = current.savedJobs.copy(status = ScreenStatus.Error(failure.title, failure.message), actionInProgress = null))
                }
            },
        ) { result ->
            val jobs = SavedJobBridgeCodec.decodeArtifacts(result.artifacts)
            _state.update { current ->
                current.copy(
                    savedJobs = current.savedJobs.copy(
                        status = if (jobs.isEmpty()) ScreenStatus.Empty("No saved jobs yet. Create one to replay a backup or transfer without rebuilding its options.") else ScreenStatus.Content,
                        items = jobs,
                        actionInProgress = null,
                    ),
                )
            }
        }
    }

    private fun createSavedJob(draft: SavedJobDraft) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(actionInProgress = "create:${draft.jobId}")) }
        executeRequest(
            request = savedJobRequestBuilder.create(nextRequestId("job-create"), draft),
            recordInHistory = false,
            onFailure = { failure -> jobActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(lastActionMessage = result.summary, actionInProgress = null)) }
            refreshSavedJobs()
        }
    }

    private fun updateSavedJob(jobId: String, expectedRevision: Long, draft: SavedJobDraft) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(actionInProgress = "update:$jobId")) }
        executeRequest(
            request = savedJobRequestBuilder.update(nextRequestId("job-update"), jobId, expectedRevision, draft),
            recordInHistory = false,
            onFailure = { failure -> jobActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(lastActionMessage = result.summary, actionInProgress = null)) }
            refreshSavedJobs()
        }
    }

    private fun cloneSavedJob(sourceJobId: String, targetJobId: String, name: String) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(actionInProgress = "clone:$sourceJobId")) }
        executeRequest(
            request = savedJobRequestBuilder.clone(nextRequestId("job-clone"), sourceJobId, targetJobId, name),
            recordInHistory = false,
            onFailure = { failure -> jobActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(lastActionMessage = result.summary, actionInProgress = null)) }
            refreshSavedJobs()
        }
    }

    private fun deleteSavedJob(jobId: String) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(actionInProgress = "delete:$jobId")) }
        executeRequest(
            request = savedJobRequestBuilder.delete(nextRequestId("job-delete"), jobId),
            recordInHistory = false,
            onFailure = { failure -> jobActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(lastActionMessage = result.summary, actionInProgress = null)) }
            refreshSavedJobs()
        }
    }

    private fun executeSavedJob(jobId: String, plan: Boolean) {
        val job = _state.value.savedJobs.items.firstOrNull { it.jobId == jobId } ?: return
        _state.update { current ->
            current.copy(savedJobs = current.savedJobs.copy(actionInProgress = if (plan) "plan:$jobId" else "run:$jobId"))
        }
        val backendHint = job.operation.repositoryRef?.let { repositoryId ->
            _state.value.repositories.items.firstOrNull { it.repositoryId == repositoryId }?.backend
        }
        val effectiveOperation = OperationClassifier.effectiveOperation(job.operation.operation)
        val request = if (plan) {
            savedJobRequestBuilder.plan(nextRequestId("job-plan"), jobId, backendHint)
        } else {
            savedJobRequestBuilder.run(nextRequestId("job-run"), jobId, backendHint, effectiveOperation)
        }
        executeRequest(
            request = request,
            recordInHistory = !plan,
            feedbackDestination = BkxDestination.Backup,
            onFailure = { failure -> jobActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(lastActionMessage = result.summary, actionInProgress = null)) }
            if (!plan) refreshRuns()
        }
    }

    private fun replayRun(runId: String, jobId: String?) {
        val effectiveOperation = _state.value.recentRuns.firstOrNull { it.requestId == runId }?.operation
            ?.let { OperationClassifier.effectiveOperation(it) }
        val request = savedJobRequestBuilder.replay(nextRequestId("run-replay"), runId, jobId, effectiveOperation)
        executeRequest(
            request = request,
            recordInHistory = true,
            feedbackDestination = BkxDestination.Backup,
            onFailure = { failure ->
                _state.update { current -> current.copy(uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), failure.message)) }
            },
        ) { result ->
            _state.update { current -> current.copy(uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), result.summary)) }
            refreshRuns()
        }
    }

    private fun jobActionFailure(failure: OperationFailureUiState) {
        _state.update { current ->
            current.copy(
                savedJobs = current.savedJobs.copy(actionInProgress = null, lastActionMessage = failure.message),
                uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), failure.message),
            )
        }
    }

    private fun refreshSavedJobSchedules() {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleStatus = ScreenStatus.Loading)) }
        executeRequest(
            request = savedJobScheduleRequestBuilder.list(nextRequestId("schedules-list")),
            recordInHistory = false,
            onFailure = { failure ->
                _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleStatus = ScreenStatus.Error(failure.title, failure.message), scheduleActionInProgress = null)) }
            },
        ) { result ->
            val schedules = SavedJobScheduleBridgeCodec.decodeArtifacts(result.artifacts)
            _state.update { current ->
                current.copy(savedJobs = current.savedJobs.copy(
                    scheduleStatus = if (schedules.isEmpty()) ScreenStatus.Empty("No recurring saved-job schedules yet.") else ScreenStatus.Content,
                    schedules = schedules,
                    scheduleActionInProgress = null,
                ))
            }
        }
    }

    private fun putSavedJobSchedule(schedule: SavedJobScheduleDefinition) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleActionInProgress = "put:${schedule.scheduleId}")) }
        executeRequest(
            request = savedJobScheduleRequestBuilder.put(nextRequestId("schedule-put"), schedule),
            recordInHistory = false,
            onFailure = { failure -> scheduleActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleMessage = result.summary, scheduleActionInProgress = null)) }
            refreshSavedJobSchedules()
        }
    }

    private fun setSavedJobScheduleEnabled(scheduleId: String, enabled: Boolean) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleActionInProgress = "toggle:$scheduleId")) }
        executeRequest(
            request = savedJobScheduleRequestBuilder.setEnabled(nextRequestId("schedule-toggle"), scheduleId, enabled),
            recordInHistory = false,
            onFailure = { failure -> scheduleActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleMessage = result.summary, scheduleActionInProgress = null)) }
            refreshSavedJobSchedules()
        }
    }

    private fun deleteSavedJobSchedule(scheduleId: String) {
        _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleActionInProgress = "delete:$scheduleId")) }
        executeRequest(
            request = savedJobScheduleRequestBuilder.delete(nextRequestId("schedule-delete"), scheduleId),
            recordInHistory = false,
            onFailure = { failure -> scheduleActionFailure(failure) },
        ) { result ->
            _state.update { current -> current.copy(savedJobs = current.savedJobs.copy(scheduleMessage = result.summary, scheduleActionInProgress = null)) }
            refreshSavedJobSchedules()
        }
    }

    private fun scheduleActionFailure(failure: OperationFailureUiState) {
        _state.update { current ->
            current.copy(
                savedJobs = current.savedJobs.copy(scheduleActionInProgress = null, scheduleMessage = failure.message),
                uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), failure.message),
            )
        }
    }

    private fun refreshRepositories() {
        _state.update { current -> current.copy(repositories = current.repositories.copy(status = ScreenStatus.Loading)) }
        executeRequest(
            request = repositoryRequestBuilder.list(nextRequestId("repositories-list")),
            recordInHistory = false,
            onFailure = { failure ->
                _state.update { current -> current.copy(repositories = current.repositories.copy(status = ScreenStatus.Error(failure.title, failure.message), actionInProgress = null)) }
            },
        ) { result ->
            val repositories = RepositoryBridgeCodec.decodeArtifacts(result.artifacts)
            _state.update { current ->
                current.copy(
                    repositories = current.repositories.copy(
                        status = if (repositories.isEmpty()) ScreenStatus.Empty("No incremental repositories yet. Add a local, rclone, or BKX-managed Google Drive repository.") else ScreenStatus.Content,
                        items = repositories,
                        actionInProgress = null,
                    ),
                )
            }
        }
    }

    private fun addRepository(spec: RepositoryCreateSpec) {
        _state.update { current -> current.copy(repositories = current.repositories.copy(actionInProgress = "add:${spec.repositoryId}")) }
        executeRequest(
            request = repositoryRequestBuilder.add(nextRequestId("repository-add"), spec),
            recordInHistory = false,
            onFailure = { failure -> repositoryActionFailure(spec.repositoryId, failure) },
        ) { result ->
            repositoryActionSuccess(spec.repositoryId, result.summary)
            refreshRepositories()
        }
    }

    private fun initializeRepository(repositoryId: String) = withRepository(repositoryId) { repository ->
        repositoryOperation(repository, "init", repositoryRequestBuilder.initialize(nextRequestId("repository-init"), repository), recordInHistory = true)
    }

    private fun checkRepository(repositoryId: String) = withRepository(repositoryId) { repository ->
        repositoryOperation(repository, "check", repositoryRequestBuilder.check(nextRequestId("repository-check"), repository), recordInHistory = true)
    }

    private fun pruneRepository(repositoryId: String, dryRun: Boolean) = withRepository(repositoryId) { repository ->
        repositoryOperation(
            repository,
            if (dryRun) "prune-preview" else "prune",
            repositoryRequestBuilder.prune(nextRequestId(if (dryRun) "repository-prune-preview" else "repository-prune"), repository, dryRun = dryRun, confirm = !dryRun),
            recordInHistory = !dryRun,
        )
    }

    private fun setRepositoryRetention(repositoryId: String, policy: RetentionPolicy) {
        val request = repositoryRequestBuilder.retentionSet(nextRequestId("retention-set"), repositoryId, policy)
        _state.update { current -> current.copy(repositories = current.repositories.copy(actionInProgress = "retention-set:$repositoryId")) }
        executeRequest(request, recordInHistory = false, onFailure = { failure -> repositoryActionFailure(repositoryId, failure) }) { result ->
            repositoryActionSuccess(repositoryId, result.summary)
            refreshRepositories()
        }
    }

    private fun applyRetention(repositoryId: String, dryRun: Boolean) = withRepository(repositoryId) { repository ->
        repositoryOperation(
            repository,
            if (dryRun) "retention-preview" else "retention-apply",
            repositoryRequestBuilder.retentionApply(nextRequestId(if (dryRun) "retention-preview" else "retention-apply"), repository, dryRun = dryRun, confirm = !dryRun),
            recordInHistory = !dryRun,
        )
    }

    private fun removeRepository(repositoryId: String) {
        _state.update { current -> current.copy(repositories = current.repositories.copy(actionInProgress = "remove:$repositoryId")) }
        executeRequest(
            request = repositoryRequestBuilder.remove(nextRequestId("repository-remove"), repositoryId),
            recordInHistory = false,
            onFailure = { failure -> repositoryActionFailure(repositoryId, failure) },
        ) { result ->
            repositoryActionSuccess(repositoryId, result.summary)
            refreshRepositories()
        }
    }

    private fun withRepository(repositoryId: String, block: (RepositoryDefinition) -> Unit) {
        val repository = _state.value.repositories.items.firstOrNull { it.repositoryId == repositoryId }
        if (repository == null) {
            _state.update { current -> current.copy(uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), "Repository $repositoryId is no longer available.")) }
            refreshRepositories()
            return
        }
        block(repository)
    }

    private fun repositoryOperation(repository: RepositoryDefinition, action: String, request: BridgeRequestEnvelope, recordInHistory: Boolean) {
        val key = "$action:${repository.repositoryId}"
        _state.update { current -> current.copy(repositories = current.repositories.copy(actionInProgress = key)) }
        executeRequest(
            request = request,
            recordInHistory = recordInHistory,
            feedbackDestination = BkxDestination.Backup,
            onFailure = { failure -> repositoryActionFailure(repository.repositoryId, failure) },
        ) { result ->
            repositoryActionSuccess(repository.repositoryId, result.summary)
            refreshRepositories()
        }
    }

    private fun repositoryActionSuccess(repositoryId: String, message: String) {
        _state.update { current ->
            current.copy(
                repositories = current.repositories.copy(
                    actionInProgress = null,
                    messages = current.repositories.messages + (repositoryId to message),
                ),
                uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), message),
            )
        }
    }

    private fun repositoryActionFailure(repositoryId: String, failure: OperationFailureUiState) {
        _state.update { current ->
            current.copy(
                repositories = current.repositories.copy(
                    actionInProgress = null,
                    messages = current.repositories.messages + (repositoryId to failure.message),
                ),
                uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), failure.message),
            )
        }
    }

    private fun ensureSnapshotRepositoryAndRefresh() {
        val currentId = _state.value.snapshots.repositoryId
        val available = _state.value.repositories.items
        val selected = available.firstOrNull { it.repositoryId == currentId } ?: available.firstOrNull()
        if (selected == null) {
            _state.update { current ->
                current.copy(
                    snapshots = current.snapshots.copy(
                        status = ScreenStatus.Empty("Add or connect a Restic repository before browsing snapshots."),
                        repositoryId = null,
                        items = emptyList(),
                        selectedSnapshot = null,
                        entries = emptyList(),
                    ),
                )
            }
            return
        }
        if (currentId != selected.repositoryId) {
            _state.update { current -> current.copy(snapshots = SnapshotsUiState(repositoryId = selected.repositoryId)) }
        }
        refreshSnapshots()
    }

    private fun selectSnapshotRepository(repositoryId: String) {
        if (_state.value.repositories.items.none { it.repositoryId == repositoryId }) return
        _state.update { current -> current.copy(snapshots = SnapshotsUiState(repositoryId = repositoryId)) }
        refreshSnapshots()
    }

    private fun refreshSnapshots() {
        val repositoryId = _state.value.snapshots.repositoryId ?: run {
            ensureSnapshotRepositoryAndRefresh()
            return
        }
        withRepository(repositoryId) { repository ->
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(status = ScreenStatus.Loading, actionInProgress = "list:$repositoryId", message = null)) }
            executeRequest(
                request = repositoryRequestBuilder.snapshots(nextRequestId("snapshots-list"), repository),
                recordInHistory = false,
                onFailure = { failure -> snapshotActionFailure(failure, listFailure = true) },
            ) { result ->
                val snapshots = SnapshotBridgeCodec.decodeSnapshots(result.artifacts)
                val selectedId = _state.value.snapshots.selectedSnapshot?.id
                val selected = snapshots.firstOrNull { it.id == selectedId }
                _state.update { current ->
                    current.copy(
                        snapshots = current.snapshots.copy(
                            status = if (snapshots.isEmpty()) ScreenStatus.Empty("No snapshots in this repository yet. Run an incremental backup to create one.") else ScreenStatus.Content,
                            items = snapshots,
                            selectedSnapshot = selected,
                            actionInProgress = null,
                            message = result.summary,
                        ),
                    )
                }
            }
        }
    }

    private fun selectSnapshot(snapshot: SnapshotDescriptor) {
        val shortId = snapshot.shortId.ifBlank { snapshot.id.take(8) }
        _state.update { current ->
            current.copy(
                snapshots = current.snapshots.copy(
                    selectedSnapshot = snapshot,
                    browseStatus = ScreenStatus.Loading,
                    browsePath = "/",
                    entries = emptyList(),
                    selectedRestorePaths = emptySet(),
                    restoreTarget = "/storage/emulated/0/BKX/Restores/$shortId",
                    restorePreview = null,
                    restoreResult = null,
                    diff = null,
                    compareTargetId = null,
                    message = null,
                ),
            )
        }
        browseSnapshotPath("/")
    }

    private fun browseSnapshotPath(path: String) {
        val snapshotState = _state.value.snapshots
        val repositoryId = snapshotState.repositoryId ?: return
        val snapshot = snapshotState.selectedSnapshot ?: return
        val normalized = path.trim().ifBlank { "/" }
        if (!normalized.startsWith("/")) return
        withRepository(repositoryId) { repository ->
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(browseStatus = ScreenStatus.Loading, browsePath = normalized, entries = emptyList(), actionInProgress = "browse:${snapshot.id}")) }
            executeRequest(
                request = repositoryRequestBuilder.snapshotBrowse(nextRequestId("snapshot-browse"), repository, snapshot.id, normalized),
                recordInHistory = false,
                onFailure = { failure ->
                    _state.update { current -> current.copy(snapshots = current.snapshots.copy(browseStatus = ScreenStatus.Error(failure.title, failure.message), actionInProgress = null, message = failure.message)) }
                },
            ) { result ->
                val entries = SnapshotBridgeCodec.decodeNodes(result.artifacts)
                _state.update { current ->
                    current.copy(
                        snapshots = current.snapshots.copy(
                            browseStatus = if (entries.isEmpty()) ScreenStatus.Empty("This folder is empty in the selected snapshot.") else ScreenStatus.Content,
                            browsePath = normalized,
                            entries = entries,
                            actionInProgress = null,
                            message = result.summary,
                        ),
                    )
                }
            }
        }
    }

    private fun toggleSnapshotRestorePath(path: String) {
        val normalized = path.trim()
        if (!normalized.startsWith("/")) return
        _state.update { current ->
            val selected = current.snapshots.selectedRestorePaths.toMutableSet()
            if (!selected.add(normalized)) selected.remove(normalized)
            current.copy(snapshots = current.snapshots.copy(selectedRestorePaths = selected, restorePreview = null, restoreResult = null))
        }
    }

    private fun setSnapshotRestoreTarget(path: String) {
        val normalized = path.trim().replace(Regex("/+"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
        val issue = when {
            !normalized.startsWith("/") -> "Restore target must be an absolute device path."
            else -> RestoreDestinationPolicy.blockedReason(normalized)
        }
        if (issue != null) {
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(message = issue, restorePreview = null)) }
            return
        }
        _state.update { current -> current.copy(snapshots = current.snapshots.copy(restoreTarget = normalized, restorePreview = null, restoreResult = null, message = null)) }
    }

    private fun compareSnapshotWithPrevious() {
        val snapshotState = _state.value.snapshots
        val currentSnapshot = snapshotState.selectedSnapshot ?: return
        val repositoryId = snapshotState.repositoryId ?: return
        val index = snapshotState.items.indexOfFirst { it.id == currentSnapshot.id }
        val previous = snapshotState.items.getOrNull(index + 1)
        if (index < 0 || previous == null) {
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(message = "There is no older snapshot to compare with this one.")) }
            return
        }
        withRepository(repositoryId) { repository ->
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(actionInProgress = "diff:${currentSnapshot.id}", diff = null, compareTargetId = previous.id)) }
            executeRequest(
                request = repositoryRequestBuilder.snapshotDiff(nextRequestId("snapshot-diff"), repository, previous.id, currentSnapshot.id, metadata = true),
                recordInHistory = false,
                onFailure = { failure -> snapshotActionFailure(failure) },
            ) { result ->
                _state.update { current -> current.copy(snapshots = current.snapshots.copy(actionInProgress = null, diff = SnapshotBridgeCodec.decodeDiff(result.artifacts), message = result.summary)) }
            }
        }
    }

    private fun runSnapshotRestore(dryRun: Boolean) {
        val snapshotState = _state.value.snapshots
        val repositoryId = snapshotState.repositoryId ?: return
        val snapshot = snapshotState.selectedSnapshot ?: return
        val target = snapshotState.restoreTarget.trim()
        val issue = when {
            !target.startsWith("/") -> "Restore target must be an absolute device path."
            else -> RestoreDestinationPolicy.blockedReason(target)
        }
        if (issue != null) {
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(message = issue)) }
            return
        }
        withRepository(repositoryId) { repository ->
            val request = repositoryRequestBuilder.snapshotRestore(
                requestId = nextRequestId(if (dryRun) "snapshot-restore-preview" else "snapshot-restore"),
                repository = repository,
                snapshot = snapshot.id,
                target = target,
                includes = snapshotState.restoreIncludes,
                overwrite = snapshotState.overwriteMode,
                dryRun = dryRun,
            )
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(actionInProgress = if (dryRun) "restore-preview:${snapshot.id}" else "restore:${snapshot.id}", message = null)) }
            executeRequest(
                request = request,
                recordInHistory = !dryRun,
                feedbackDestination = if (dryRun) null else BkxDestination.Backup,
                onFailure = { failure -> snapshotActionFailure(failure) },
            ) { result ->
                val decoded = SnapshotBridgeCodec.decodeRestoreResult(result.artifacts)
                _state.update { current ->
                    current.copy(
                        snapshots = current.snapshots.copy(
                            actionInProgress = null,
                            restorePreview = if (dryRun) decoded else current.snapshots.restorePreview,
                            restoreResult = if (dryRun) current.snapshots.restoreResult else decoded,
                            message = result.summary,
                        ),
                    )
                }
                if (!dryRun) refreshRuns()
            }
        }
    }

    private fun forgetSnapshot(snapshotId: String) {
        val repositoryId = _state.value.snapshots.repositoryId ?: return
        withRepository(repositoryId) { repository ->
            _state.update { current -> current.copy(snapshots = current.snapshots.copy(actionInProgress = "forget:$snapshotId")) }
            executeRequest(
                request = repositoryRequestBuilder.snapshotForget(nextRequestId("snapshot-forget"), repository, snapshotId, confirm = true),
                recordInHistory = true,
                feedbackDestination = BkxDestination.Backup,
                onFailure = { failure -> snapshotActionFailure(failure) },
            ) { result ->
                _state.update { current ->
                    val clearSelected = current.snapshots.selectedSnapshot?.id == snapshotId
                    current.copy(
                        snapshots = current.snapshots.copy(
                            actionInProgress = null,
                            selectedSnapshot = if (clearSelected) null else current.snapshots.selectedSnapshot,
                            entries = if (clearSelected) emptyList() else current.snapshots.entries,
                            selectedRestorePaths = if (clearSelected) emptySet() else current.snapshots.selectedRestorePaths,
                            message = result.summary,
                        ),
                    )
                }
                refreshSnapshots()
            }
        }
    }

    private fun snapshotActionFailure(failure: OperationFailureUiState, listFailure: Boolean = false) {
        _state.update { current ->
            current.copy(
                snapshots = current.snapshots.copy(
                    status = if (listFailure) ScreenStatus.Error(failure.title, failure.message) else current.snapshots.status,
                    actionInProgress = null,
                    message = failure.message,
                ),
                uiMessage = BkxUiMessage(messageSequence.incrementAndGet(), failure.message),
            )
        }
    }

    private fun refreshBackend() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, readiness = BackendReadiness.Checking) }
            val readiness = checkBackendReadiness()
            _state.update { current ->
                current.copy(
                    isRefreshing = false,
                    readiness = readiness,
                    screenStatuses = current.screenStatuses.withBackendStatus(readiness),
                )
            }
        }
    }

    private fun refreshRuns() {
        val request = RunHistoryRequestBuilder.list(nextRequestId("runs-list"), limit = 50)
        executeRequest(
            request = request,
            recordInHistory = false,
            onFailure = { publishCachedRuns() },
        ) { result ->
            val sharedRuns = RunHistoryBridgeCodec.decodeArtifacts(result.artifacts)
            viewModelScope.launch {
                sharedRuns.forEach { runSummaryStore.upsert(it) }
                val cached = runSummaryStore.recentRuns(limit = 50)
                val sharedById = sharedRuns.associateBy { it.requestId }
                val merged = (cached.filterNot { it.requestId in sharedById } + sharedRuns)
                    .sortedByDescending { it.startedAtEpochMillis }
                    .take(20)
                publishRuns(merged)
            }
        }
    }

    private fun publishCachedRuns() {
        viewModelScope.launch { publishRuns(runSummaryStore.recentRuns(limit = 20)) }
    }

    private fun publishRuns(runs: List<RunSummary>) {
        _state.update { current ->
            current.copy(
                recentRuns = runs,
                screenStatuses = current.screenStatuses + (
                    BkxDestination.Activity to if (runs.isEmpty() && current.operationRuntime.activeOperations.isEmpty()) {
                        ScreenStatus.Empty("No backup, restore, verification, or transfer activity yet.")
                    } else {
                        ScreenStatus.Content
                    }
                ),
            )
        }
    }

    private fun refreshOperations() {
        viewModelScope.launch {
            val runtime = operationCoordinator.snapshot()
            _state.update { current ->
                val focusedRequestId = current.operationRuntime.focusedRequestId
                current.copy(
                    operationRuntime = if (focusedRequestId == null) runtime else runtime.copy(focusedRequestId = focusedRequestId),
                )
            }
        }
    }

    private fun updateSettings(settings: BkxSettings) {
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch { settingsStore.update(settings) }
    }

    private fun updateOperationRuntimeAndSelectActivity(transform: (OperationRuntimeState) -> OperationRuntimeState) {
        var nextRuntime: OperationRuntimeState? = null
        _state.update { current ->
            val next = transform(current.operationRuntime)
            nextRuntime = next
            current.copy(selectedDestination = BkxDestination.Activity, operationRuntime = next)
        }
        nextRuntime?.let { runtime -> viewModelScope.launch { operationStateStore.update(runtime) } }
    }

    private fun updateOperationRuntime(transform: (OperationRuntimeState) -> OperationRuntimeState) {
        var nextRuntime: OperationRuntimeState? = null
        _state.update { current ->
            val next = transform(current.operationRuntime)
            nextRuntime = next
            current.copy(operationRuntime = next)
        }
        nextRuntime?.let { runtime -> viewModelScope.launch { operationStateStore.update(runtime) } }
    }

    private fun refreshSettings() {
        viewModelScope.launch {
            val settings = settingsStore.read()
            _state.update { it.copy(settings = settings) }
        }
    }

    private fun nextRequestId(prefix: String): String {
        val sequence = requestSequence.incrementAndGet()
        return "$prefix-${System.currentTimeMillis()}-$sequence"
    }

    private fun filesystemDisplayName(path: String): String {
        if (path.startsWith("rclone://", ignoreCase = true)) {
            val uri = path.toUri()
            return uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: uri.host?.takeIf { it.isNotBlank() }
                ?: "Remote"
        }
        return path.substringAfterLast('/').ifBlank { path }
    }

    private fun archiveLocation(path: String): ArchiveLocation = ArchiveLocation(
        archiveId = "archive-${path.hashCode().toUInt().toString(16)}",
        displayName = filesystemDisplayName(path).ifBlank { "Archive" },
        stagedPath = path,
    )
}

class BkxAppViewModelFactory(
    private val backendPort: BackendPort,
    private val operationCoordinator: BkxOperationCoordinator,
    private val runSummaryStore: RunSummaryStore,
    private val operationStateStore: OperationStateStore,
    private val settingsStore: SettingsStore,
    private val providerStagingPort: ProviderStagingPort? = null,
    private val googleDriveAuthorizationManager: GoogleDriveAuthorizationManager? = null,
    private val archiveRootsProvider: (Boolean) -> List<String> = { emptyList() },
    private val requestBaseDirectoryPath: String = defaultRequestBaseDirectory(),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BkxAppViewModel::class.java)) {
            return BkxAppViewModel(
                backendPort = backendPort,
                runSummaryStore = runSummaryStore,
                operationStateStore = operationStateStore,
                settingsStore = settingsStore,
                providerStagingPort = providerStagingPort,
                googleDriveAuthorizationManager = googleDriveAuthorizationManager,
                archiveRootsProvider = archiveRootsProvider,
                requestBaseDirectoryPath = requestBaseDirectoryPath,
                operationCoordinator = operationCoordinator,
            ) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}

private fun initialScreenStatuses(): Map<BkxDestination, ScreenStatus> = mapOf(
    BkxDestination.Home to ScreenStatus.Content,
    BkxDestination.Backup to ScreenStatus.Content,
    BkxDestination.Archives to ScreenStatus.Content,
    BkxDestination.Activity to ScreenStatus.Loading,
    BkxDestination.More to ScreenStatus.Content,
)

private fun Map<BkxDestination, ScreenStatus>.withBackendStatus(readiness: BackendReadiness): Map<BkxDestination, ScreenStatus> =
    this + (BkxDestination.Home to if (readiness is BackendReadiness.ProbeFailed) {
        ScreenStatus.Error(readiness.title, readiness.message)
    } else {
        ScreenStatus.Content
    })

private fun defaultRequestBaseDirectory(): String =
    File(System.getProperty("java.io.tmpdir") ?: "/tmp", "bkx-android-requests").absolutePath
