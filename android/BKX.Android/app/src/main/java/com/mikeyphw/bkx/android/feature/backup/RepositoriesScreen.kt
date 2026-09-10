package com.mikeyphw.bkx.android.feature.backup

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.MoreRoute
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.state.BkxSharedStateContract
import com.mikeyphw.bkx.android.core.repositories.RepositoryBackendKind
import com.mikeyphw.bkx.android.core.repositories.RepositoryCreateSpec
import com.mikeyphw.bkx.android.core.repositories.RepositoryDefinition
import com.mikeyphw.bkx.android.core.repositories.RepositorySpecs
import com.mikeyphw.bkx.android.core.repositories.RetentionPolicy

@Composable
fun RepositoriesScreen(state: BkxHomeUiState, onEvent: (BkxHomeEvent) -> Unit, modifier: Modifier = Modifier) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<RepositoryDefinition?>(null) }
    var pruneTarget by remember { mutableStateOf<RepositoryDefinition?>(null) }
    var retentionTarget by remember { mutableStateOf<RepositoryDefinition?>(null) }
    var retentionApplyTarget by remember { mutableStateOf<RepositoryDefinition?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader("Repositories", "Manage Restic repositories once, then reference them from saved jobs on Android, CLI, and Tasker.")
        BkxSurfaceCard {
            Text("Shared with on-device CLI", style = MaterialTheme.typography.titleMedium)
            BkxKeyValue("State", BkxSharedStateContract.ROOT)
            Text("Only job and repository definitions are shared here. Restic passwords, OAuth tokens, and rclone credentials remain outside the shared control-plane store.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }, enabled = state.repositories.actionInProgress == null, modifier = Modifier.weight(1f)) { Text("Add repository") }
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshRepositories) }, enabled = state.repositories.actionInProgress == null, modifier = Modifier.weight(1f)) { Text("Refresh") }
        }

        if (!state.googleDrive.connected) {
            BkxSurfaceCard {
                Text("Google Drive is not connected", style = MaterialTheme.typography.titleMedium)
                Text("Local and existing rclone repositories still work. Connect Drive before creating a BKX-managed Drive repository.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    onEvent(BkxHomeEvent.SelectDestination(BkxDestination.More))
                    onEvent(BkxHomeEvent.OpenMoreRoute(MoreRoute.GoogleDrive))
                }) { Text("Connect Google Drive") }
            }
        }

        when (val status = state.repositories.status) {
            ScreenStatus.Loading -> BkxSurfaceCard { Text("Loading repositories…") }
            is ScreenStatus.Empty -> BkxSurfaceCard { Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            is ScreenStatus.Error -> BkxSurfaceCard {
                Text(status.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshRepositories) }) { Text("Try again") }
            }
            ScreenStatus.Content -> state.repositories.items.forEach { repository ->
                RepositoryCard(
                    repository = repository,
                    message = state.repositories.messages[repository.repositoryId],
                    busy = state.repositories.actionInProgress != null,
                    onInitialize = { onEvent(BkxHomeEvent.InitializeRepository(repository.repositoryId)) },
                    onCheck = { onEvent(BkxHomeEvent.CheckRepository(repository.repositoryId)) },
                    onRetention = { retentionTarget = repository },
                    onRetentionPreview = { onEvent(BkxHomeEvent.PreviewRetentionApply(repository.repositoryId)) },
                    onRetentionApply = { retentionApplyTarget = repository },
                    onPrunePreview = { onEvent(BkxHomeEvent.PreviewRepositoryPrune(repository.repositoryId)) },
                    onPrune = { pruneTarget = repository },
                    onRemove = { removeTarget = repository },
                )
            }
        }
    }

    if (adding) {
        RepositoryEditorDialog(
            driveConnected = state.googleDrive.connected,
            onDismiss = { adding = false },
            onSave = { spec -> onEvent(BkxHomeEvent.AddRepository(spec)); adding = false },
        )
    }

    removeTarget?.let { repository ->
        ConfirmDialog(
            title = "Remove repository definition?",
            text = "This removes ${repository.name} from BKX. It does not delete the Restic repository or its backup data.",
            confirmLabel = "Remove",
            onDismiss = { removeTarget = null },
            onConfirm = { onEvent(BkxHomeEvent.RemoveRepository(repository.repositoryId)); removeTarget = null },
        )
    }
    pruneTarget?.let { repository ->
        ConfirmDialog(
            title = "Prune unused repository data?",
            text = "Pruning can permanently remove unreferenced Restic data from ${repository.name}. BKX keeps this separate from snapshot deletion and backup creation.",
            confirmLabel = "Prune now",
            onDismiss = { pruneTarget = null },
            onConfirm = { onEvent(BkxHomeEvent.PruneRepository(repository.repositoryId)); pruneTarget = null },
        )
    }
    retentionApplyTarget?.let { repository ->
        ConfirmDialog(
            title = "Apply retention policy?",
            text = "This will forget snapshots that are outside ${repository.name}'s keep policy. It will not prune repository data automatically.",
            confirmLabel = "Apply retention",
            onDismiss = { retentionApplyTarget = null },
            onConfirm = { onEvent(BkxHomeEvent.ApplyRetention(repository.repositoryId)); retentionApplyTarget = null },
        )
    }
    retentionTarget?.let { repository ->
        RetentionDialog(
            repository = repository,
            onDismiss = { retentionTarget = null },
            onSave = { policy -> onEvent(BkxHomeEvent.SetRepositoryRetention(repository.repositoryId, policy)); retentionTarget = null },
        )
    }
}

@Composable
private fun RepositoryCard(
    repository: RepositoryDefinition,
    message: String?,
    busy: Boolean,
    onInitialize: () -> Unit,
    onCheck: () -> Unit,
    onRetention: () -> Unit,
    onRetentionPreview: () -> Unit,
    onRetentionApply: () -> Unit,
    onPrunePreview: () -> Unit,
    onPrune: () -> Unit,
    onRemove: () -> Unit,
) {
    BkxSurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(repository.name, style = MaterialTheme.typography.titleLarge)
                Text(repository.repositoryId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BkxStatusPill(repositoryBackendLabel(repository))
        }
        BkxKeyValue("Location", repository.location)
        BkxKeyValue("Engine", repository.engine)
        repository.credentialRef?.let { BkxKeyValue("Credential", it) }
        repository.retention?.let { BkxKeyValue("Retention", retentionLabel(it)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheck, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Check") }
            OutlinedButton(onClick = onInitialize, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Initialize") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetention, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Retention") }
            TextButton(onClick = onRetentionPreview, enabled = !busy && repository.retention != null, modifier = Modifier.weight(1f)) { Text("Preview keep") }
            TextButton(onClick = onRetentionApply, enabled = !busy && repository.retention != null, modifier = Modifier.weight(1f)) { Text("Apply keep") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPrunePreview, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Preview prune") }
            TextButton(onClick = onPrune, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Prune") }
            TextButton(onClick = onRemove, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Remove") }
        }
    }
}

@Composable
private fun RepositoryEditorDialog(driveConnected: Boolean, onDismiss: () -> Unit, onSave: (RepositoryCreateSpec) -> Unit) {
    var backend by remember { mutableStateOf(RepositoryBackendKind.Local) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("/storage/emulated/0/BKX/restic") }
    var credentialRef by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add repository") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Storage", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = backend == RepositoryBackendKind.Local, onClick = { backend = RepositoryBackendKind.Local; location = "/storage/emulated/0/BKX/restic" }, label = { Text("Local") })
                    FilterChip(selected = backend == RepositoryBackendKind.Rclone, onClick = { backend = RepositoryBackendKind.Rclone; location = "rclone://remote/BKX/restic" }, label = { Text("rclone") })
                    FilterChip(selected = backend == RepositoryBackendKind.ManagedGoogleDrive, onClick = { if (driveConnected) { backend = RepositoryBackendKind.ManagedGoogleDrive; location = "restic/${id.ifBlank { "phone" }}" } }, enabled = driveConnected, label = { Text("Google Drive") })
                }
                OutlinedTextField(value = id, onValueChange = { id = normalizeRepositoryId(it) }, label = { Text("Repository ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(if (backend == RepositoryBackendKind.ManagedGoogleDrive) "Drive child path" else "Location") },
                    supportingText = { Text(if (backend == RepositoryBackendKind.Rclone) "Use rclone://remote/path" else if (backend == RepositoryBackendKind.ManagedGoogleDrive) "Stored under BKX on the managed Drive connection" else "Absolute local filesystem path") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = credentialRef, onValueChange = { credentialRef = it.trim() }, label = { Text("Restic password reference") }, supportingText = { Text("Reference ID only; the secret stays in the secure credential environment.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val ref = credentialRef.takeIf { it.isNotBlank() }
                    when (backend) {
                        RepositoryBackendKind.Local -> RepositorySpecs.local(id, name.trim().ifBlank { id }, location.trim(), ref)
                        RepositoryBackendKind.Rclone -> RepositorySpecs.rclone(id, name.trim().ifBlank { id }, location.trim(), ref)
                        RepositoryBackendKind.ManagedGoogleDrive -> RepositorySpecs.managedGoogleDrive(id, name.trim().ifBlank { id }, location.trim(), ref)
                    }
                }.onSuccess(onSave).onFailure { error = it.message ?: "Invalid repository" }
            }, enabled = id.isNotBlank() && location.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RetentionDialog(repository: RepositoryDefinition, onDismiss: () -> Unit, onSave: (RetentionPolicy) -> Unit) {
    var keepLast by remember { mutableStateOf(repository.retention?.keepLast?.toString() ?: "10") }
    var keepDaily by remember { mutableStateOf(repository.retention?.keepDaily?.toString() ?: "7") }
    var keepWeekly by remember { mutableStateOf(repository.retention?.keepWeekly?.toString() ?: "4") }
    var keepMonthly by remember { mutableStateOf(repository.retention?.keepMonthly?.toString() ?: "12") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retention · ${repository.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BKX applies this with Restic forget. Pruning remains a separate explicit operation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = keepLast, onValueChange = { keepLast = it.filter { ch -> ch.isDigit() } }, label = { Text("Keep last") }, singleLine = true)
                OutlinedTextField(value = keepDaily, onValueChange = { keepDaily = it.filter { ch -> ch.isDigit() } }, label = { Text("Keep daily") }, singleLine = true)
                OutlinedTextField(value = keepWeekly, onValueChange = { keepWeekly = it.filter { ch -> ch.isDigit() } }, label = { Text("Keep weekly") }, singleLine = true)
                OutlinedTextField(value = keepMonthly, onValueChange = { keepMonthly = it.filter { ch -> ch.isDigit() } }, label = { Text("Keep monthly") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    RetentionPolicy(
                        keepLast = keepLast.toPositiveOrNull(),
                        keepDaily = keepDaily.toPositiveOrNull(),
                        keepWeekly = keepWeekly.toPositiveOrNull(),
                        keepMonthly = keepMonthly.toPositiveOrNull(),
                    )
                }.onSuccess(onSave).onFailure { error = it.message ?: "Invalid retention policy" }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun repositoryBackendLabel(repository: RepositoryDefinition): String = when (repository.backend) {
    RepositoryBackendKind.Local.wireValue -> "LOCAL"
    RepositoryBackendKind.Rclone.wireValue -> "RCLONE"
    RepositoryBackendKind.ManagedGoogleDrive.wireValue -> "GOOGLE DRIVE"
    else -> repository.backend.uppercase()
}

private fun retentionLabel(policy: RetentionPolicy): String = buildList {
    policy.keepLast?.let { add("last $it") }
    policy.keepDaily?.let { add("daily $it") }
    policy.keepWeekly?.let { add("weekly $it") }
    policy.keepMonthly?.let { add("monthly $it") }
    policy.keepYearly?.let { add("yearly $it") }
}.joinToString(" · ").ifBlank { "Custom" }

private fun String.toPositiveOrNull(): Int? = trim().takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it > 0 }
private fun normalizeRepositoryId(value: String): String = value.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')
