package com.mikeyphw.bkx.android.feature.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxPathRow
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.settings.AppearancePreference
import com.mikeyphw.bkx.android.core.settings.LogPrivacyMode
import com.mikeyphw.bkx.android.core.settings.ProgressPreference
import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferResultUiState
import com.mikeyphw.bkx.android.storage.AndroidStorageAccessManager
import com.mikeyphw.bkx.android.storage.BkxStorageLocations
import com.mikeyphw.bkx.android.storage.StorageLocationKind

@Composable
fun MoreScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storageAccess = AndroidStorageAccessManager(context)
    val quickLocations = storageAccess.discoverLocations(state.rootAvailable)
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader(
            title = "More",
            subtitle = "Transfers, storage access, appearance, root policy, and advanced diagnostics.",
        )

        BkxSectionHeader("Storage access")
        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("All files access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (storageAccess.hasAllFilesAccess()) "Shared storage is directly accessible." else "Grant access for direct shared-storage backups.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BkxStatusPill(if (storageAccess.hasAllFilesAccess()) "GRANTED" else "NEEDED")
            }
            if (!storageAccess.hasAllFilesAccess()) {
                Button(onClick = storageAccess::openGrantSettings) { Text("Grant file access") }
            }
            BkxDivider()
            Text("Quick locations", style = MaterialTheme.typography.titleMedium)
            quickLocations.forEach { location ->
                BkxPathRow(
                    label = location.label,
                    path = location.path,
                    supporting = when {
                        location.requiresRoot -> "Root access"
                        location.kind == StorageLocationKind.Removable -> "SD / USB storage"
                        else -> "Shared storage"
                    },
                )
            }
        }

        TransferPanel(state, onEvent)

        BkxSectionHeader("Root")
        BkxSurfaceCard {
            Text(
                if (state.rootAvailable) "Root is currently active." else "BKX is currently running without root.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Root gives the embedded BKX engine direct access to private paths such as Termux home, Termux prefix, /data/adb, and app-private data.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RootAccessMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.settings.rootAccessMode == mode,
                        onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(rootAccessMode = mode))) },
                        label = { Text(mode.displayLabel()) },
                    )
                }
            }
            Text("Root policy is evaluated for every operation; running work keeps the route it started with.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }) { Text("Check root status") }
        }

        BkxSectionHeader("Appearance")
        BkxSurfaceCard {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearancePreference.entries.forEach { appearance ->
                    FilterChip(
                        selected = state.settings.appearance == appearance,
                        onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(appearance = appearance))) },
                        label = { Text(appearance.name) },
                    )
                }
            }
            BkxSettingSwitch(
                title = "Use Android colors",
                summary = "Use your device wallpaper colors on Android 12 and newer.",
                checked = state.settings.dynamicColor,
                onCheckedChange = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(dynamicColor = it))) },
            )
        }

        BkxSectionHeader("Privacy & progress")
        BkxSurfaceCard {
            Text("Activity details", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogPrivacyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.settings.logPrivacyMode == mode,
                        onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(logPrivacyMode = mode))) },
                        label = { Text(mode.name) },
                    )
                }
            }
            Text("Progress style", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressPreference.entries.forEach { preference ->
                    FilterChip(
                        selected = state.settings.progressPreference == preference,
                        onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(progressPreference = preference))) },
                        label = { Text(preference.displayLabel()) },
                    )
                }
            }
        }

        BkxSectionHeader("Advanced")
        OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics")
        }
        if (showDiagnostics) {
            BkxSurfaceCard {
                Text("Engine diagnostics", style = MaterialTheme.typography.titleLarge)
                BkxKeyValue("Runtime", state.backendToolState.runtimeLabel)
                BkxKeyValue("BKX version", state.backendToolState.bkxVersion ?: "Not verified")
                BkxKeyValue("Protocol", state.backendToolState.protocolVersion.toString())
                BkxKeyValue("Root", if (state.rootAvailable) "Active" else "Inactive")
                BkxKeyValue("Capabilities", state.backendToolState.capabilities.size.toString())
                state.backendToolState.remediationActions.forEach { action ->
                    BkxDivider()
                    Text(action.title, style = MaterialTheme.typography.titleMedium)
                    action.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun TransferPanel(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    val transfer = state.transfer
    val draft = transfer.draft
    val sourcePath = draft.sources.firstOrNull()?.location.orEmpty()
    val destinationPath = draft.destination?.location.orEmpty()
    val providerTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let { onEvent(BkxHomeEvent.ImportProviderTransferTree(it)) }
    }

    BkxSectionHeader("Local transfer")
    BkxSurfaceCard {
        Text("Copy or synchronize filesystem trees using embedded BKX.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransferMode.entries.forEach { mode ->
                FilterChip(
                    selected = draft.mode == mode,
                    onClick = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(mode = mode))) },
                    label = { Text(mode.name) },
                )
            }
        }
        OutlinedTextField(
            value = sourcePath,
            onValueChange = { path ->
                val endpoint = path.trim().takeIf { it.startsWith("/") }?.let {
                    TransferEndpoint("source-${it.hashCode()}", it.substringAfterLast('/').ifBlank { "Source" }, it)
                }
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = endpoint?.let(::listOf).orEmpty())))
            },
            label = { Text("Source path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.TransferSource, sourcePath.takeIf { it.startsWith("/") } ?: BkxStorageLocations.SHARED_STORAGE)) },
                modifier = Modifier.weight(1f),
            ) { Text("Browse source") }
            OutlinedButton(
                onClick = { providerTreeLauncher.launch(null) },
                enabled = !state.providerImportInProgress,
                modifier = Modifier.weight(1f),
            ) { Text("Document provider") }
        }
        OutlinedTextField(
            value = destinationPath,
            onValueChange = { path ->
                val endpoint = path.trim().takeIf { it.startsWith("/") }?.let {
                    TransferEndpoint("destination-${it.hashCode()}", it.substringAfterLast('/').ifBlank { "Destination" }, it)
                }
                onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(destination = endpoint)))
            },
            label = { Text("Destination path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(
            onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.TransferDestination, destinationPath.takeIf { it.startsWith("/") } ?: BkxStorageLocations.SHARED_STORAGE)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Browse destination") }
        state.providerImportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {
                    val endpoint = TransferEndpoint("termux-home", "Termux home", BkxStorageLocations.TERMUX_HOME)
                    onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(sources = listOf(endpoint))))
                },
                label = { Text("Termux home as source") },
                enabled = state.rootAvailable,
            )
            AssistChip(
                onClick = {
                    val endpoint = TransferEndpoint("shared", "Internal storage", BkxStorageLocations.SHARED_STORAGE)
                    onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(destination = endpoint)))
                },
                label = { Text("Internal storage destination") },
            )
        }
        BkxSettingSwitch(
            title = "Verify after transfer",
            summary = "Check copied data before BKX reports success.",
            checked = draft.verifyAfterTransfer,
            onCheckedChange = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(verifyAfterTransfer = it))) },
        )
        BkxSettingSwitch(
            title = "Replace existing files",
            summary = "Allow BKX to overwrite destination files when the transfer plan permits it.",
            checked = draft.overwriteExisting,
            onCheckedChange = { onEvent(BkxHomeEvent.UpdateTransferDraft(draft.copy(overwriteExisting = it))) },
        )
        if (draft.mode == TransferMode.Sync) {
            BkxSettingSwitch(
                title = "Delete extra destination files",
                summary = "Make the destination mirror the source. Review the plan before running.",
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
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.RequestTransferPreview) },
                enabled = transfer.canPreview,
                modifier = Modifier.weight(1f),
            ) { Text("Review") }
            Button(
                onClick = { onEvent(BkxHomeEvent.RunTransfer) },
                enabled = transfer.canRun,
                modifier = Modifier.weight(1f),
            ) { Text(if (draft.mode == TransferMode.Sync) "Sync" else "Transfer") }
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

private fun RootAccessMode.displayLabel(): String = when (this) {
    RootAccessMode.Preferred -> "Prefer root"
    RootAccessMode.Required -> "Require root"
    RootAccessMode.Disabled -> "No root"
}

private fun ProgressPreference.displayLabel(): String = when (this) {
    ProgressPreference.EventDriven -> "Detailed"
    ProgressPreference.Polling -> "Compatible"
    ProgressPreference.Compact -> "Compact"
}
