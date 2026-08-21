package com.mikeyphw.bkx.android.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.ui.components.BkxEmptyState
import com.mikeyphw.bkx.android.app.ui.components.BkxOperationProgress
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.core.settings.LogPrivacyMode
import com.mikeyphw.bkx.android.feature.home.formatTime
import com.mikeyphw.bkx.android.feature.home.operationLabel

@Composable
fun ActivityScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveOperations = state.operationRuntime.activeOperations.values
        .filter { it.status in setOf(ActiveOperationStatus.Queued, ActiveOperationStatus.Running, ActiveOperationStatus.Cancelling) }
        .sortedByDescending { it.lastSeenEpochMillis }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader(
            title = "Activity",
            subtitle = if (state.settings.logPrivacyMode == LogPrivacyMode.Private) "Live operations and a private, path-free history of completed BKX work." else "Live operations with diagnostic request identifiers and compact completed history.",
        )

        BkxSectionHeader("In progress")
        if (liveOperations.isEmpty()) {
            BkxEmptyState("Nothing is running", "Start a backup, restore, verification, or transfer and its progress will appear here.")
        } else {
            liveOperations.forEach { operation ->
                BkxSurfaceCard {
                    BkxOperationProgress(
                        title = operationLabel(operation.operation),
                        phase = operation.lastMessage.ifBlank { operation.lastPhase },
                        progress = operation.progress,
                    )
                    if (operation.status == ActiveOperationStatus.Cancelling) {
                        BkxStatusPill("STOP REQUESTED", MaterialTheme.colorScheme.tertiary)
                    } else {
                        BkxStatusPill("RUNNING")
                    }
                    if (state.settings.logPrivacyMode == LogPrivacyMode.Diagnostic) {
                        Text("Request ${operation.requestId.take(16)} · seq ${operation.lastSeq}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (operation.cancelFilePath != null && operation.status != ActiveOperationStatus.Cancelling) {
                        OutlinedButton(
                            onClick = {
                                onEvent(
                                    BkxHomeEvent.SubmitOperationCancellation(
                                        requestId = operation.requestId,
                                        cancelFilePath = operation.cancelFilePath,
                                        cancelRequestId = "cancel-${System.currentTimeMillis()}",
                                        requestedAtEpochMillis = System.currentTimeMillis(),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel operation") }
                    }
                    state.operationRuntime.failureStates[operation.requestId]?.let { failure ->
                        Text(failure.title, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                        Text(failure.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        when (val result = state.operationResult) {
            OperationResultUiState.NotStarted -> Unit
            is OperationResultUiState.Success -> BkxSurfaceCard {
                BkxStatusPill("COMPLETED")
                Text(result.summary, style = MaterialTheme.typography.bodyLarge)
            }
            is OperationResultUiState.Cancelled -> BkxSurfaceCard {
                BkxStatusPill("CANCELLED", MaterialTheme.colorScheme.tertiary)
                Text(result.summary)
            }
            is OperationResultUiState.Failed -> BkxSurfaceCard {
                BkxStatusPill("FAILED", MaterialTheme.colorScheme.error)
                Text(result.failure.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(result.failure.message)
                result.failure.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }

        BkxSectionHeader("History", "Refresh") { onEvent(BkxHomeEvent.RefreshRuns) }
        if (state.recentRuns.isEmpty()) {
            BkxEmptyState("No completed work yet", "BKX stores only a compact activity summary here; full filesystem paths are intentionally omitted.")
        } else {
            state.recentRuns.forEach { run ->
                BkxSurfaceCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(operationLabel(run.operation), style = MaterialTheme.typography.titleMedium)
                            Text(formatTime(run.finishedAtEpochMillis ?: run.startedAtEpochMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BkxStatusPill(run.status.name.uppercase(), if (run.status.name == "Failure") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    if (run.summary.isNotBlank()) Text(run.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (run.artifacts.isNotEmpty()) {
                        Text(
                            run.artifacts.joinToString(prefix = "Output: ", separator = ", ") { it.displayName },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }, modifier = Modifier.fillMaxWidth()) {
            Text("Check BKX engine")
        }
    }
}
