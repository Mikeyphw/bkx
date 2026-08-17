package com.mikeyphw.bkx.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.bkx.android.app.navigation.BackendToolState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.BkxSettings
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
}

class BkxAppViewModel(
    private val backendPort: BackendPort,
    private val runSummaryStore: RunSummaryStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val checkBackendReadiness = CheckBackendReadinessUseCase(backendPort)
    private val _state = MutableStateFlow(BkxHomeUiState())
    val state: StateFlow<BkxHomeUiState> = _state.asStateFlow()

    init {
        refreshBackend()
        refreshRuns()
        refreshSettings()
    }

    fun onEvent(event: BkxHomeEvent) {
        when (event) {
            BkxHomeEvent.RefreshBackend -> refreshBackend()
            BkxHomeEvent.RefreshRuns -> refreshRuns()
            is BkxHomeEvent.SelectDestination -> _state.update { it.copy(selectedDestination = event.destination) }
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
    private val settingsStore: SettingsStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BkxAppViewModel::class.java)) {
            return BkxAppViewModel(backendPort, runSummaryStore, settingsStore) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}

private fun initialScreenStatuses(): Map<BkxDestination, ScreenStatus> = mapOf(
    BkxDestination.Home to ScreenStatus.Loading,
    BkxDestination.Backup to ScreenStatus.Empty("Backup planning starts in AOV-05 after file access is safe."),
    BkxDestination.Archives to ScreenStatus.Empty("Read-only archive workflows start in AOV-06."),
    BkxDestination.Restore to ScreenStatus.Empty("Restore safety gates start in AOV-07."),
    BkxDestination.Transfers to ScreenStatus.Empty("Transfer topology and verification gates start in AOV-08."),
    BkxDestination.Tools to ScreenStatus.Content,
    BkxDestination.Settings to ScreenStatus.Content,
)

private fun Map<BkxDestination, ScreenStatus>.withBackendStatus(readiness: BackendReadiness): Map<BkxDestination, ScreenStatus> =
    this + (BkxDestination.Tools to if (readiness is BackendReadiness.ProbeFailed) {
        ScreenStatus.Error(readiness.title, readiness.message)
    } else {
        ScreenStatus.Content
    })
