package com.mikeyphw.bkx.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.uiTruthSurface
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
import com.mikeyphw.bkx.android.core.runs.RunStatus
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
        .maxByOrNull { it.lastSeenEpochMillis }
    val lastBackup = state.recentRuns.firstOrNull { it.operation == "backup.create" && it.status == RunStatus.Success }
    val truth = state.uiTruthSurface()
    val setupNeedsAttention = state.readiness !is BackendReadiness.Ready || !storageAccess.hasAllFilesAccess()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
        ) {
            BkxPageHeader(
                title = "BKX",
                subtitle = "Back up, verify, and restore your files without leaving the device workflow.",
            )

            if (setupNeedsAttention) {
                BkxHeroCard(
                    title = if (state.readiness is BackendReadiness.Ready) "File access needed" else "Finish setup",
                    body = if (!storageAccess.hasAllFilesAccess()) {
                        "Allow shared-storage access so BKX can create and restore archives in normal device storage."
                    } else {
                        state.readiness.message
                    },
                    badge = "SETUP",
                ) {
                    if (!storageAccess.hasAllFilesAccess()) {
                        Button(onClick = storageAccess::openGrantSettings) { Text("Grant file access") }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Ready to protect your files", style = MaterialTheme.typography.titleMedium)
                        Text(truth.engine.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            truth.engine.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BkxStatusPill(truth.engine.badge)
                }
            }

            if (active != null) {
                BkxSectionHeader("In progress", "Open activity") { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Activity)) }
                BkxSurfaceCard {
                    BkxOperationProgress(
                        title = operationLabel(active.operation),
                        phase = active.lastMessage.ifBlank { active.lastPhase },
                        progress = active.progress,
                        filesDone = active.filesDone,
                        filesTotal = active.filesTotal,
                        bytesDone = active.bytesDone,
                        bytesTotal = active.bytesTotal,
                    )
                    BkxStatusPill(
                        if (active.status == ActiveOperationStatus.Cancelling) "CANCELLING" else "RUNNING",
                        if (active.status == ActiveOperationStatus.Cancelling) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (wide) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BkxTokens.CardGap)) {
                        LastBackupCard(lastBackup, onEvent)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BkxTokens.CardGap)) {
                        QuickActions(onEvent)
                    }
                }
            } else {
                LastBackupCard(lastBackup, onEvent)
                QuickActions(onEvent)
            }

            BkxSectionHeader("Recent activity", "See all") { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Activity)) }
            if (state.recentRuns.isEmpty()) {
                BkxEmptyState("Nothing here yet", "Completed backups, restores, archive checks, and transfers will appear here.")
            } else {
                state.recentRuns.take(4).forEach { run ->
                    BkxSurfaceCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(operationLabel(run.operation), style = MaterialTheme.typography.titleMedium)
                                Text(formatTime(run.finishedAtEpochMillis ?: run.startedAtEpochMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val accent = when (run.status) {
                                RunStatus.Failure -> MaterialTheme.colorScheme.error
                                RunStatus.Cancelled -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                            BkxStatusPill(run.status.name.uppercase(), accent)
                        }
                        if (run.summary.isNotBlank()) Text(run.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LastBackupCard(
    lastBackup: com.mikeyphw.bkx.android.core.runs.RunSummary?,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BkxSectionHeader("Last backup")
    BkxSurfaceCard {
        if (lastBackup == null) {
            Text("No completed backup yet", style = MaterialTheme.typography.titleMedium)
            Text("Create your first BKX archive to establish a restore point.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(lastBackup.artifacts.firstOrNull()?.displayName ?: "Backup", style = MaterialTheme.typography.titleLarge)
                    Text(formatTime(lastBackup.finishedAtEpochMillis ?: lastBackup.startedAtEpochMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BkxStatusPill(if (lastBackup.verification.name == "Passed") "VERIFIED" else "COMPLETE")
            }
            if (lastBackup.summary.isNotBlank()) Text(lastBackup.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Backup)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Back up now")
        }
    }
}

@Composable
private fun QuickActions(onEvent: (BkxHomeEvent) -> Unit) {
    BkxSectionHeader("Quick actions")
    BkxSurfaceCard {
        FilledTonalButton(
            onClick = {
                onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Archives))
                onEvent(BkxHomeEvent.RefreshArchives)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Browse archives") }
        FilledTonalButton(onClick = { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Activity)) }, modifier = Modifier.fillMaxWidth()) {
            Text("View activity")
        }
    }
}
