package com.mikeyphw.bkx.android.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.backup.BackupCompression
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.backup.BackupSplitPolicy
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.storage.BkxStorageLocations

@Composable
fun BackupScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val planning = state.backupPlanning
    val draft = planning.draft
    val capabilities = state.backendToolState.capabilities.toSet()
    val splitSupported = "archive.split" in capabilities
    val compressionSupported = draft.compression.requiredCapability in capabilities
    val selectedCapabilitiesSupported = compressionSupported && (!draft.split.enabled || splitSupported)
    val providerTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let { onEvent(BkxHomeEvent.ImportProviderBackupTree(it)) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader(
            title = "Create a backup",
            subtitle = "Choose real filesystem paths. Root locations work directly when the root engine is active.",
        )

        BkxSectionHeader("1 · What to protect")
        BkxSurfaceCard {
            OutlinedTextField(
                value = draft.source?.stagedPath.orEmpty(),
                onValueChange = { path ->
                    onEvent(
                        BkxHomeEvent.UpdateBackupDraft(
                            draft.copy(
                                source = path.trim().takeIf { it.startsWith("/") }?.let {
                                    BackupSourceSelection(
                                        sourceId = "path-${it.hashCode()}",
                                        label = it.substringAfterLast('/').ifBlank { "Source" },
                                        stagedPath = it,
                                    )
                                },
                            ),
                        ),
                    )
                },
                label = { Text("Source path") },
                supportingText = { Text("Folder or file to back up") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.BackupSource, draft.source?.stagedPath ?: BkxStorageLocations.SHARED_STORAGE)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Browse files") }
                OutlinedButton(
                    onClick = { providerTreeLauncher.launch(null) },
                    enabled = !state.providerImportInProgress,
                    modifier = Modifier.weight(1f),
                ) { Text("Document provider") }
            }
            state.providerImportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = source(BkxStorageLocations.SHARED_STORAGE, "Internal storage")))) },
                    label = { Text("Internal storage") },
                )
                if (state.rootAvailable) {
                    AssistChip(
                        onClick = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = source(BkxStorageLocations.TERMUX_HOME, "Termux home")))) },
                        label = { Text("Termux home") },
                    )
                    AssistChip(
                        onClick = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = source(BkxStorageLocations.TERMUX_PREFIX, "Termux prefix")))) },
                        label = { Text("Termux prefix") },
                    )
                }
            }
        }

        BkxSectionHeader("2 · Where to save it")
        BkxSurfaceCard {
            OutlinedTextField(
                value = draft.destination?.stagedDirectoryPath.orEmpty(),
                onValueChange = { path ->
                    onEvent(
                        BkxHomeEvent.UpdateBackupDraft(
                            draft.copy(
                                destination = path.trim().takeIf { it.startsWith("/") }?.let {
                                    BackupDestinationSelection(
                                        destinationId = "path-${it.hashCode()}",
                                        label = it.substringAfterLast('/').ifBlank { "Backup destination" },
                                        stagedDirectoryPath = it,
                                    )
                                },
                            ),
                        ),
                    )
                },
                label = { Text("Destination folder") },
                supportingText = { Text("For example /storage/emulated/0/BKX") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.BackupDestination, draft.destination?.stagedDirectoryPath ?: BkxStorageLocations.SHARED_STORAGE)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Browse destination") }
            AssistChip(
                onClick = {
                    val path = "${BkxStorageLocations.SHARED_STORAGE}/BKX"
                    onEvent(
                        BkxHomeEvent.UpdateBackupDraft(
                            draft.copy(destination = BackupDestinationSelection("bkx-default", "BKX", path)),
                        ),
                    )
                },
                label = { Text("Use Internal storage/BKX") },
            )
        }

        BkxSectionHeader("3 · Options")
        BkxSurfaceCard {
            OutlinedTextField(
                value = draft.archiveName,
                onValueChange = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(archiveName = it))) },
                label = { Text("Backup name (optional)") },
                supportingText = { Text("A .bakery extension is added automatically. Leave blank for an automatic name.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            BkxDivider()
            Text("Compression", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupCompression.entries.forEach { compression ->
                    val supported = compression.requiredCapability in capabilities
                    FilterChip(
                        selected = draft.compression == compression,
                        onClick = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(compression = compression))) },
                        enabled = supported,
                        label = { Text(compression.displayLabel()) },
                    )
                }
            }
            BkxDivider()
            BkxSettingSwitch(
                title = "Verify after backup",
                summary = "BKX checks the completed archive before reporting success.",
                checked = draft.verification.verifyAfterCreate,
                onCheckedChange = { enabled ->
                    onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(verification = draft.verification.copy(verifyAfterCreate = enabled))))
                },
            )
            BkxSettingSwitch(
                title = "Include hidden files",
                summary = "Include dotfiles and hidden configuration data.",
                checked = draft.filters.includeHidden,
                onCheckedChange = { enabled ->
                    onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(filters = draft.filters.copy(includeHidden = enabled))))
                },
            )
            Text(
                "Symbolic links are skipped safely rather than followed. This keeps root backups such as Termux home from escaping the selected source tree.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BkxSettingSwitch(
                title = "Split large backups",
                summary = if (splitSupported) "Create 1 GB parts for easier copying to constrained filesystems." else "This BKX engine does not support split archives.",
                checked = draft.split.enabled,
                enabled = splitSupported,
                onCheckedChange = { enabled ->
                    onEvent(
                        BkxHomeEvent.UpdateBackupDraft(
                            draft.copy(
                                split = if (enabled) BackupSplitPolicy(true, 1024L * 1024L * 1024L) else BackupSplitPolicy(),
                            ),
                        ),
                    )
                },
            )
        }

        BkxSectionHeader("4 · Review")
        BkxSurfaceCard {
            BkxKeyValue("Source", draft.source?.label ?: "Not selected")
            BkxKeyValue("Destination", draft.destination?.label ?: "Not selected")
            BkxKeyValue("Name", draft.archiveName.trim().ifBlank { "Automatic" })
            BkxKeyValue("Compression", draft.compression.displayLabel())
            BkxKeyValue("Verification", if (draft.verification.verifyAfterCreate) "On" else "Off")
            if (!selectedCapabilitiesSupported) {
                BkxDivider()
                Text(
                    when {
                        !compressionSupported -> "The connected BKX engine does not support ${draft.compression.displayLabel()} compression."
                        draft.split.enabled && !splitSupported -> "The connected BKX engine does not support split archives."
                        else -> "The selected backup options are not supported by this BKX engine."
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (planning.validationIssues.isNotEmpty()) {
                BkxDivider()
                planning.validationIssues.forEach { issue ->
                    Text(issue.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            planning.preview?.let { preview ->
                BkxDivider()
                BkxStatusPill(if (preview.isExecutable) "READY" else "NEEDS ATTENTION")
                Text("BKX reviewed this backup plan.", style = MaterialTheme.typography.titleMedium)
                preview.warnings.forEach { warning ->
                    Text(warning.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.verificationExpectations.isNotEmpty()) {
                    Text("The completed archive will be verified before success is shown.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onEvent(BkxHomeEvent.RequestBackupPlan) },
                    enabled = planning.canRequestPlan && selectedCapabilitiesSupported,
                    modifier = Modifier.weight(1f),
                ) { Text(if (planning.preview == null) "Review backup" else "Review again") }
                Button(
                    onClick = { onEvent(BkxHomeEvent.CreateBackup) },
                    enabled = planning.canCreateBackup && selectedCapabilitiesSupported,
                    modifier = Modifier.weight(1f),
                ) { Text("Create backup") }
            }
            if (planning.canCreateBackup && selectedCapabilitiesSupported) {
                Text(
                    "The backup runs through the embedded BKX engine and will appear under Activity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(
            onClick = { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Activity)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("View activity") }
    }
}

private fun source(path: String, label: String): BackupSourceSelection = BackupSourceSelection(
    sourceId = "preset-${path.hashCode()}",
    label = label,
    stagedPath = path,
)

private fun BackupCompression.displayLabel(): String = when (this) {
    BackupCompression.None -> "None"
    BackupCompression.Gzip -> "Gzip"
    BackupCompression.Zstd -> "Zstd"
}
