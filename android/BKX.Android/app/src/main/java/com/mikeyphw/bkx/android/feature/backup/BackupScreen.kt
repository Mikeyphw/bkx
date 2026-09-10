package com.mikeyphw.bkx.android.feature.backup

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.StatFs
import java.io.File
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BackupRoute
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.MoreRoute
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxOperationProgress
import com.mikeyphw.bkx.android.app.ui.components.BkxOutlinedSurface
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.backup.BackupCompression
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.backup.BackupSplitPolicy
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.feature.home.formatBytes
import com.mikeyphw.bkx.android.storage.BkxStorageLocations

@Composable
fun BackupScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = BkxTokens.ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = state.backupRoute == BackupRoute.Overview, onClick = { onEvent(BkxHomeEvent.OpenBackupRoute(BackupRoute.Overview)) }, label = { Text("Backup now") })
            FilterChip(selected = state.backupRoute == BackupRoute.Jobs, onClick = { onEvent(BkxHomeEvent.OpenBackupRoute(BackupRoute.Jobs)) }, label = { Text("Jobs") })
            FilterChip(selected = state.backupRoute == BackupRoute.Repositories, onClick = { onEvent(BkxHomeEvent.OpenBackupRoute(BackupRoute.Repositories)) }, label = { Text("Repositories") })
        }
        when (state.backupRoute) {
            BackupRoute.Overview -> BackupCreateScreen(state, onEvent, Modifier.weight(1f))
            BackupRoute.Jobs -> SavedJobsScreen(state, onEvent, Modifier.weight(1f))
            BackupRoute.Repositories -> RepositoriesScreen(state, onEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BackupCreateScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val planning = state.backupPlanning
    val draft = planning.draft
    val capabilities = state.backendToolState.capabilities.toSet()
    val splitSupported = "archive.split" in capabilities
    val compressionSupported = draft.compression.requiredCapability in capabilities
    val remoteDestinationSelected = draft.destination?.stagedDirectoryPath?.startsWith("rclone://", ignoreCase = true) == true
    val remoteBackupSupported = "backup.remote" in capabilities
    val selectedCapabilitiesSupported = compressionSupported &&
        (!draft.split.enabled || splitSupported) &&
        (!remoteDestinationSelected || remoteBackupSupported)
    val providerTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let { onEvent(BkxHomeEvent.ImportProviderBackupTree(it)) }
    }
    val activeBackup = state.operationRuntime.activeOperations.values
        .filter { it.operation == "backup.create" && it.status in setOf(ActiveOperationStatus.Queued, ActiveOperationStatus.Running, ActiveOperationStatus.Cancelling) }
        .maxByOrNull { it.lastSeenEpochMillis }
    val backupFeedback = state.operationFeedback[BkxDestination.Backup]
    var chooseSource by rememberSaveable { mutableStateOf(draft.source == null) }
    var chooseDestination by rememberSaveable { mutableStateOf(draft.destination == null) }
    var showAdvancedSource by rememberSaveable { mutableStateOf(false) }
    var showAdvancedDestination by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val destinationFreeBytes = remember(draft.destination?.stagedDirectoryPath) {
        usableSpaceFor(context, draft.destination?.stagedDirectoryPath)
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
            BkxPageHeader(
                title = "Create backup",
                subtitle = "Choose what to protect, where to save it, then review the real file and space estimate before starting.",
            )

            BackupOperationFeedback(activeBackup, backupFeedback?.result, onEvent)

            if (wide) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                    Column(modifier = Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                        BackupSourceSection(
                            state = state,
                            draft = draft,
                            expanded = chooseSource,
                            advanced = showAdvancedSource,
                            onExpandedChange = { chooseSource = it },
                            onAdvancedChange = { showAdvancedSource = it },
                            onProvider = { providerTreeLauncher.launch(null) },
                            onEvent = onEvent,
                        )
                        BackupDestinationSection(
                            state = state,
                            draft = draft,
                            expanded = chooseDestination,
                            advanced = showAdvancedDestination,
                            onExpandedChange = { chooseDestination = it },
                            onAdvancedChange = { showAdvancedDestination = it },
                            onEvent = onEvent,
                        )
                        BackupOptionsSection(
                            draft = draft,
                            capabilities = capabilities,
                            splitSupported = splitSupported,
                            expanded = showOptions,
                            onExpandedChange = { showOptions = it },
                            onEvent = onEvent,
                        )
                    }
                    Column(modifier = Modifier.weight(0.85f), verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap)) {
                        BackupReviewSection(
                            state = state,
                            selectedCapabilitiesSupported = selectedCapabilitiesSupported,
                            compressionSupported = compressionSupported,
                            splitSupported = splitSupported,
                            destinationFreeBytes = destinationFreeBytes,
                            activeBackup = activeBackup != null,
                            onEvent = onEvent,
                        )
                    }
                }
            } else {
                BackupSourceSection(
                    state = state,
                    draft = draft,
                    expanded = chooseSource,
                    advanced = showAdvancedSource,
                    onExpandedChange = { chooseSource = it },
                    onAdvancedChange = { showAdvancedSource = it },
                    onProvider = { providerTreeLauncher.launch(null) },
                    onEvent = onEvent,
                )
                BackupDestinationSection(
                    state = state,
                    draft = draft,
                    expanded = chooseDestination,
                    advanced = showAdvancedDestination,
                    onExpandedChange = { chooseDestination = it },
                    onAdvancedChange = { showAdvancedDestination = it },
                    onEvent = onEvent,
                )
                BackupOptionsSection(
                    draft = draft,
                    capabilities = capabilities,
                    splitSupported = splitSupported,
                    expanded = showOptions,
                    onExpandedChange = { showOptions = it },
                    onEvent = onEvent,
                )
                BackupReviewSection(
                    state = state,
                    selectedCapabilitiesSupported = selectedCapabilitiesSupported,
                    compressionSupported = compressionSupported,
                    splitSupported = splitSupported,
                    destinationFreeBytes = destinationFreeBytes,
                    activeBackup = activeBackup != null,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun BackupOperationFeedback(
    activeBackup: com.mikeyphw.bkx.android.core.operations.ActiveOperation?,
    result: OperationResultUiState?,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    if (activeBackup != null) {
        BkxSurfaceCard {
            BkxOperationProgress(
                title = "Backing up",
                phase = activeBackup.lastMessage.ifBlank { activeBackup.lastPhase },
                progress = activeBackup.progress,
                filesDone = activeBackup.filesDone,
                filesTotal = activeBackup.filesTotal,
                bytesDone = activeBackup.bytesDone,
                bytesTotal = activeBackup.bytesTotal,
            )
            BkxStatusPill(
                if (activeBackup.status == ActiveOperationStatus.Cancelling) "CANCELLING" else "RUNNING",
                if (activeBackup.status == ActiveOperationStatus.Cancelling) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            if (activeBackup.cancelFilePath != null && activeBackup.status != ActiveOperationStatus.Cancelling) {
                OutlinedButton(
                    onClick = {
                        onEvent(
                            BkxHomeEvent.SubmitOperationCancellation(
                                requestId = activeBackup.requestId,
                                cancelFilePath = activeBackup.cancelFilePath,
                                cancelRequestId = "cancel-${System.currentTimeMillis()}",
                                requestedAtEpochMillis = System.currentTimeMillis(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel backup") }
            }
        }
        return
    }
    when (result) {
        is OperationResultUiState.Success -> BkxSurfaceCard {
            BkxStatusPill("BACKUP COMPLETE")
            Text(result.summary, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Archives)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open archive library")
            }
        }
        is OperationResultUiState.Failed -> BkxSurfaceCard {
            BkxStatusPill("BACKUP FAILED", MaterialTheme.colorScheme.error)
            Text(result.failure.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(result.failure.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is OperationResultUiState.Cancelled -> BkxSurfaceCard {
            BkxStatusPill("BACKUP CANCELLED", MaterialTheme.colorScheme.tertiary)
            Text(result.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OperationResultUiState.NotStarted, null -> Unit
    }
}

@Composable
private fun BackupSourceSection(
    state: BkxHomeUiState,
    draft: BackupPlanDraft,
    expanded: Boolean,
    advanced: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAdvancedChange: (Boolean) -> Unit,
    onProvider: () -> Unit,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BkxSectionHeader("From")
    BkxSurfaceCard {
        val source = draft.source
        if (source != null) {
            LocationSummary(source.label, source.stagedPath, if (source.originalUri != null) "Files & other apps" else locationKind(source.stagedPath, state.rootAvailable))
            TextButton(onClick = { onExpandedChange(!expanded) }) { Text(if (expanded) "Done" else "Change source") }
        } else {
            Text("Choose what to back up", style = MaterialTheme.typography.titleMedium)
            Text("Pick a common location, browse the filesystem, or choose a folder from Files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onExpandedChange(true) }) { Text("Choose source") }
        }
        if (expanded) {
            BkxDivider()
            LocationChoice("Internal storage", BkxStorageLocations.SHARED_STORAGE, "Shared storage") {
                onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = source(BkxStorageLocations.SHARED_STORAGE, "Internal storage"))))
                onExpandedChange(false)
            }
            if (state.rootAvailable) {
                LocationChoice("Termux home", BkxStorageLocations.TERMUX_HOME, "Root") {
                    onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = source(BkxStorageLocations.TERMUX_HOME, "Termux home"))))
                    onExpandedChange(false)
                }
                LocationChoice("Termux prefix", BkxStorageLocations.TERMUX_PREFIX, "Root") {
                    onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(source = source(BkxStorageLocations.TERMUX_PREFIX, "Termux prefix"))))
                    onExpandedChange(false)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.BackupSource, draft.source?.stagedPath ?: BkxStorageLocations.SHARED_STORAGE)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Browse device") }
                OutlinedButton(onClick = onProvider, enabled = !state.providerImportInProgress, modifier = Modifier.weight(1f)) {
                    Text("Files & other apps")
                }
            }
            TextButton(onClick = { onAdvancedChange(!advanced) }) { Text(if (advanced) "Hide manual path" else "Manual path · Advanced") }
            if (advanced) {
                OutlinedTextField(
                    value = draft.source?.stagedPath.orEmpty(),
                    onValueChange = { path ->
                        onEvent(
                            BkxHomeEvent.UpdateBackupDraft(
                                draft.copy(source = path.trim().takeIf { it.startsWith("/") }?.let { source(it, it.substringAfterLast('/').ifBlank { "Source" }) }),
                            ),
                        )
                    },
                    label = { Text("Filesystem path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            state.providerImportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BackupDestinationSection(
    state: BkxHomeUiState,
    draft: BackupPlanDraft,
    expanded: Boolean,
    advanced: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAdvancedChange: (Boolean) -> Unit,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BkxSectionHeader("To")
    BkxSurfaceCard {
        val destination = draft.destination
        if (destination != null) {
            LocationSummary(destination.label, destination.stagedDirectoryPath, locationKind(destination.stagedDirectoryPath, false))
            TextButton(onClick = { onExpandedChange(!expanded) }) { Text(if (expanded) "Done" else "Change destination") }
        } else {
            Text("Choose where to save the archive", style = MaterialTheme.typography.titleMedium)
            Text("Save locally or connect Google Drive for first-class cloud backup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onExpandedChange(true) }) { Text("Choose destination") }
        }
        if (expanded) {
            BkxDivider()
            val defaultPath = "${BkxStorageLocations.SHARED_STORAGE}/BKX"
            LocationChoice("BKX backups", defaultPath, "Internal storage") {
                onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(destination = destination(defaultPath, "BKX backups"))))
                onExpandedChange(false)
            }
            if (state.googleDrive.connected) {
                LocationChoice("Google Drive", GoogleDriveRcloneConfig.rootUri, "Cloud · BKX folder") {
                    onEvent(
                        BkxHomeEvent.UpdateBackupDraft(
                            draft.copy(destination = BackupDestinationSelection("google-drive-bkx", "Google Drive", GoogleDriveRcloneConfig.rootUri)),
                        ),
                    )
                    onExpandedChange(false)
                }
            } else {
                BkxOutlinedSurface(
                    onClick = {
                        onEvent(BkxHomeEvent.SelectDestination(BkxDestination.More))
                        onEvent(BkxHomeEvent.OpenMoreRoute(MoreRoute.GoogleDrive))
                    },
                ) {
                    Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                    Text("Connect once, then back up directly to BKX on Drive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(
                onClick = {
                    val start = draft.destination?.stagedDirectoryPath
                        ?.takeIf { it.startsWith("/") || it.startsWith("rclone://", ignoreCase = true) }
                        ?: BkxStorageLocations.SHARED_STORAGE
                    onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.BackupDestination, start))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.googleDrive.connected) "Browse device or remote" else "Browse device") }
            TextButton(onClick = { onAdvancedChange(!advanced) }) { Text(if (advanced) "Hide manual path" else "Manual path · Advanced") }
            if (advanced) {
                OutlinedTextField(
                    value = draft.destination?.stagedDirectoryPath.orEmpty(),
                    onValueChange = { path ->
                        onEvent(
                            BkxHomeEvent.UpdateBackupDraft(
                                draft.copy(destination = path.trim().takeIf {
                                    it.startsWith("/") || it.startsWith("rclone://", ignoreCase = true)
                                }?.let { destination(it, if (it.startsWith("rclone://", ignoreCase = true)) "Remote backup" else it.substringAfterLast('/').ifBlank { "Backup destination" }) }),
                            ),
                        )
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
private fun BackupOptionsSection(
    draft: BackupPlanDraft,
    capabilities: Set<String>,
    splitSupported: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BkxSectionHeader("Options", if (expanded) "Done" else "Customize") { onExpandedChange(!expanded) }
    BkxSurfaceCard {
        Text(
            buildList {
                add(draft.compression.displayLabel())
                if (draft.verification.verifyAfterCreate) add("Verify")
                if (draft.split.enabled) add("Split")
            }.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (draft.filters.includeHidden) "Hidden files included; symbolic links are skipped safely." else "Hidden files excluded; symbolic links are skipped safely.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            BkxDivider()
            OutlinedTextField(
                value = draft.archiveName,
                onValueChange = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(archiveName = it))) },
                label = { Text("Backup name (optional)") },
                supportingText = { Text("BKX adds .bakery automatically.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("Compression", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupCompression.entries.forEach { compression ->
                    FilterChip(
                        selected = draft.compression == compression,
                        onClick = { onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(compression = compression))) },
                        enabled = compression.requiredCapability in capabilities,
                        label = { Text(compression.displayLabel()) },
                    )
                }
            }
            BkxSettingSwitch(
                title = "Verify after backup",
                summary = "Check the completed archive before reporting success.",
                checked = draft.verification.verifyAfterCreate,
                onCheckedChange = { enabled -> onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(verification = draft.verification.copy(verifyAfterCreate = enabled)))) },
            )
            BkxSettingSwitch(
                title = "Include hidden files",
                summary = "Include dotfiles and hidden configuration data.",
                checked = draft.filters.includeHidden,
                onCheckedChange = { enabled -> onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(filters = draft.filters.copy(includeHidden = enabled)))) },
            )
            BkxSettingSwitch(
                title = "Split large backups",
                summary = if (splitSupported) "Create 1 GB parts for constrained filesystems." else "This BKX engine does not support split archives.",
                checked = draft.split.enabled,
                enabled = splitSupported,
                onCheckedChange = { enabled ->
                    onEvent(BkxHomeEvent.UpdateBackupDraft(draft.copy(split = if (enabled) BackupSplitPolicy(true, 1024L * 1024L * 1024L) else BackupSplitPolicy())))
                },
            )
        }
    }
}

@Composable
private fun BackupReviewSection(
    state: BkxHomeUiState,
    selectedCapabilitiesSupported: Boolean,
    compressionSupported: Boolean,
    splitSupported: Boolean,
    destinationFreeBytes: Long?,
    activeBackup: Boolean,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    val planning = state.backupPlanning
    val draft = planning.draft
    BkxSectionHeader("Review")
    BkxSurfaceCard {
        BkxKeyValue("Source", draft.source?.label ?: "Not selected")
        BkxKeyValue("Destination", draft.destination?.label ?: "Not selected")
        BkxKeyValue("Compression", draft.compression.displayLabel())
        planning.preview?.let { preview ->
            BkxDivider()
            Text("Preflight", style = MaterialTheme.typography.titleMedium)
            BkxKeyValue("Files", preview.filesTotal?.toString() ?: "—")
            BkxKeyValue("Source size", formatBytes(preview.bytesTotal))
            if (draft.destination?.stagedDirectoryPath?.startsWith("rclone://", ignoreCase = true) == true) {
                BkxKeyValue("Destination storage", "Managed by Google Drive")
            } else {
                BkxKeyValue("Free at destination", formatBytes(destinationFreeBytes))
            }
            if (preview.bytesTotal != null && destinationFreeBytes != null && draft.destination?.stagedDirectoryPath?.startsWith("rclone://", ignoreCase = true) != true) {
                val enough = destinationFreeBytes > preview.bytesTotal
                BkxStatusPill(if (enough) "SPACE AVAILABLE" else "LOW SPACE", if (enough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            preview.warnings.forEach { Text(it.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!selectedCapabilitiesSupported) {
            Text(
                when {
                    !compressionSupported -> "The connected engine does not support ${draft.compression.displayLabel()} compression."
                    draft.split.enabled && !splitSupported -> "The connected engine does not support split archives."
                    draft.destination?.stagedDirectoryPath?.startsWith("rclone://", ignoreCase = true) == true && "backup.remote" !in state.backendToolState.capabilities ->
                        "The connected engine does not support remote backup destinations."
                    else -> "The selected options are not supported by this engine."
                },
                color = MaterialTheme.colorScheme.error,
            )
        }
        planning.validationIssues.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.RequestBackupPlan) },
                enabled = planning.canRequestPlan && selectedCapabilitiesSupported,
                modifier = Modifier.weight(1f),
            ) { Text(if (planning.preview == null) "Review" else "Refresh") }
            Button(
                onClick = { onEvent(BkxHomeEvent.CreateBackup) },
                enabled = planning.canCreateBackup && selectedCapabilitiesSupported && !activeBackup,
                modifier = Modifier.weight(1f),
            ) { Text("Start backup") }
        }
        if (planning.preview == null && planning.canRequestPlan) {
            Text("Review scans the source and checks the backup plan before anything is written.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocationSummary(label: String, path: String, supporting: String) {
    Text(label, style = MaterialTheme.typography.titleLarge)
    Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LocationChoice(label: String, path: String, supporting: String, onClick: () -> Unit) {
    BkxOutlinedSurface(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun source(path: String, label: String): BackupSourceSelection = BackupSourceSelection(
    sourceId = "preset-${path.hashCode()}",
    label = label,
    stagedPath = path,
)

private fun destination(path: String, label: String): BackupDestinationSelection = BackupDestinationSelection(
    destinationId = "path-${path.hashCode()}",
    label = label,
    stagedDirectoryPath = path,
)

private fun BackupCompression.displayLabel(): String = when (this) {
    BackupCompression.None -> "None"
    BackupCompression.Gzip -> "Gzip"
    BackupCompression.Zstd -> "Zstd"
}

private fun locationKind(path: String, rootAvailable: Boolean): String = when {
    path.startsWith(GoogleDriveRcloneConfig.rootUri, ignoreCase = true) -> "Google Drive"
    path.startsWith("rclone://", ignoreCase = true) -> "Remote storage"
    path == BkxStorageLocations.TERMUX_HOME || path == BkxStorageLocations.TERMUX_PREFIX -> if (rootAvailable) "Root filesystem" else "Private filesystem"
    path.startsWith(BkxStorageLocations.SHARED_STORAGE) -> "Internal storage"
    else -> "Filesystem"
}

private fun usableSpaceFor(context: Context, path: String?): Long? {
    if (path.isNullOrBlank() || !path.startsWith('/')) return null
    var current: File? = File(path)
    while (current != null && !current.exists()) current = current.parentFile
    val existing = current ?: return null
    val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(existing))
        }.getOrElse { StatFs(existing.absolutePath).availableBytes }
    } else {
        StatFs(existing.absolutePath).availableBytes
    }
    return bytes.takeIf { it > 0L }
}
