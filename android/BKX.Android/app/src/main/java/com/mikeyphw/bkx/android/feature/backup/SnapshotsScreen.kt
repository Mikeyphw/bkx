package com.mikeyphw.bkx.android.feature.backup

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxOperationProgress
import com.mikeyphw.bkx.android.app.ui.components.BkxOutlinedSurface
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.core.repositories.SnapshotDescriptor
import com.mikeyphw.bkx.android.core.repositories.SnapshotNodeDescriptor
import com.mikeyphw.bkx.android.core.repositories.SnapshotOverwriteMode
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun SnapshotsScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshotState = state.snapshots
    val activeRestore = state.operationRuntime.activeOperations.values
        .filter {
            it.operation == "snapshots.restore" &&
                it.status in setOf(ActiveOperationStatus.Queued, ActiveOperationStatus.Running, ActiveOperationStatus.Cancelling)
        }
        .maxByOrNull { it.lastSeenEpochMillis }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmForgetId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader(
            title = "Snapshots",
            subtitle = "Browse incremental backups, compare changes, select files or folders, and restore them without mounting the repository.",
        )

        BkxSectionHeader(title = "Repository", action = "Refresh", onAction = { onEvent(BkxHomeEvent.RefreshSnapshots) })
        if (state.repositories.items.isEmpty()) {
            BkxSurfaceCard { Text("Add a Restic repository first. Snapshot browsing uses the same repository definitions as the CLI.") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.repositories.items.forEach { repository ->
                    FilterChip(
                        selected = snapshotState.repositoryId == repository.repositoryId,
                        onClick = { onEvent(BkxHomeEvent.SelectSnapshotRepository(repository.repositoryId)) },
                        label = { Text(repository.name) },
                    )
                }
            }
        }

        when (val status = snapshotState.status) {
            ScreenStatus.Loading -> BkxSurfaceCard { Text("Loading snapshots…") }
            is ScreenStatus.Empty -> BkxSurfaceCard { Text(status.message) }
            is ScreenStatus.Error -> BkxSurfaceCard {
                BkxStatusPill("ERROR", MaterialTheme.colorScheme.error)
                Text(status.title, style = MaterialTheme.typography.titleMedium)
                Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScreenStatus.Content -> SnapshotList(snapshotState.items, snapshotState.selectedSnapshot, onEvent)
        }

        snapshotState.selectedSnapshot?.let { selected ->
            SnapshotDetail(
                state = state,
                snapshot = selected,
                activeRestore = activeRestore,
                onEvent = onEvent,
                onConfirmRestore = { confirmRestore = true },
                onConfirmForget = { confirmForgetId = selected.id },
            )
        }

        snapshotState.message?.takeIf { it.isNotBlank() }?.let { message ->
            BkxSurfaceCard { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (confirmRestore) {
        val selectedCount = snapshotState.selectedRestorePaths.size
        val scope = if (selectedCount == 0) "the whole snapshot" else "$selectedCount selected path${if (selectedCount == 1) "" else "s"}"
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore snapshot?") },
            text = {
                Text(
                    "BKX will restore $scope into ${snapshotState.restoreTarget}. Existing files use the ${overwriteLabel(snapshotState.overwriteMode).lowercase()} policy.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    onEvent(BkxHomeEvent.ExecuteSnapshotRestore)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }

    confirmForgetId?.let { snapshotId ->
        AlertDialog(
            onDismissRequest = { confirmForgetId = null },
            title = { Text("Forget snapshot?") },
            text = { Text("This removes the snapshot reference. It does not prune repository data automatically; pruning stays a separate explicit repository action.") },
            confirmButton = {
                Button(onClick = {
                    confirmForgetId = null
                    onEvent(BkxHomeEvent.ForgetSnapshot(snapshotId))
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForgetId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SnapshotList(
    snapshots: List<SnapshotDescriptor>,
    selected: SnapshotDescriptor?,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BkxSectionHeader(title = "Available snapshots")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshots.forEach { snapshot ->
            BkxOutlinedSurface(onClick = { onEvent(BkxHomeEvent.SelectSnapshot(snapshot)) }) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(snapshot.shortId.ifBlank { snapshot.id.take(8) }, style = MaterialTheme.typography.titleMedium)
                        Text(snapshot.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (snapshot.paths.isNotEmpty()) {
                            Text(snapshot.paths.joinToString(" • "), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (snapshot.id == selected?.id) BkxStatusPill("SELECTED")
                }
            }
        }
    }
}

@Composable
private fun SnapshotDetail(
    state: BkxHomeUiState,
    snapshot: SnapshotDescriptor,
    activeRestore: com.mikeyphw.bkx.android.core.operations.ActiveOperation?,
    onEvent: (BkxHomeEvent) -> Unit,
    onConfirmRestore: () -> Unit,
    onConfirmForget: () -> Unit,
) {
    val snapshotState = state.snapshots
    BkxSurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Snapshot ${snapshot.shortId.ifBlank { snapshot.id.take(8) }}", style = MaterialTheme.typography.titleLarge)
                Text(snapshot.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            snapshot.size?.let { BkxStatusPill(formatUnsignedBytes(it)) }
        }
        if (snapshot.hostname.isNotBlank()) BkxKeyValue("Host", snapshot.hostname)
        if (snapshot.username.isNotBlank()) BkxKeyValue("User", snapshot.username)
        if (snapshot.tags.isNotEmpty()) BkxKeyValue("Tags", snapshot.tags.joinToString(", "))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.CompareSnapshotWithPrevious) },
                enabled = snapshotState.actionInProgress == null,
                modifier = Modifier.weight(1f),
            ) { Text("Compare previous") }
            TextButton(onClick = onConfirmForget, enabled = snapshotState.actionInProgress == null, modifier = Modifier.weight(1f)) { Text("Forget") }
        }
    }

    snapshotState.diff?.let { diff ->
        BkxSurfaceCard {
            BkxSectionHeader(title = "Changes")
            BkxKeyValue("Changed entries", diff.changedFiles.toString())
            diff.bytesAdded?.let { BkxKeyValue("Added", formatUnsignedBytes(it)) }
            diff.bytesRemoved?.let { BkxKeyValue("Removed", formatUnsignedBytes(it)) }
            diff.entries.take(30).forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.modifier.ifBlank { "?" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(entry.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (diff.entries.size > 30) Text("${diff.entries.size - 30} more changed entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SnapshotBrowser(state, onEvent)
    SnapshotRestoreControls(state, activeRestore, onEvent, onConfirmRestore)
}

@Composable
private fun SnapshotBrowser(state: BkxHomeUiState, onEvent: (BkxHomeEvent) -> Unit) {
    val snapshotState = state.snapshots
    BkxSurfaceCard {
        BkxSectionHeader(title = "Browse snapshot")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(snapshotState.browsePath, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (snapshotState.browsePath != "/") {
                TextButton(onClick = { onEvent(BkxHomeEvent.BrowseSnapshotPath(parentSnapshotPath(snapshotState.browsePath))) }) { Text("Up") }
            }
        }
        Text(
            if (snapshotState.selectedRestorePaths.isEmpty()) "Nothing selected: restore will include the entire snapshot." else "${snapshotState.selectedRestorePaths.size} selected path(s).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshotState.selectedRestorePaths.isNotEmpty()) {
            TextButton(onClick = { onEvent(BkxHomeEvent.ClearSnapshotRestorePaths) }) { Text("Clear selection") }
        }
        when (val browse = snapshotState.browseStatus) {
            ScreenStatus.Loading -> Text("Loading folder…")
            is ScreenStatus.Empty -> Text(browse.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ScreenStatus.Error -> {
                Text(browse.title, color = MaterialTheme.colorScheme.error)
                Text(browse.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScreenStatus.Content -> snapshotState.entries.forEach { node -> SnapshotNodeRow(node, node.path in snapshotState.selectedRestorePaths, onEvent) }
        }
    }
}

@Composable
private fun SnapshotNodeRow(node: SnapshotNodeDescriptor, selected: Boolean, onEvent: (BkxHomeEvent) -> Unit) {
    BkxOutlinedSurface {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = selected, onCheckedChange = { onEvent(BkxHomeEvent.ToggleSnapshotRestorePath(node.path)) })
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(if (node.isDirectory) "Folder" else if (node.isSymlink) "Link" else "File")
                        node.size?.let { append(" • ${formatUnsignedBytes(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (node.isDirectory) {
                TextButton(onClick = { onEvent(BkxHomeEvent.BrowseSnapshotPath(node.path)) }) { Text("Open") }
            }
        }
    }
}

@Composable
private fun SnapshotRestoreControls(
    state: BkxHomeUiState,
    activeRestore: com.mikeyphw.bkx.android.core.operations.ActiveOperation?,
    onEvent: (BkxHomeEvent) -> Unit,
    onConfirmRestore: () -> Unit,
) {
    val snapshotState = state.snapshots
    BkxSurfaceCard {
        BkxSectionHeader(title = "Restore")
        OutlinedTextField(
            value = snapshotState.restoreTarget,
            onValueChange = { onEvent(BkxHomeEvent.SetSnapshotRestoreTarget(it)) },
            label = { Text("Restore to") },
            supportingText = { Text("A separate BKX restore folder is the default. Original files are not overwritten unless you choose a different policy.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.SnapshotRestoreDestination, snapshotState.restoreTarget)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Choose device folder") }

        Text("Existing files", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SnapshotOverwriteMode.entries.forEach { mode ->
                FilterChip(
                    selected = snapshotState.overwriteMode == mode,
                    onClick = { onEvent(BkxHomeEvent.SetSnapshotOverwriteMode(mode)) },
                    label = { Text(overwriteLabel(mode)) },
                )
            }
        }

        if (activeRestore != null) {
            BkxOperationProgress(
                title = "Restoring snapshot",
                phase = activeRestore.lastMessage.ifBlank { activeRestore.lastPhase },
                progress = activeRestore.progress,
                filesDone = activeRestore.filesDone,
                filesTotal = activeRestore.filesTotal,
                bytesDone = activeRestore.bytesDone,
                bytesTotal = activeRestore.bytesTotal,
            )
            if (activeRestore.cancelFilePath != null && activeRestore.status != ActiveOperationStatus.Cancelling) {
                OutlinedButton(
                    onClick = {
                        onEvent(
                            BkxHomeEvent.SubmitOperationCancellation(
                                requestId = activeRestore.requestId,
                                cancelFilePath = activeRestore.cancelFilePath,
                                cancelRequestId = "cancel-${System.currentTimeMillis()}",
                                requestedAtEpochMillis = System.currentTimeMillis(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel restore") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onEvent(BkxHomeEvent.PreviewSnapshotRestore) },
                    enabled = snapshotState.actionInProgress == null,
                    modifier = Modifier.weight(1f),
                ) { Text("Preview") }
                Button(
                    onClick = onConfirmRestore,
                    enabled = snapshotState.actionInProgress == null,
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            }
        }

        snapshotState.restorePreview?.let { preview ->
            Text("Preview", style = MaterialTheme.typography.titleMedium)
            BkxKeyValue("Target", preview.target)
            BkxKeyValue("Files", preview.totalFiles.toString())
            BkxKeyValue("Data", formatSignedBytes(preview.totalBytes))
        }
        snapshotState.restoreResult?.let { result ->
            Text("Last restore", style = MaterialTheme.typography.titleMedium)
            BkxStatusPill("COMPLETE")
            BkxKeyValue("Restored files", result.filesRestored.toString())
            BkxKeyValue("Restored data", formatSignedBytes(result.bytesRestored))
            if (result.filesSkipped > 0) BkxKeyValue("Skipped files", result.filesSkipped.toString())
        }
    }
}

private fun parentSnapshotPath(path: String): String {
    val clean = path.trimEnd('/')
    if (clean.isBlank() || clean == "/") return "/"
    val parent = clean.substringBeforeLast('/', "")
    return if (parent.isBlank()) "/" else parent
}

private fun overwriteLabel(mode: SnapshotOverwriteMode): String = when (mode) {
    SnapshotOverwriteMode.Never -> "Never overwrite"
    SnapshotOverwriteMode.IfChanged -> "If changed"
    SnapshotOverwriteMode.IfNewer -> "If newer"
    SnapshotOverwriteMode.Always -> "Always overwrite"
}

private fun formatUnsignedBytes(bytes: ULong): String {
    val maxLong = Long.MAX_VALUE.toULong()
    return if (bytes <= maxLong) formatSignedBytes(bytes.toLong()) else "$bytes B"
}

private fun formatSignedBytes(bytes: Long): String {
    if (bytes < 1024L && bytes > -1024L) return "$bytes B"
    val sign = if (bytes < 0) "-" else ""
    val value = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE.toDouble() else kotlin.math.abs(bytes.toDouble())
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    val index = (ln(value) / ln(1024.0)).toInt().coerceIn(1, units.size) - 1
    val scaled = value / 1024.0.pow(index + 1)
    return "$sign${if (scaled >= 10) "%.0f".format(scaled) else "%.1f".format(scaled)} ${units[index]}"
}
