package com.mikeyphw.bkx.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.ui.components.BkxEmptyState
import com.mikeyphw.bkx.android.app.ui.components.BkxHeroCard
import com.mikeyphw.bkx.android.app.ui.components.BkxOperationProgress
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.storage.AndroidStorageAccessManager

@Composable
fun HomeScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storageAccess = AndroidStorageAccessManager(context)
    val active = state.operationRuntime.activeOperations.values
        .filter { it.status == ActiveOperationStatus.Queued || it.status == ActiveOperationStatus.Running || it.status == ActiveOperationStatus.Cancelling }
        .sortedByDescending { it.lastSeenEpochMillis }
        .firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader(
            title = "Your backups, without the bridge",
            subtitle = "BKX runs inside the app. Root unlocks private app data and Termux storage.",
        )

        BkxHeroCard(
            title = when {
                state.rootAvailable -> "Root access is ready"
                state.readiness is BackendReadiness.Ready -> "BKX is ready"
                else -> "Finish setup"
            },
            body = when (val readiness = state.readiness) {
                is BackendReadiness.Ready -> if (readiness.isRoot) {
                    "You can back up shared storage, Termux home and prefix, app-private data, and other root-readable paths."
                } else {
                    "Shared storage is available. Grant root when you want BKX to reach private app data and Termux internals."
                }
                else -> readiness.message
            },
            badge = when {
                state.rootAvailable -> "ROOT READY"
                state.readiness is BackendReadiness.Ready -> "APP ACCESS"
                else -> "SETUP"
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Backup)) }) {
                    Text("Back up now")
                }
                FilledTonalButton(onClick = {
                    onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Archives))
                    onEvent(BkxHomeEvent.RefreshArchives)
                }) {
                    Text("Archives")
                }
            }
        }

        if (!storageAccess.hasAllFilesAccess()) {
            BkxSurfaceCard {
                BkxStatusPill("FILE ACCESS NEEDED", MaterialTheme.colorScheme.tertiary)
                Text("Give BKX access to shared storage", style = MaterialTheme.typography.titleLarge)
                Text(
                    "This is separate from root. It lets BKX work directly with normal files under internal storage, SD cards, and USB storage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = storageAccess::openGrantSettings) { Text("Grant file access") }
            }
        }

        if (active != null) {
            BkxSectionHeader("In progress", "Open activity") {
                onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Activity))
            }
            BkxSurfaceCard {
                BkxOperationProgress(
                    title = operationLabel(active.operation),
                    phase = active.lastMessage.ifBlank { active.lastPhase },
                    progress = active.progress,
                )
                Text(
                    text = when (active.status) {
                        ActiveOperationStatus.Queued -> "Queued"
                        ActiveOperationStatus.Running -> "Running"
                        ActiveOperationStatus.Cancelling -> "Cancelling"
                        else -> active.status.name
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        BkxSectionHeader("Recent activity", "See all") {
            onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Activity))
        }
        if (state.recentRuns.isEmpty()) {
            BkxEmptyState(
                title = "Nothing here yet",
                body = "Your completed backups, restores, archive checks, and transfers will appear here.",
            )
        } else {
            state.recentRuns.take(4).forEach { run ->
                BkxSurfaceCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(operationLabel(run.operation), style = MaterialTheme.typography.titleMedium)
                            Text(formatTime(run.finishedAtEpochMillis ?: run.startedAtEpochMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BkxStatusPill(run.status.name.uppercase())
                    }
                    if (run.summary.isNotBlank()) {
                        Text(run.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        BkxSectionHeader("Engine")
        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.backendToolState.runtimeLabel, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "BKX ${state.backendToolState.bkxVersion ?: "checking"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }, enabled = !state.isRefreshing) {
                    Text(if (state.isRefreshing) "Checking…" else "Check")
                }
            }
        }
    }
}
