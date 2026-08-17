package com.mikeyphw.bkx.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.mikeyphw.bkx.android.core.backend.BackendReadiness

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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Thin Android interface over the BKX engine contract.",
                style = MaterialTheme.typography.bodyMedium,
            )
            BackendReadinessCard(state.readiness)
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onEvent(BkxHomeEvent.RefreshBackend) },
                    enabled = !state.isRefreshing,
                ) {
                    Text(if (state.isRefreshing) "Checking…" else "Check Termux backend")
                }
            }
        }
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
