package com.mikeyphw.bkx.android.feature.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxOutlinedSurface
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferResultUiState
import com.mikeyphw.bkx.android.storage.BkxStorageLocations

@Composable
fun TransferScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.transfer.draft
    val sourcePath = draft.sources.firstOrNull()?.location.orEmpty()
    val destinationPath = draft.destination?.location.orEmpty()
    var chooseSource by rememberSaveable { mutableStateOf(sourcePath.isBlank()) }
    var chooseDestination by rememberSaveable { mutableStateOf(destinationPath.isBlank()) }
    var advancedSource by rememberSaveable { mutableStateOf(false) }
    var advancedDestination by rememberSaveable { mutableStateOf(false) }
    val providerTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let { onEvent(BkxHomeEvent.ImportProviderTransferTree(it)) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
        ) {
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseMoreRoute) }) { Text("Back to More") }
            BkxPageHeader(
                title = "Transfer",
                subtitle = "Copy or synchronize local filesystem trees. Cloud remotes will join this workflow when remote storage is added.",
            )
            if (wide) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                        TransferEndpoints(
                            state = state,
                            chooseSource = chooseSource,
                            chooseDestination = chooseDestination,
                            advancedSource = advancedSource,
                            advancedDestination = advancedDestination,
                            onChooseSource = { chooseSource = it },
                            onChooseDestination = { chooseDestination = it },
                            onAdvancedSource = { advancedSource = it },
                            onAdvancedDestination = { advancedDestination = it },
                            openProvider = { providerTreeLauncher.launch(null) },
                            onEvent = onEvent,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                        TransferOptionsAndReview(state, onEvent)
                    }
                }
            } else {
                TransferEndpoints(
                    state = state,
                    chooseSource = chooseSource,
                    chooseDestination = chooseDestination,
                    advancedSource = advancedSource,
                    advancedDestination = advancedDestination,
                    onChooseSource = { chooseSource = it },
                    onChooseDestination = { chooseDestination = it },
                    onAdvancedSource = { advancedSource = it },
                    onAdvancedDestination = { advancedDestination = it },
                    openProvider = { providerTreeLauncher.launch(null) },
                    onEvent = onEvent,
                )
                TransferOptionsAndReview(state, onEvent)
            }
        }
    }
}

@Composable
private fun TransferEndpoints(
    state: BkxHomeUiState,
    chooseSource: Boolean,
    chooseDestination: Boolean,
    advancedSource: Boolean,
    advancedDestination: Boolean,
    onChooseSource: (Boolean) -> Unit,
    onChooseDestination: (Boolean) -> Unit,
    onAdvancedSource: (Boolean) -> Unit,
    onAdvancedDestination: (Boolean) -> Unit,
    openProvider: () -> Unit,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    val draft = state.transfer.draft
    val source = draft.sources.firstOrNull()
    val destination = draft.destination

    BkxSurfaceCard {
        Text("From", style = MaterialTheme.typography.titleLarge)
        if (source == null) {
            Text("Choose what to transfer", style = MaterialTheme.typography.titleMedium)
            Text("Pick a common location, browse the filesystem, or choose a folder from Files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onChooseSource(true) }) { Text("Choose source") }
        } else {
            TransferEndpointSummary(source)
            TextButton(onClick = { onChooseSource(!chooseSource) }) { Text(if (chooseSource) "Done" else "Change source") }
        }
        if (chooseSource) {
            BkxDivider()
            TransferLocationChoice("Internal storage", BkxStorageLocations.SHARED_STORAGE, "Shared storage") {
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = listOf(localEndpoint("source", "Internal storage", BkxStorageLocations.SHARED_STORAGE)))))
                onChooseSource(false)
            }
            if (state.rootAvailable) {
                TransferLocationChoice("Termux home", BkxStorageLocations.TERMUX_HOME, "Root") {
                    onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = listOf(localEndpoint("termux-home", "Termux home", BkxStorageLocations.TERMUX_HOME)))))
                    onChooseSource(false)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.TransferSource, source?.location ?: BkxStorageLocations.SHARED_STORAGE)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Browse device") }
                OutlinedButton(onClick = openProvider, enabled = !state.providerImportInProgress, modifier = Modifier.weight(1f)) {
                    Text("Files & other apps")
                }
            }
            TextButton(onClick = { onAdvancedSource(!advancedSource) }) {
                Text(if (advancedSource) "Hide manual path" else "Manual path · Advanced")
            }
            if (advancedSource) {
                OutlinedTextField(
                    value = source?.location.orEmpty(),
                    onValueChange = { path ->
                        val endpoint = path.trim().takeIf { it.startsWith("/") }?.let { localEndpoint("source-${it.hashCode()}", it.substringAfterLast('/').ifBlank { "Source" }, it) }
                        onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = endpoint?.let { listOf(it) }.orEmpty())))
                    },
                    label = { Text("Source path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            state.providerImportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    BkxSurfaceCard {
        Text("To", style = MaterialTheme.typography.titleLarge)
        if (destination == null) {
            Text("Choose where to transfer the files", style = MaterialTheme.typography.titleMedium)
            Text("Remote destinations will appear here when cloud and rclone support is connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onChooseDestination(true) }) { Text("Choose destination") }
        } else {
            TransferEndpointSummary(destination)
            TextButton(onClick = { onChooseDestination(!chooseDestination) }) { Text(if (chooseDestination) "Done" else "Change destination") }
        }
        if (chooseDestination) {
            BkxDivider()
            TransferLocationChoice("Internal storage", BkxStorageLocations.SHARED_STORAGE, "Shared storage") {
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(destination = localEndpoint("destination-shared", "Internal storage", BkxStorageLocations.SHARED_STORAGE))))
                onChooseDestination(false)
            }
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.TransferDestination, destination?.location ?: BkxStorageLocations.SHARED_STORAGE)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Browse device") }
            TextButton(onClick = { onAdvancedDestination(!advancedDestination) }) {
                Text(if (advancedDestination) "Hide manual path" else "Manual path · Advanced")
            }
            if (advancedDestination) {
                OutlinedTextField(
                    value = destination?.location.orEmpty(),
                    onValueChange = { path ->
                        val endpoint = path.trim().takeIf { it.startsWith("/") }?.let { localEndpoint("destination-${it.hashCode()}", it.substringAfterLast('/').ifBlank { "Destination" }, it) }
                        onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(destination = endpoint)))
                    },
                    label = { Text("Destination path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun TransferEndpointSummary(endpoint: TransferEndpoint) {
    Text(endpoint.label, style = MaterialTheme.typography.titleMedium)
    Text(if (endpoint.isRemote) "Remote" else "Filesystem", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(endpoint.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TransferLocationChoice(label: String, path: String, supporting: String, onClick: () -> Unit) {
    BkxOutlinedSurface(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun localEndpoint(id: String, label: String, path: String): TransferEndpoint = TransferEndpoint(id, label, path)

@Composable
private fun TransferOptionsAndReview(state: BkxHomeUiState, onEvent: (BkxHomeEvent) -> Unit) {
    val transfer = state.transfer
    val draft = transfer.draft
    BkxSurfaceCard {
        Text("Transfer mode", style = MaterialTheme.typography.titleLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransferMode.entries.forEach { mode ->
                FilterChip(
                    selected = draft.mode == mode,
                    onClick = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(mode = mode))) },
                    label = { Text(mode.name) },
                )
            }
        }
        BkxSettingSwitch(
            title = "Verify after transfer",
            summary = "Check copied data before BKX reports success.",
            checked = draft.verifyAfterTransfer,
            onCheckedChange = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(verifyAfterTransfer = it))) },
        )
        BkxSettingSwitch(
            title = "Replace existing files",
            summary = "Allow BKX to overwrite destination files when the reviewed plan permits it.",
            checked = draft.overwriteExisting,
            onCheckedChange = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(overwriteExisting = it))) },
        )
        if (draft.mode == TransferMode.Sync) {
            BkxSettingSwitch(
                title = "Delete extra destination files",
                summary = "Mirror the source exactly. Review the plan before running.",
                checked = draft.deleteExtraneousOnSync,
                onCheckedChange = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(deleteExtraneousOnSync = it))) },
            )
        }
        transfer.validationIssues.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
        transfer.preview?.let { preview ->
            BkxDivider()
            BkxStatusPill(if (preview.isExecutable) "READY" else "BLOCKED")
            Text(preview.topologyLabel, style = MaterialTheme.typography.titleMedium)
            preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            preview.collisionSummary.collisions.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.collisionSummary.overlaps.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RequestTransferPreview) }, enabled = transfer.canPreview, modifier = Modifier.weight(1f)) {
                Text("Review")
            }
            Button(onClick = { onEvent(BkxHomeEvent.RunTransfer) }, enabled = transfer.canRun, modifier = Modifier.weight(1f)) {
                Text(if (draft.mode == TransferMode.Sync) "Sync" else "Transfer")
            }
        }
        when (val result = transfer.result) {
            TransferResultUiState.NotStarted -> Unit
            is TransferResultUiState.Succeeded -> Text(result.summary, color = MaterialTheme.colorScheme.primary)
            is TransferResultUiState.PartialFailure -> Text(result.summary, color = MaterialTheme.colorScheme.error)
            is TransferResultUiState.VerificationFailed -> Text(result.summary, color = MaterialTheme.colorScheme.error)
            is TransferResultUiState.Failed -> Text(result.summary, color = MaterialTheme.colorScheme.error)
            is TransferResultUiState.Cancelled -> Text(result.summary, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
