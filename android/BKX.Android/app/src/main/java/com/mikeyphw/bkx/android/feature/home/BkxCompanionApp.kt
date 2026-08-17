package com.mikeyphw.bkx.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxAppViewModel
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BackendToolState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.settings.BkxSettings

@Composable
fun BkxCompanionApp(viewModel: BkxAppViewModel) {
    val state by viewModel.state.collectAsState()
    BkxHomeScreen(
        state = state,
        onEvent = viewModel::onEvent,
    )
}

@Composable
fun BkxHomeScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Thin Android interface over the BKX engine contract.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NavigationGraphTabs(state, onEvent)
            when (state.selectedDestination) {
                BkxDestination.Home -> HomeRecentRunsScreen(state, onEvent)
                BkxDestination.Tools -> ToolsBackendScreen(state.backendToolState, state.isRefreshing, onEvent)
                BkxDestination.Settings -> SettingsScreen(state.settings)
                BkxDestination.Backup,
                BkxDestination.Archives,
                BkxDestination.Restore,
                BkxDestination.Transfers -> InitialScreenPanel(
                    destination = state.selectedDestination,
                    status = state.screenStatuses[state.selectedDestination] ?: ScreenStatus.Loading,
                )
            }
        }
    }
}

@Composable
private fun NavigationGraphTabs(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.destinations.chunked(2).forEach { rowDestinations ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowDestinations.forEach { destination ->
                    val selected = destination == state.selectedDestination
                    if (selected) {
                        Button(onClick = { onEvent(BkxHomeEvent.SelectDestination(destination)) }) {
                            Text(destination.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onEvent(BkxHomeEvent.SelectDestination(destination)) }) {
                            Text(destination.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecentRunsScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BackendReadinessCard(state.readiness)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onEvent(BkxHomeEvent.RefreshBackend) },
            enabled = !state.isRefreshing,
        ) {
            Text(if (state.isRefreshing) "Checking…" else "Check Termux backend")
        }
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshRuns) }) {
            Text("Refresh runs")
        }
    }
    RecentRunsCard(state.recentRuns, state.screenStatuses[BkxDestination.Home] ?: ScreenStatus.Loading)
}

@Composable
private fun RecentRunsCard(runs: List<RunSummary>, status: ScreenStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Recent runs", style = MaterialTheme.typography.titleMedium)
            if (runs.isEmpty()) {
                InitialStatusText(status)
            } else {
                runs.forEach { run ->
                    Text(
                        text = "${run.operation}: ${run.status} • ${run.durationMillis ?: 0} ms • verification ${run.verification}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    run.artifacts.forEach { artifact ->
                        Text(text = "Artifact: ${artifact.displayName} (${artifact.label})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsBackendScreen(
    tools: BackendToolState,
    isRefreshing: Boolean,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BackendReadinessCard(tools.readiness)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Backend tools", style = MaterialTheme.typography.titleMedium)
            Text(text = "Wrapper: ${tools.wrapperPath}")
            Text(text = "Bridge protocol: ${tools.protocolVersion}")
            Text(text = "BKX version: ${tools.bkxVersion ?: "not verified"}")
            Text(text = "Capabilities: ${tools.capabilities.ifEmpty { listOf("not verified") }.joinToString()}")
            Button(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }, enabled = !isRefreshing) {
                Text(if (isRefreshing) "Checking…" else "Re-check backend")
            }
        }
    }
    tools.remediationActions.forEach { action ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = action.title, style = MaterialTheme.typography.titleMedium)
                action.steps.forEach { step -> Text(text = "• $step", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: BkxSettings) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Settings", style = MaterialTheme.typography.titleMedium)
            Text(text = "Backend: ${settings.backend}")
            Text(text = "Staging: ${settings.stagingLocationLabel}")
            Text(text = "Log privacy: ${settings.logPrivacyMode}")
            Text(text = "Progress: ${settings.progressPreference}")
            Text(
                text = "SAF grants are kept deliberate; content URIs are staged before BKX receives filesystem paths.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InitialScreenPanel(destination: BkxDestination, status: ScreenStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = destination.label, style = MaterialTheme.typography.titleMedium)
            InitialStatusText(status)
        }
    }
}

@Composable
private fun InitialStatusText(status: ScreenStatus) {
    when (status) {
        ScreenStatus.Loading -> Text("Loading…")
        is ScreenStatus.Empty -> Text(status.message)
        is ScreenStatus.Error -> Text("${status.title}: ${status.message}")
        ScreenStatus.Content -> Text("Ready.")
    }
}

@Composable
private fun BackendReadinessCard(readiness: BackendReadiness) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = readiness.title, style = MaterialTheme.typography.titleMedium)
            Text(text = readiness.message, style = MaterialTheme.typography.bodyMedium)
            readiness.remediationSteps.forEach { step ->
                Text(text = "• $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
