package com.mikeyphw.bkx.android.feature.archives

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxEmptyState
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxOutlinedSurface
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxPathRow
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.archives.ArchiveEntryType
import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.archives.ArchiveVerificationUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveWorkflowUiState
import com.mikeyphw.bkx.android.core.files.FilesystemSelectionTarget
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationPolicy
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestoreOutputMode
import com.mikeyphw.bkx.android.feature.home.formatBytes

@Composable
fun ArchivesScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val archives = state.archiveReadOnly
    var manualPath by rememberSaveable { mutableStateOf(archives.selectedArchive?.stagedPath.orEmpty()) }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var archiveSort by rememberSaveable { mutableStateOf(ArchiveSort.Newest.name) }
    var entryQuery by rememberSaveable { mutableStateOf("") }
    var entryFilter by rememberSaveable { mutableStateOf(ArchiveEntryFilter.All.name) }
    val providerArchiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.toString()?.let { onEvent(BkxHomeEvent.ImportProviderArchive(it)) }
    }

    LaunchedEffect(archives.selectedArchive?.stagedPath) {
        val selectedPath = archives.selectedArchive?.stagedPath
        if (!selectedPath.isNullOrBlank()) manualPath = selectedPath
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader(
            title = "Archives",
            subtitle = "Inspect, verify, browse, and restore BKX archives without exposing engine internals.",
        )

        BkxSectionHeader("Your archives", "Refresh") { onEvent(BkxHomeEvent.RefreshArchives) }
        when (val listState = archives.listState) {
            ArchiveWorkflowUiState.Idle -> BkxEmptyState("Load your archives", "BKX indexes its bounded local/removable storage roots and completed backup history. You can also open any archive by path or document provider below.")
            ArchiveWorkflowUiState.Loading -> BkxSurfaceCard { Text("Loading archives…") }
            is ArchiveWorkflowUiState.Failed -> BkxSurfaceCard {
                Text("Could not load archives", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(listState.summary)
            }
            is ArchiveWorkflowUiState.Content -> if (listState.value.isEmpty()) {
                BkxEmptyState("No indexed archives yet", "Create a backup, browse storage, or import an archive from a document provider.")
            } else {
                OutlinedTextField(
                    value = archiveQuery,
                    onValueChange = { archiveQuery = it },
                    label = { Text("Search archives") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArchiveSort.entries.forEach { sort ->
                        FilterChip(
                            selected = archiveSort == sort.name,
                            onClick = { archiveSort = sort.name },
                            label = { Text(sort.label) },
                        )
                    }
                }
                val normalizedQuery = archiveQuery.trim().lowercase()
                val matching = listState.value
                    .filter { item ->
                        normalizedQuery.isBlank() ||
                            item.archive.displayName.lowercase().contains(normalizedQuery) ||
                            item.archive.stagedPath.lowercase().contains(normalizedQuery)
                    }
                    .let { items ->
                        when (ArchiveSort.valueOf(archiveSort)) {
                            ArchiveSort.Newest -> items.sortedByDescending { it.createdAtEpochMillis ?: Long.MIN_VALUE }
                            ArchiveSort.Name -> items.sortedBy { it.archive.displayName.lowercase() }
                            ArchiveSort.Size -> items.sortedByDescending { it.sizeBytes ?: -1L }
                        }
                    }
                if (matching.isEmpty()) {
                    BkxEmptyState("No matching archives", "Try a different archive name or path.")
                } else {
                    matching.forEach { item ->
                        BkxPathRow(
                            label = item.archive.displayName,
                            path = item.archive.stagedPath,
                            supporting = buildList {
                                item.sizeBytes?.let { add(formatBytes(it)) }
                                item.createdAtEpochMillis?.let { add(com.mikeyphw.bkx.android.feature.home.formatTime(it)) }
                            }.joinToString(" • ").ifBlank { null },
                            onClick = {
                                onEvent(BkxHomeEvent.SelectArchive(item.archive))
                                onEvent(BkxHomeEvent.LoadArchiveDetails(item.archive))
                            },
                        )
                    }
                }
            }
        }

        BkxSurfaceCard {
            Text("Open an archive by path", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = manualPath,
                onValueChange = { manualPath = it },
                label = { Text("Archive path") },
                supportingText = { Text("Root paths are supported when root is active.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.Archive, manualPath.takeIf { it.startsWith("/") } ?: "/storage/emulated/0")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Browse files") }
                OutlinedButton(
                    onClick = { providerArchiveLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.providerImportInProgress,
                    modifier = Modifier.weight(1f),
                ) { Text("Document provider") }
            }
            state.providerImportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(
                onClick = {
                    val archive = archiveLocation(manualPath)
                    onEvent(BkxHomeEvent.SelectArchive(archive))
                    onEvent(BkxHomeEvent.LoadArchiveDetails(archive))
                },
                enabled = manualPath.trim().startsWith("/"),
            ) { Text("Open archive") }
        }

        archives.selectedArchive?.let { archive ->
            BkxSectionHeader("Archive details")
            BkxSurfaceCard {
                Text(archive.displayName, style = MaterialTheme.typography.titleLarge)
                Text(archive.stagedPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val infoState = archives.infoState) {
                    ArchiveWorkflowUiState.Idle -> Text("Open the archive to load details.")
                    ArchiveWorkflowUiState.Loading -> Text("Reading archive metadata…")
                    is ArchiveWorkflowUiState.Failed -> Text(infoState.summary, color = MaterialTheme.colorScheme.error)
                    is ArchiveWorkflowUiState.Content -> {
                        BkxKeyValue("Entries", infoState.value.entryCount?.toString() ?: "—")
                        BkxKeyValue("Uncompressed", formatBytes(infoState.value.uncompressedSizeBytes))
                        BkxKeyValue("Format", infoState.value.formatVersion ?: "—")
                    }
                }
                BkxDivider()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEvent(BkxHomeEvent.BrowseArchive(archive)) }, modifier = Modifier.weight(1f)) {
                        Text("Browse")
                    }
                    OutlinedButton(onClick = { onEvent(BkxHomeEvent.VerifyArchive(archive)) }, modifier = Modifier.weight(1f)) {
                        Text("Verify")
                    }
                    Button(onClick = { onEvent(BkxHomeEvent.PrepareRestore(archive)) }, modifier = Modifier.weight(1f)) {
                        Text("Restore")
                    }
                }
                when (val verification = archives.verification) {
                    ArchiveVerificationUiState.NotStarted -> Unit
                    is ArchiveVerificationUiState.Passed -> BkxStatusPill("VERIFIED")
                    is ArchiveVerificationUiState.Failed -> {
                        BkxStatusPill("CHECK FAILED", MaterialTheme.colorScheme.error)
                        Text(verification.summary, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            archives.browsePage?.let { page ->
                BkxSectionHeader("Contents")
                BkxSurfaceCard {
                    OutlinedTextField(
                        value = entryQuery,
                        onValueChange = { entryQuery = it },
                        label = { Text("Filter this page") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ArchiveEntryFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = entryFilter == filter.name,
                                onClick = { entryFilter = filter.name },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    val query = entryQuery.trim().lowercase()
                    val selectedFilter = ArchiveEntryFilter.valueOf(entryFilter)
                    val visibleEntries = page.entries
                        .filter { entry ->
                            (query.isBlank() || entry.path.lowercase().contains(query)) && selectedFilter.matches(entry.type)
                        }
                        .sortedWith(compareBy({ it.path.substringBeforeLast('/', "").lowercase() }, { it.type != ArchiveEntryType.Directory }, { it.path.lowercase() }))
                    Text(
                        "Showing ${visibleEntries.size} of ${page.entries.size} loaded entries",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visibleEntries.isEmpty()) {
                        Text(if (page.entries.isEmpty()) "This archive page is empty." else "No entries match this filter.")
                    } else {
                        visibleEntries.forEach { entry ->
                            BkxOutlinedSurface {
                                Text(
                                    when (entry.type) {
                                        ArchiveEntryType.Directory -> "Folder"
                                        ArchiveEntryType.File -> "File"
                                        ArchiveEntryType.Symlink -> "Link"
                                        ArchiveEntryType.Special -> "Special"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(entry.path, style = MaterialTheme.typography.bodyMedium)
                                entry.sizeBytes?.let { Text(formatBytes(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (page.nextPageToken != null) {
                        OutlinedButton(
                            onClick = { onEvent(BkxHomeEvent.BrowseArchive(archive, page.nextPageToken)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Load more") }
                    }
                }
            }

            if (state.restore.draft.archive?.stagedPath == archive.stagedPath) {
                RestorePanel(state, onEvent)
            }
        }
    }
}

@Composable
private fun RestorePanel(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    val restore = state.restore
    val draft = restore.draft
    var confirmationText by remember(restore.preview?.planToken) { mutableStateOf("") }

    BkxSectionHeader("Restore")
    BkxSurfaceCard {
        Text("Choose where the restored files should go.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.outputMode == RestoreOutputMode.FreshDirectory,
                onClick = { onEvent(BkxHomeEvent.UpdateRestoreDraft(draft.copy(outputMode = RestoreOutputMode.FreshDirectory))) },
                label = { Text("New folder") },
            )
            FilterChip(
                selected = draft.outputMode == RestoreOutputMode.ExistingDirectory,
                onClick = { onEvent(BkxHomeEvent.UpdateRestoreDraft(draft.copy(outputMode = RestoreOutputMode.ExistingDirectory))) },
                label = { Text("Existing folder") },
            )
        }
        Text(
            if (draft.outputMode == RestoreOutputMode.FreshDirectory)
                "Enter a new destination path. BKX will refuse to reuse an existing directory in New folder mode."
            else
                "Enter the existing directory which should receive the restored files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.destination?.stagedDirectoryPath.orEmpty(),
            onValueChange = { path ->
                onEvent(
                    BkxHomeEvent.UpdateRestoreDraft(
                        draft.copy(
                            destination = path.trim().takeIf { it.startsWith("/") }?.let {
                                RestoreDestinationSelection(
                                    destinationId = "restore-${it.hashCode()}",
                                    label = it.substringAfterLast('/').ifBlank { "Restore destination" },
                                    stagedDirectoryPath = it,
                                )
                            },
                        ),
                    ),
                )
            },
            label = { Text(if (draft.outputMode == RestoreOutputMode.FreshDirectory) "New destination path" else "Existing destination path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(
            onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.RestoreDestination, draft.destination?.stagedDirectoryPath ?: "/storage/emulated/0")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Browse destination") }
        BkxSettingSwitch(
            title = "Verify before restore",
            summary = "Check the archive before writing restored files.",
            checked = draft.verifyBeforeRestore,
            onCheckedChange = { enabled -> onEvent(BkxHomeEvent.UpdateRestoreDraft(draft.copy(verifyBeforeRestore = enabled))) },
        )
        restore.validationIssues.forEach { issue -> Text(issue.message, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(
            onClick = { onEvent(BkxHomeEvent.RequestRestorePreview) },
            enabled = restore.canPreview,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (restore.preview == null) "Review restore" else "Review again") }

        restore.preview?.let { preview ->
            BkxDivider()
            BkxStatusPill(if (preview.isExecutable) "SAFE TO RESTORE" else "BLOCKED")
            BkxKeyValue("Selection", if (preview.fullRestore) "Entire archive" else "${preview.selectedPathCount} items")
            BkxKeyValue("Existing files affected", preview.overwriteSummary.totalOverwrites.toString())
            preview.verificationHash?.let { BkxKeyValue("Verification", "Passed") }
            preview.pathSafetySummary.unsafePaths.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.pathSafetySummary.blockedLinks.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.pathSafetySummary.blockedSpecialFiles.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            val confirmationRequired = preview.requiresDestructiveConfirmation ||
                RestoreDestinationPolicy.requiresRootConfirmation(draft.destination?.stagedDirectoryPath)
            if (confirmationRequired) {
                val requiredPhrase = RestoreExtractionConfirmation.requiredPhrase(draft)
                val reason = if (RestoreDestinationPolicy.requiresRootConfirmation(draft.destination?.stagedDirectoryPath)) {
                    "This destination is root-sensitive. Type $requiredPhrase to confirm privileged writes."
                } else {
                    "This restore would replace existing files. Type $requiredPhrase to confirm."
                }
                Text(reason, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = { value ->
                        confirmationText = value
                        if (value == requiredPhrase) {
                            onEvent(
                                BkxHomeEvent.ConfirmRestoreExtraction(
                                    RestoreExtractionConfirmation(
                                        draftFingerprint = draft.fingerprint(),
                                        planToken = preview.planToken,
                                        acknowledgedOverwriteCount = preview.overwriteSummary.totalOverwrites,
                                        confirmationPhrase = value,
                                        confirmedAtEpochMillis = System.currentTimeMillis(),
                                    ),
                                ),
                            )
                        }
                    },
                    label = { Text("Confirmation") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Button(
                onClick = { onEvent(BkxHomeEvent.ExecuteRestore) },
                enabled = restore.canExtract,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore files") }
        }
    }
}

private enum class ArchiveSort(val label: String) {
    Newest("Newest"),
    Name("Name"),
    Size("Largest"),
}

private enum class ArchiveEntryFilter(val label: String) {
    All("All"),
    Files("Files"),
    Folders("Folders"),
    Links("Links"),
    Special("Special"),
    ;

    fun matches(type: ArchiveEntryType): Boolean = when (this) {
        All -> true
        Files -> type == ArchiveEntryType.File
        Folders -> type == ArchiveEntryType.Directory
        Links -> type == ArchiveEntryType.Symlink
        Special -> type == ArchiveEntryType.Special
    }
}

private fun archiveLocation(rawPath: String): ArchiveLocation {
    val path = rawPath.trim()
    return ArchiveLocation(
        archiveId = "archive-${path.hashCode().toUInt().toString(16)}",
        displayName = path.substringAfterLast('/').ifBlank { "Archive" },
        stagedPath = path,
    )
}
