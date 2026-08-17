package com.mikeyphw.bkx.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.bkx.android.app.navigation.BackendToolState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.archives.ArchiveBrowsePage
import com.mikeyphw.bkx.android.core.archives.ArchiveInfoSummary
import com.mikeyphw.bkx.android.core.archives.ArchiveListItem
import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.archives.ArchiveWorkflowUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveReadOnlyUiState
import com.mikeyphw.bkx.android.core.archives.toArchiveVerificationUiState
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupPlanPreview
import com.mikeyphw.bkx.android.core.backup.BackupPlanningUiState
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.operations.OperationFailureMapper
import com.mikeyphw.bkx.android.core.operations.OperationFailureUiState
import com.mikeyphw.bkx.android.core.operations.OperationProgressTracker
import com.mikeyphw.bkx.android.core.operations.OperationReconnectSnapshot
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.core.operations.OperationRuntimeState
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.operations.toOperationResultUiState
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.restore.RestoreDraft
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestorePreviewSummary
import com.mikeyphw.bkx.android.core.restore.RestoreUiState
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.BkxSettings
import com.mikeyphw.bkx.android.core.transfer.TransferPreviewSummary
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import com.mikeyphw.bkx.android.core.transfer.TransferUiState
import com.mikeyphw.bkx.android.core.transfer.toTransferResultUiState
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import com.mikeyphw.bkx.android.core.usecase.CheckBackendReadinessUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BkxHomeUiState(
    val title: String = "BKX Companion",
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
    val settings: BkxSettings = BkxSettings(),
    val screenStatuses: Map<BkxDestination, ScreenStatus> = initialScreenStatuses(),
) {
    val backendToolState: BackendToolState = BackendToolState(
        readiness = readiness,
        protocolVersion = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
        bkxVersion = (readiness as? BackendReadiness.Ready)?.backendVersion,
        capabilities = (readiness as? BackendReadiness.Ready)?.capabilities?.sorted().orEmpty(),
    )
}

sealed interface BkxHomeEvent {
    data object RefreshBackend : BkxHomeEvent
    data object RefreshRuns : BkxHomeEvent
    data class SelectDestination(val destination: BkxDestination) : BkxHomeEvent
    data class UpdateBackupDraft(val draft: BackupPlanDraft) : BkxHomeEvent
    data class ApplyBackupPlanPreview(val preview: BackupPlanPreview) : BkxHomeEvent
    data class SelectArchive(val archive: ArchiveLocation) : BkxHomeEvent
    data class ApplyArchiveList(val archives: List<ArchiveListItem>) : BkxHomeEvent
    data class ApplyArchiveInfo(val info: ArchiveInfoSummary) : BkxHomeEvent
    data class ApplyArchiveBrowsePage(val page: ArchiveBrowsePage) : BkxHomeEvent
    data class ApplyArchiveVerification(val result: BridgeResultEnvelope) : BkxHomeEvent
    data class UpdateRestoreDraft(val draft: RestoreDraft) : BkxHomeEvent
    data class ApplyRestorePreview(val preview: RestorePreviewSummary) : BkxHomeEvent
    data class ConfirmRestoreExtraction(val confirmation: RestoreExtractionConfirmation) : BkxHomeEvent
    data class UpdateTransferDraft(val draft: TransferTopologyDraft) : BkxHomeEvent
    data class ApplyTransferPreview(val preview: TransferPreviewSummary) : BkxHomeEvent
    data class ApplyTransferResult(val result: BridgeResultEnvelope) : BkxHomeEvent
    data class TrackOperationStarted(
        val requestId: String,
        val operation: String,
        val startedAtEpochMillis: Long,
        val cancelFilePath: String? = null,
    ) : BkxHomeEvent
    data class ConsumeOperationProgress(val progress: BridgeProgressEnvelope, val observedAtEpochMillis: Long) : BkxHomeEvent
    data class ApplyOperationFinalResult(val result: BridgeResultEnvelope, val completedAtEpochMillis: Long) : BkxHomeEvent
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
) : ViewModel() {
    private val checkBackendReadiness = CheckBackendReadinessUseCase(backendPort)
    private val operationProgressTracker = OperationProgressTracker()
    private val _state = MutableStateFlow(BkxHomeUiState())
    val state: StateFlow<BkxHomeUiState> = _state.asStateFlow()

    init {
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
            is BkxHomeEvent.UpdateRestoreDraft -> _state.update { current ->
                current.copy(restore = RestoreUiState(draft = event.draft))
            }
            is BkxHomeEvent.ApplyRestorePreview -> _state.update { current ->
                current.copy(restore = current.restore.copy(preview = event.preview, confirmation = null))
            }
            is BkxHomeEvent.ConfirmRestoreExtraction -> _state.update { current ->
                current.copy(restore = current.restore.copy(confirmation = event.confirmation))
            }
            is BkxHomeEvent.UpdateTransferDraft -> _state.update { current ->
                current.copy(transfer = TransferUiState(draft = event.draft))
            }
            is BkxHomeEvent.ApplyTransferPreview -> _state.update { current ->
                current.copy(transfer = current.transfer.copy(preview = event.preview))
            }
            is BkxHomeEvent.ApplyTransferResult -> _state.update { current ->
                current.copy(transfer = current.transfer.copy(result = event.result.toTransferResultUiState()))
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
            is BkxHomeEvent.ApplyOperationFinalResult -> {
                updateOperationRuntime { runtime ->
                    operationProgressTracker.finish(runtime, event.result, event.completedAtEpochMillis)
                }
                _state.update { current -> current.copy(operationResult = event.result.toOperationResultUiState()) }
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
            is BkxHomeEvent.FocusActiveOperation -> updateOperationRuntimeAndSelectHome { runtime ->
                operationProgressTracker.focus(runtime, event.requestId)
            }
        }
    }

    private fun submitOperationCancellation(event: BkxHomeEvent.SubmitOperationCancellation) {
        updateOperationRuntime { runtime ->
            operationProgressTracker.markCancelling(
                state = runtime,
                requestId = event.requestId,
                cancelRequestId = event.cancelRequestId,
                nowEpochMillis = event.requestedAtEpochMillis,
            )
        }
        viewModelScope.launch {
            val submission = backendPort.cancel(event.requestId, event.cancelFilePath)
            if (!submission.accepted) {
                val failure = OperationFailureMapper.fromBackendReadiness(submission.readiness)
                updateOperationRuntime { runtime ->
                    operationProgressTracker.fail(
                        state = runtime,
                        requestId = event.requestId,
                        failure = failure,
                        nowEpochMillis = event.requestedAtEpochMillis,
                    )
                }
                _state.update { current -> current.copy(operationResult = OperationResultUiState.Failed(failure)) }
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
                        BkxDestination.Home to if (runs.isEmpty()) {
                            ScreenStatus.Empty("No BKX runs are stored on this device yet.")
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
            val runtime = operationStateStore.read()
            _state.update { current ->
                val focusedRequestId = current.operationRuntime.focusedRequestId
                current.copy(
                    operationRuntime = if (focusedRequestId == null) {
                        runtime
                    } else {
                        runtime.copy(focusedRequestId = focusedRequestId)
                    },
                )
            }
        }
    }

    private fun updateOperationRuntimeAndSelectHome(transform: (OperationRuntimeState) -> OperationRuntimeState) {
        var nextRuntime: OperationRuntimeState? = null
        _state.update { current ->
            val next = transform(current.operationRuntime)
            nextRuntime = next
            current.copy(selectedDestination = BkxDestination.Home, operationRuntime = next)
        }
        nextRuntime?.let { runtime ->
            viewModelScope.launch { operationStateStore.update(runtime) }
        }
    }

    private fun updateOperationRuntime(transform: (OperationRuntimeState) -> OperationRuntimeState) {
        var nextRuntime: OperationRuntimeState? = null
        _state.update { current ->
            val next = transform(current.operationRuntime)
            nextRuntime = next
            current.copy(operationRuntime = next)
        }
        nextRuntime?.let { runtime ->
            viewModelScope.launch { operationStateStore.update(runtime) }
        }
    }

    private fun refreshSettings() {
        viewModelScope.launch {
            val settings = settingsStore.read()
            _state.update { it.copy(settings = settings) }
        }
    }
}

class BkxAppViewModelFactory(
    private val backendPort: BackendPort,
    private val runSummaryStore: RunSummaryStore,
    private val operationStateStore: OperationStateStore,
    private val settingsStore: SettingsStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BkxAppViewModel::class.java)) {
            return BkxAppViewModel(backendPort, runSummaryStore, operationStateStore, settingsStore) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}

private fun initialScreenStatuses(): Map<BkxDestination, ScreenStatus> = mapOf(
    BkxDestination.Home to ScreenStatus.Loading,
    BkxDestination.Backup to ScreenStatus.Content,
    BkxDestination.Archives to ScreenStatus.Content,
    BkxDestination.Restore to ScreenStatus.Content,
    BkxDestination.Transfers to ScreenStatus.Content,
    BkxDestination.Tools to ScreenStatus.Content,
    BkxDestination.Settings to ScreenStatus.Content,
)

private fun Map<BkxDestination, ScreenStatus>.withBackendStatus(readiness: BackendReadiness): Map<BkxDestination, ScreenStatus> =
    this + (BkxDestination.Tools to if (readiness is BackendReadiness.ProbeFailed) {
        ScreenStatus.Error(readiness.title, readiness.message)
    } else {
        ScreenStatus.Content
    })
