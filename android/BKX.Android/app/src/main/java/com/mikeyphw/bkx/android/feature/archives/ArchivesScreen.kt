package com.mikeyphw.bkx.android.feature.archives

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mikeyphw.bkx.android.app.navigation.ArchiveDetailSection
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
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationPolicy
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestoreOutputMode
import com.mikeyphw.bkx.android.feature.home.formatBytes
import com.mikeyphw.bkx.android.feature.home.formatTime
import com.mikeyphw.bkx.android.storage.BkxStorageLocations

@Composable
fun ArchivesScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val archives = state.archiveReadOnly
    BackHandler(enabled = archives.selectedArchive != null) { onEvent(BkxHomeEvent.CloseArchiveDetails) }
    var manualPath by rememberSaveable { mutableStateOf(archives.selectedArchive?.stagedPath.orEmpty()) }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var archiveSort by rememberSaveable { mutableStateOf(ArchiveSort.Newest.name) }
    var showOpenOther by rememberSaveable { mutableStateOf(false) }
    val providerArchiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.toString()?.let { onEvent(BkxHomeEvent.ImportProviderArchive(it)) }
    }
    LaunchedEffect(archives.selectedArchive?.stagedPath) {
        archives.selectedArchive?.stagedPath?.takeIf { it.isNotBlank() }?.let { manualPath = it }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ArchiveListPane(
                    state = state,
                    query = archiveQuery,
                    sort = archiveSort,
                    showOpenOther = showOpenOther,
                    manualPath = manualPath,
                    onQueryChange = { archiveQuery = it },
                    onSortChange = { archiveSort = it },
                    onToggleOpenOther = { showOpenOther = !showOpenOther },
                    onManualPathChange = { manualPath = it },
                    onProvider = { providerArchiveLauncher.launch(arrayOf("*/*")) },
                    onEvent = onEvent,
                    modifier = Modifier.weight(0.9f).fillMaxHeight(),
                )
                val selected = archives.selectedArchive
                if (selected == null) {
                    Column(
                        modifier = Modifier.weight(1.1f).fillMaxHeight().padding(end = BkxTokens.ScreenPadding, top = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        BkxEmptyState("Select an archive", "Choose an archive on the left to inspect, verify, browse, or restore it.")
                    }
                } else {
                    ArchiveDetailPane(state, selected, onEvent, showBack = false, modifier = Modifier.weight(1.1f).fillMaxHeight())
                }
            }
        } else {
            val selected = archives.selectedArchive
            if (selected == null) {
                ArchiveListPane(
                    state = state,
                    query = archiveQuery,
                    sort = archiveSort,
                    showOpenOther = showOpenOther,
                    manualPath = manualPath,
                    onQueryChange = { archiveQuery = it },
                    onSortChange = { archiveSort = it },
                    onToggleOpenOther = { showOpenOther = !showOpenOther },
                    onManualPathChange = { manualPath = it },
                    onProvider = { providerArchiveLauncher.launch(arrayOf("*/*")) },
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ArchiveDetailPane(state, selected, onEvent, showBack = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ArchiveListPane(
    state: BkxHomeUiState,
    query: String,
    sort: String,
    showOpenOther: Boolean,
    manualPath: String,
    onQueryChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onToggleOpenOther: () -> Unit,
    onManualPathChange: (String) -> Unit,
    onProvider: () -> Unit,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier,
) {
    val listState = state.archiveReadOnly.listState
    var showManualPath by rememberSaveable { mutableStateOf(false) }
    val listedItems = when (listState) {
        is ArchiveWorkflowUiState.Content -> listState.value
        else -> emptyList()
    }
    val visible = listedItems
        .filter { item ->
            val normalized = query.trim().lowercase()
            normalized.isBlank() || item.archive.displayName.lowercase().contains(normalized) || item.archive.stagedPath.lowercase().contains(normalized)
        }
        .let { items ->
            when (ArchiveSort.valueOf(sort)) {
                ArchiveSort.Newest -> items.sortedByDescending { it.createdAtEpochMillis ?: Long.MIN_VALUE }
                ArchiveSort.Name -> items.sortedBy { it.archive.displayName.lowercase() }
                ArchiveSort.Size -> items.sortedByDescending { it.sizeBytes ?: -1L }
            }
        }

    LazyColumn(
        modifier = modifier.padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.CardGap),
    ) {
        item {
            BkxPageHeader("Archives", "Your BKX restore points, local or imported.")
        }
        item {
            BkxSectionHeader("Archive library", "Refresh") { onEvent(BkxHomeEvent.RefreshArchives) }
        }
        when (listState) {
            ArchiveWorkflowUiState.Idle -> item { BkxEmptyState("Load your archives", "Refresh the library or open another archive from Files.") }
            ArchiveWorkflowUiState.Loading -> item { BkxSurfaceCard { Text("Loading archives…") } }
            is ArchiveWorkflowUiState.Failed -> item {
                BkxSurfaceCard {
                    Text("Could not load archives", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(listState.summary)
                }
            }
            is ArchiveWorkflowUiState.Content -> {
                if (listState.value.isEmpty()) {
                    item { BkxEmptyState("No archives yet", "Create a backup or open an existing .bakery archive.") }
                } else {
                    item {
                        OutlinedTextField(value = query, onValueChange = onQueryChange, label = { Text("Search archives") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ArchiveSort.entries.chunked(2).forEach { options ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    options.forEach { option ->
                                        FilterChip(selected = sort == option.name, onClick = { onSortChange(option.name) }, label = { Text(option.label) })
                                    }
                                }
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item { BkxEmptyState("No matching archives", "Try a different name or path.") }
                    } else {
                        items(visible, key = { it.archive.archiveId }) { item ->
                            BkxPathRow(
                                label = item.archive.displayName,
                                path = item.archive.stagedPath,
                                supporting = buildList {
                                    item.sizeBytes?.let { add(formatBytes(it)) }
                                    item.createdAtEpochMillis?.let { add(formatTime(it)) }
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
        }
        item {
            OutlinedButton(onClick = onToggleOpenOther, modifier = Modifier.fillMaxWidth()) {
                Text(if (showOpenOther) "Hide open options" else "Open another archive")
            }
        }
        if (showOpenOther) {
            item {
                BkxSurfaceCard {
                    Text("Open another archive", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.Archive, manualPath.takeIf { it.startsWith("/") } ?: BkxStorageLocations.SHARED_STORAGE)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Browse device") }
                        OutlinedButton(onClick = onProvider, enabled = !state.providerImportInProgress, modifier = Modifier.weight(1f)) {
                            Text("Files & other apps")
                        }
                    }
                    TextButton(onClick = { showManualPath = !showManualPath }) {
                        Text(if (showManualPath) "Hide manual path" else "Manual path · Advanced")
                    }
                    if (showManualPath) {
                        OutlinedTextField(
                            value = manualPath,
                            onValueChange = onManualPathChange,
                            label = { Text("Archive path") },
                            supportingText = { Text("Root paths work when root is active.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val archive = archiveLocation(manualPath)
                                onEvent(BkxHomeEvent.SelectArchive(archive))
                                onEvent(BkxHomeEvent.LoadArchiveDetails(archive))
                            },
                            enabled = manualPath.trim().startsWith("/"),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open archive") }
                    }
                    state.providerImportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun ArchiveDetailPane(
    state: BkxHomeUiState,
    archive: ArchiveLocation,
    onEvent: (BkxHomeEvent) -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val archives = state.archiveReadOnly
    val section = state.archiveDetailSection
    var entryQuery by rememberSaveable(archive.archiveId) { mutableStateOf("") }
    var entryFilter by rememberSaveable(archive.archiveId) { mutableStateOf(ArchiveEntryFilter.All.name) }

    LazyColumn(
        modifier = modifier.padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.CardGap),
    ) {
        if (showBack) {
            item { OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseArchiveDetails) }) { Text("Back to archives") } }
        }
        item {
            BkxPageHeader(archive.displayName, "Inspect this archive, verify its integrity, browse contents, or restore it.")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ArchiveDetailSection.entries.chunked(2).forEach { tabs ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEach { tab ->
                            FilterChip(
                                selected = section == tab,
                                onClick = {
                                    when (tab) {
                                        ArchiveDetailSection.Overview -> onEvent(BkxHomeEvent.ShowArchiveSection(tab))
                                        ArchiveDetailSection.Contents -> onEvent(BkxHomeEvent.BrowseArchive(archive))
                                        ArchiveDetailSection.Restore -> onEvent(BkxHomeEvent.PrepareRestore(archive))
                                    }
                                },
                                label = { Text(tab.label()) },
                            )
                        }
                    }
                }
            }
        }
        when (section) {
            ArchiveDetailSection.Overview -> {
                item { ArchiveOverview(state, archive, onEvent) }
            }
            ArchiveDetailSection.Contents -> {
                item {
                    OutlinedTextField(value = entryQuery, onValueChange = { entryQuery = it }, label = { Text("Filter loaded contents") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ArchiveEntryFilter.entries.take(3).forEach { filter ->
                                FilterChip(selected = entryFilter == filter.name, onClick = { entryFilter = filter.name }, label = { Text(filter.label) })
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ArchiveEntryFilter.entries.drop(3).forEach { filter ->
                                FilterChip(selected = entryFilter == filter.name, onClick = { entryFilter = filter.name }, label = { Text(filter.label) })
                            }
                        }
                    }
                }
                val page = archives.browsePage
                if (page == null) {
                    item { BkxSurfaceCard { Text("Loading archive contents…") } }
                } else {
                    val q = entryQuery.trim().lowercase()
                    val selectedFilter = ArchiveEntryFilter.valueOf(entryFilter)
                    val entries = page.entries
                        .filter { (q.isBlank() || it.path.lowercase().contains(q)) && selectedFilter.matches(it.type) }
                        .sortedWith(compareBy({ it.path.substringBeforeLast('/', "").lowercase() }, { it.type != ArchiveEntryType.Directory }, { it.path.lowercase() }))
                    item {
                        Text("Showing ${entries.size} of ${page.entries.size} loaded entries", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entries.isEmpty()) {
                        item { BkxEmptyState("No matching entries", "Try another content filter.") }
                    } else {
                        items(entries, key = { "${it.type}:${it.path}" }) { entry ->
                            BkxOutlinedSurface {
                                Text(entry.type.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(entry.path, style = MaterialTheme.typography.bodyMedium)
                                entry.sizeBytes?.let { Text(formatBytes(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (page.nextPageToken != null) {
                        item {
                            OutlinedButton(onClick = { onEvent(BkxHomeEvent.BrowseArchive(archive, page.nextPageToken)) }, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                        }
                    }
                }
            }
            ArchiveDetailSection.Restore -> {
                item { RestorePanel(state, onEvent) }
            }
        }
    }
}

@Composable
private fun ArchiveOverview(state: BkxHomeUiState, archive: ArchiveLocation, onEvent: (BkxHomeEvent) -> Unit) {
    val archives = state.archiveReadOnly
    BkxSurfaceCard {
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
        when (val verification = archives.verification) {
            ArchiveVerificationUiState.NotStarted -> Unit
            is ArchiveVerificationUiState.Passed -> BkxStatusPill("VERIFIED")
            is ArchiveVerificationUiState.Failed -> {
                BkxStatusPill("CHECK FAILED", MaterialTheme.colorScheme.error)
                Text(verification.summary, color = MaterialTheme.colorScheme.error)
            }
        }
        BkxDivider()
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.VerifyArchive(archive)) }, modifier = Modifier.fillMaxWidth()) { Text("Verify archive") }
        Button(onClick = { onEvent(BkxHomeEvent.PrepareRestore(archive)) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from this archive") }
    }
}

@Composable
private fun RestorePanel(state: BkxHomeUiState, onEvent: (BkxHomeEvent) -> Unit) {
    val restore = state.restore
    val draft = restore.draft
    var confirmationText by remember(restore.preview?.planToken) { mutableStateOf("") }
    var chooseDestination by rememberSaveable(draft.archive?.archiveId.orEmpty()) { mutableStateOf(draft.destination == null) }
    var advancedPath by rememberSaveable(draft.archive?.archiveId.orEmpty()) { mutableStateOf(false) }

    BkxSurfaceCard {
        Text("Restore destination", style = MaterialTheme.typography.titleLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        draft.destination?.let { destination ->
            BkxPathRow(label = destination.label, path = destination.stagedDirectoryPath, supporting = if (RestoreDestinationPolicy.requiresRootConfirmation(destination.stagedDirectoryPath)) "Root-sensitive destination" else "Filesystem")
            TextButton(onClick = { chooseDestination = !chooseDestination }) { Text(if (chooseDestination) "Done" else "Change destination") }
        }
        if (draft.destination == null || chooseDestination) {
            OutlinedButton(
                onClick = { onEvent(BkxHomeEvent.OpenFilesystemBrowser(FilesystemSelectionTarget.RestoreDestination, draft.destination?.stagedDirectoryPath ?: BkxStorageLocations.SHARED_STORAGE)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Browse device") }
            TextButton(onClick = { advancedPath = !advancedPath }) { Text(if (advancedPath) "Hide manual path" else "Manual path · Advanced") }
            if (advancedPath) {
                OutlinedTextField(
                    value = draft.destination?.stagedDirectoryPath.orEmpty(),
                    onValueChange = { path ->
                        onEvent(
                            BkxHomeEvent.UpdateRestoreDraft(
                                draft.copy(
                                    destination = path.trim().takeIf { it.startsWith("/") }?.let {
                                        RestoreDestinationSelection("restore-${it.hashCode()}", it.substringAfterLast('/').ifBlank { "Restore destination" }, it)
                                    },
                                ),
                            ),
                        )
                    },
                    label = { Text("Destination path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        BkxSettingSwitch(
            title = "Verify before restore",
            summary = "Check the archive before writing restored files.",
            checked = draft.verifyBeforeRestore,
            onCheckedChange = { enabled -> onEvent(BkxHomeEvent.UpdateRestoreDraft(draft.copy(verifyBeforeRestore = enabled))) },
        )
        restore.validationIssues.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.RequestRestorePreview) }, enabled = restore.canPreview, modifier = Modifier.fillMaxWidth()) {
            Text(if (restore.preview == null) "Review restore" else "Review again")
        }
        restore.preview?.let { preview ->
            BkxDivider()
            BkxStatusPill(if (preview.isExecutable) "SAFE TO RESTORE" else "BLOCKED")
            BkxKeyValue("Selection", if (preview.fullRestore) "Entire archive" else "${preview.selectedPathCount} items")
            BkxKeyValue("Existing files affected", preview.overwriteSummary.totalOverwrites.toString())
            preview.verificationHash?.let { BkxKeyValue("Verification", "Passed") }
            preview.pathSafetySummary.unsafePaths.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.pathSafetySummary.blockedLinks.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.pathSafetySummary.blockedSpecialFiles.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            val confirmationRequired = preview.requiresDestructiveConfirmation || RestoreDestinationPolicy.requiresRootConfirmation(draft.destination?.stagedDirectoryPath)
            if (confirmationRequired) {
                val phrase = RestoreExtractionConfirmation.requiredPhrase(draft)
                Text(
                    if (phrase == RestoreExtractionConfirmation.ROOT_CONFIRMATION_PHRASE) "This destination is root-sensitive. Type $phrase to confirm privileged writes." else "Existing files may be replaced. Type $phrase to confirm.",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = { value ->
                        confirmationText = value
                        if (value == phrase) {
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
            Button(onClick = { onEvent(BkxHomeEvent.ExecuteRestore) }, enabled = restore.canExtract, modifier = Modifier.fillMaxWidth()) { Text("Restore files") }
        }
    }
}

private enum class ArchiveSort(val label: String) { Newest("Newest"), Name("Name"), Size("Largest") }
private enum class ArchiveEntryFilter(val label: String) {
    All("All"), Files("Files"), Folders("Folders"), Links("Links"), Special("Special");
    fun matches(type: ArchiveEntryType): Boolean = when (this) {
        All -> true
        Files -> type == ArchiveEntryType.File
        Folders -> type == ArchiveEntryType.Directory
        Links -> type == ArchiveEntryType.Symlink
        Special -> type == ArchiveEntryType.Special
    }
}

private fun ArchiveDetailSection.label(): String = when (this) {
    ArchiveDetailSection.Overview -> "Overview"
    ArchiveDetailSection.Contents -> "Contents"
    ArchiveDetailSection.Restore -> "Restore"
}

private fun ArchiveEntryType.label(): String = when (this) {
    ArchiveEntryType.Directory -> "Folder"
    ArchiveEntryType.File -> "File"
    ArchiveEntryType.Symlink -> "Link"
    ArchiveEntryType.Special -> "Special"
}

private fun archiveLocation(rawPath: String): ArchiveLocation {
    val path = rawPath.trim()
    return ArchiveLocation(
        archiveId = "archive-${path.hashCode().toUInt().toString(16)}",
        displayName = path.substringAfterLast('/').ifBlank { "Archive" },
        stagedPath = path,
    )
}
