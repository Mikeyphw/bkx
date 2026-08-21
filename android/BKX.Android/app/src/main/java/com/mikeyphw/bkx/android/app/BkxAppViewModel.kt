package com.mikeyphw.bkx.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.bkx.android.app.navigation.BackendToolState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
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
import com.mikeyphw.bkx.android.core.files.FilesystemBrowserEntry
import com.mikeyphw.bkx.android.core.files.FilesystemBrowserUiState
import com.mikeyphw.bkx.android.core.files.FilesystemEntryKind
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.files.ProviderStagingPort
import com.mikeyphw.bkx.android.core.operations.OperationFailureMapper
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
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.BkxSettings
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import com.mikeyphw.bkx.android.core.transfer.TransferCollisionSummary
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferPreviewSummary
import com.mikeyphw.bkx.android.core.transfer.TransferRequestBuilder
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import com.mikeyphw.bkx.android.core.transfer.TransferUiState
import com.mikeyphw.bkx.android.core.transfer.TransferVerificationPlan
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

data class BkxHomeUiState(
    val title: String = "BKX",
    val readiness: BackendReadiness = BackendReadiness.Checking,
    val isRefreshing: Boolean = false,
    val selectedDestination: BkxDestination = BkxDestination.Home,
    val destinations: List<BkxDestination> = BkxDestination.entries,
    val recentRuns: List<RunSummary> = emptyList(),
    val backupPlanning: BackupPlanningUiState = BackupPlanningUiState(),
    val archiveReadOnly: ArchiveReadOnlyUiState = ArchiveReadOnlyUiState(),
    val restore: RestoreUiState = RestoreUiState(),
    val transfer: TransferUiState = TransferUiState(),
    val operationRuntime: OperationRuntimeState = OperationRuntimeState(),
    val operationResult: OperationResultUiState = OperationResultUiState.NotStarted,
    val filesystemBrowser: FilesystemBrowserUiState = FilesystemBrowserUiState(),
    val providerImportInProgress: Boolean = false,
    val providerImportMessage: String? = null,
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
    val activeOperationCount: Int get() = operationRuntime.activeOperations.values.count { it.status == com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus.Queued || it.status == com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus.Running || it.status == com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus.Cancelling }
}

sealed interface BkxHomeEvent {
    data object RefreshBackend : BkxHomeEvent
    data object RefreshRuns : BkxHomeEvent
    data class SelectDestination(val destination: BkxDestination) : BkxHomeEvent

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

    data class UpdateSettings(val settings: BkxSettings) : BkxHomeEvent

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
    private val requestSequence = AtomicLong(0L)
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
        refreshBackend()
        refreshRuns()
        refreshOperations()
        refreshSettings()
    }

    fun onEvent(event: BkxHomeEvent) {
        when (event) {
            BkxHomeEvent.RefreshBackend -> refreshBackend()
            BkxHomeEvent.RefreshRuns -> refreshRuns()
            is BkxHomeEvent.SelectDestination -> _state.update { it.copy(selectedDestination = event.destination) }

            is BkxHomeEvent.UpdateBackupDraft -> _state.update { current ->
                current.copy(backupPlanning = BackupPlanningUiState(draft = event.draft))
            }
            is BkxHomeEvent.ApplyBackupPlanPreview -> _state.update { current ->
                current.copy(backupPlanning = BackupPlanningUiState.withPreview(current.backupPlanning.draft, event.preview))
            }
            BkxHomeEvent.RequestBackupPlan -> requestBackupPlan()
            BkxHomeEvent.CreateBackup -> createBackup()

            is BkxHomeEvent.SelectArchive -> _state.update { current ->
                current.copy(archiveReadOnly = current.archiveReadOnly.copy(selectedArchive = event.archive))
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
            is BkxHomeEvent.BrowseArchive -> browseArchive(event.archive, event.pageToken)
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

            is BkxHomeEvent.UpdateSettings -> updateSettings(event.settings)

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
        executeRequest(request, recordInHistory = true) { result ->
            if (result.status == BridgeResultStatus.Success) refreshArchives()
        }
    }

    private fun refreshArchives() {
        val request = archiveRequestBuilder.buildListRequest(nextRequestId("archives-list"), roots = archiveRootsProvider(_state.value.rootAvailable))
        _state.update { current ->
            current.copy(
                archiveReadOnly = current.archiveReadOnly.copy(listState = ArchiveWorkflowUiState.Loading),
                screenStatuses = current.screenStatuses + (BkxDestination.Archives to ScreenStatus.Loading),
            )
        }
        executeRequest(request, recordInHistory = false) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val archives = result.artifacts
                    .filter { it.label == "archive" && it.path.startsWith("/") }
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
            )
        }
        val request = archiveRequestBuilder.buildInfoRequest(nextRequestId("archive-info"), archive)
        executeRequest(request, recordInHistory = false) { result ->
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
        executeRequest(request, recordInHistory = false) { result ->
            if (result.status != BridgeResultStatus.Success) return@executeRequest
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
        executeRequest(request, recordInHistory = true) { result ->
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
                restore = RestoreUiState(draft = current.copy(archive = archive)),
            )
        }
    }

    private fun requestRestorePreview() {
        val draft = _state.value.restore.draft
        if (draft.validationIssues().isNotEmpty()) return
        val request = restoreRequestBuilder.buildPreviewRequest(nextRequestId("restore-preview"), draft)
        executeRequest(request, recordInHistory = false) { result ->
            if (result.status == BridgeResultStatus.Success) {
                _state.update { current -> current.copy(restore = current.restore.copy(preview = result.toRestorePreviewSummary(draft), confirmation = null)) }
            }
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
        executeRequest(request, recordInHistory = true)
    }

    private fun requestTransferPreview() {
        val draft = _state.value.transfer.draft
        if (draft.validationIssues().isNotEmpty()) return
        val request = transferRequestBuilder.buildPreviewRequest(nextRequestId("transfer-plan"), draft)
        executeRequest(request, recordInHistory = false) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val topology = buildString {
                    append(draft.mode.protocolValue.replaceFirstChar { it.uppercase() })
                    append(" · ")
                    append(draft.sources.size)
                    append(if (draft.sources.size == 1) " source" else " sources")
                    append(" → ")
                    append(draft.destination?.label ?: "destination")
                }
                val preview = TransferPreviewSummary(
                    requestId = result.requestId,
                    draftFingerprint = draft.fingerprint(),
                    topologyLabel = topology,
                    mappings = emptyList(),
                    collisionSummary = TransferCollisionSummary(
                        collisions = result.errors.filter { it.code.contains("collision", true) }.map { it.message },
                        overlaps = result.errors.filter { it.code.contains("overlap", true) }.map { it.message },
                        symlinkPolicy = draft.symlinkPolicy,
                        specialFilePolicy = draft.specialFilePolicy,
                    ),
                    verificationPlan = TransferVerificationPlan(
                        enabled = draft.verifyAfterTransfer,
                        checksum = draft.checksum,
                    ),
                    warnings = result.errors.filterNot {
                        it.code.contains("collision", true) || it.code.contains("overlap", true)
                    }.map { it.message },
                )
                _state.update { current -> current.copy(transfer = current.transfer.copy(preview = preview)) }
            }
        }
    }

    private fun runTransfer() {
        val transfer = _state.value.transfer
        if (!transfer.canRun) return
        val request = transferRequestBuilder.buildRunRequest(nextRequestId("transfer-run"), transfer.draft, transfer.preview)
        executeRequest(request, recordInHistory = true) { result ->
            _state.update { current -> current.copy(transfer = current.transfer.copy(result = result.toTransferResultUiState())) }
        }
    }

    private fun executeRequest(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
        surfaceResult: Boolean = true,
        onResult: (BridgeResultEnvelope) -> Unit = {},
    ) {
        viewModelScope.launch {
            when (val outcome = operationCoordinator.execute(request, recordInHistory)) {
                is OperationOutcome.Completed -> {
                    val result = outcome.result
                    if (surfaceResult) _state.update { current -> current.copy(operationResult = result.toOperationResultUiState()) }
                    onResult(result)
                    refreshOperations()
                    if (recordInHistory) refreshRuns()
                }
                is OperationOutcome.Failed -> {
                    if (surfaceResult) _state.update { current -> current.copy(operationResult = OperationResultUiState.Failed(outcome.failure)) }
                    refreshOperations()
                }
                is OperationOutcome.CancellationAccepted -> refreshOperations()
            }
        }
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
        val path = initialPath.takeIf { it.startsWith("/") } ?: "/storage/emulated/0"
        _state.update { current ->
            current.copy(filesystemBrowser = FilesystemBrowserUiState(open = true, target = target, currentPath = path, loading = true))
        }
        loadFilesystem(path)
    }

    private fun loadFilesystem(path: String) {
        val browser = _state.value.filesystemBrowser
        if (!browser.open || !path.startsWith("/")) return
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
        executeRequest(request, recordInHistory = false, surfaceResult = false) { result ->
            if (result.status == BridgeResultStatus.Success) {
                val entries = result.artifacts.map { artifact ->
                    FilesystemBrowserEntry(
                        path = artifact.path,
                        name = artifact.path.substringAfterLast('/').ifBlank { artifact.path },
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
        val target = _state.value.filesystemBrowser.target ?: return
        if (target.folderOnly && !isDirectory) return
        when (target) {
            FilesystemSelectionTarget.BackupSource -> {
                val draft = _state.value.backupPlanning.draft
                onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = BackupSourceSelection("path-${path.hashCode()}", path.substringAfterLast('/').ifBlank { path }, path))))
            }
            FilesystemSelectionTarget.BackupDestination -> {
                val draft = _state.value.backupPlanning.draft
                onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(destination = BackupDestinationSelection("path-${path.hashCode()}", path.substringAfterLast('/').ifBlank { path }, path))))
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
                onEvent(BkxHomeEvent.UpdateRestoreDraft(draft.copy(destination = RestoreDestinationSelection("path-${path.hashCode()}", path.substringAfterLast('/').ifBlank { path }, path))))
            }
            FilesystemSelectionTarget.TransferSource -> {
                val draft = _state.value.transfer.draft
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = listOf(TransferEndpoint("path-${path.hashCode()}", path.substringAfterLast('/').ifBlank { path }, path)))))
            }
            FilesystemSelectionTarget.TransferDestination -> {
                val draft = _state.value.transfer.draft
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(destination = TransferEndpoint("path-${path.hashCode()}", path.substringAfterLast('/').ifBlank { path }, path))))
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
        viewModelScope.launch {
            val runs = runSummaryStore.recentRuns(limit = 20)
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

    private fun archiveLocation(path: String): ArchiveLocation = ArchiveLocation(
        archiveId = "archive-${path.hashCode().toUInt().toString(16)}",
        displayName = path.substringAfterLast('/').ifBlank { "Archive" },
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
