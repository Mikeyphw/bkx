package com.mikeyphw.bkx.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxAppViewModel
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BackendToolState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.archives.ArchiveInfoSummary
import com.mikeyphw.bkx.android.core.archives.ArchiveListItem
import com.mikeyphw.bkx.android.core.archives.ArchiveReadOnlyUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveVerificationUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveWorkflowUiState
import com.mikeyphw.bkx.android.core.backup.BackupPlanningUiState
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.restore.RestoreOutputMode
import com.mikeyphw.bkx.android.core.restore.RestoreUiState
import com.mikeyphw.bkx.android.core.settings.BkxSettings
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferResultUiState
import com.mikeyphw.bkx.android.core.transfer.TransferUiState

@Composable
fun BkxCompanionApp(viewModel: BkxAppViewModel) {
    val state by viewModel.state.collectAsState()
    BkxHomeScreen(
        state = state,
        onEvent = viewModel::onEvent,
    )
}

@Composable
fun BkxHomeScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Thin Android interface over the BKX engine contract.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NavigationGraphTabs(state, onEvent)
            when (state.selectedDestination) {
                BkxDestination.Home -> HomeRecentRunsScreen(state, onEvent)
                BkxDestination.Backup -> BackupPlanningScreen(state.backupPlanning)
                BkxDestination.Archives -> ArchiveReadOnlyScreen(state.archiveReadOnly)
                BkxDestination.Restore -> RestoreSafetyScreen(state.restore)
                BkxDestination.Transfers -> TransferSafetyScreen(state.transfer)
                BkxDestination.Tools -> ToolsBackendScreen(state.backendToolState, state.isRefreshing, onEvent)
                BkxDestination.Settings -> SettingsScreen(state.settings)
            }
        }
    }
}

@Composable
private fun NavigationGraphTabs(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.destinations.chunked(2).forEach { rowDestinations ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowDestinations.forEach { destination ->
                    val selected = destination == state.selectedDestination
                    if (selected) {
                        Button(onClick = { onEvent(BkxHomeEvent.SelectDestination(destination)) }) {
                            Text(destination.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onEvent(BkxHomeEvent.SelectDestination(destination)) }) {
                            Text(destination.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecentRunsScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BackendReadinessCard(state.readiness)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onEvent(BkxHomeEvent.RefreshBackend) },
            enabled = !state.isRefreshing,
        ) {
            Text(if (state.isRefreshing) "Checking…" else "Check Termux backend")
        }
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshRuns) }) {
            Text("Refresh runs")
        }
    }
    RecentRunsCard(state.recentRuns, state.screenStatuses[BkxDestination.Home] ?: ScreenStatus.Loading)
}

@Composable
private fun RecentRunsCard(runs: List<RunSummary>, status: ScreenStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Recent runs", style = MaterialTheme.typography.titleMedium)
            if (runs.isEmpty()) {
                InitialStatusText(status)
            } else {
                runs.forEach { run ->
                    Text(
                        text = "${run.operation}: ${run.status} • ${run.durationMillis ?: 0} ms • verification ${run.verification}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    run.artifacts.forEach { artifact ->
                        Text(text = "Artifact: ${artifact.displayName} (${artifact.label})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}


@Composable
private fun BackupPlanningScreen(backup: BackupPlanningUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Backup planning", style = MaterialTheme.typography.titleMedium)
            Text(text = "Android creates backup.plan previews first; backup.create stays disabled until a matching preview exists.")
            backup.draft.source?.let { Text(text = "Source: ${it.label} → staged") } ?: Text("Source: not selected")
            backup.draft.destination?.let { Text(text = "Destination: ${it.label}") } ?: Text("Destination: not selected")
            Text(text = "Compression: ${backup.draft.compression.protocolValue}")
            Text(text = "Split: ${backup.draft.split.chunkSizeBytes?.toString() ?: "disabled"}")
            Text(text = "Verify after create: ${backup.draft.verification.verifyAfterCreate}")
            if (backup.validationIssues.isEmpty()) {
                Text("Plan request is available.")
            } else {
                backup.validationIssues.forEach { issue -> Text(text = "Blocked: ${issue.message}") }
            }
            backup.preview?.let { preview ->
                Text(text = "Preview token: ${preview.planToken}")
                preview.warnings.forEach { warning -> Text(text = "${warning.severity}: ${warning.message}") }
                preview.verificationExpectations.forEach { expectation -> Text(text = "Verify: ${expectation.message}") }
            }
            Text(text = if (backup.canCreateBackup) "Create backup is enabled from this preview." else "Create backup is disabled until a valid preview exists.")
        }
    }
}

@Composable
private fun ArchiveReadOnlyScreen(archives: ArchiveReadOnlyUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Archive tools", style = MaterialTheme.typography.titleMedium)
            Text(text = "Read-only workflows: ${archives.availableWorkflows.joinToString { it.operation }}")
            ArchiveListStatePanel(archives.listState)
            archives.selectedArchive?.let { archive ->
                Text(text = "Archive: ${archive.displayName}")
            } ?: Text("Archive: not selected")
            ArchiveInfoStatePanel(archives.infoState)
            archives.browsePage?.let { page ->
                Text(text = "Browse entries: ${page.entries.size}")
                Text(text = if (archives.hasMoreBrowsePages) "More pages are available." else "No more pages loaded.")
                Text(text = if (archives.isLargeArchive) "Large archive mode." else "Standard archive mode.")
            } ?: Text("Browse page: not loaded")
            when (val verification = archives.verification) {
                ArchiveVerificationUiState.NotStarted -> Text("Verification: not started")
                is ArchiveVerificationUiState.Passed -> Text("Verification passed: ${verification.summary}")
                is ArchiveVerificationUiState.Failed -> {
                    Text("Verification failed: ${verification.summary}")
                    verification.errors.forEach { error -> Text(text = "${error.code}: ${error.message}") }
                }
            }
        }
    }
}

@Composable
private fun ArchiveListStatePanel(listState: ArchiveWorkflowUiState<List<ArchiveListItem>>) {
    when (listState) {
        ArchiveWorkflowUiState.Idle -> Text("Archive list: not loaded")
        ArchiveWorkflowUiState.Loading -> Text("Archive list: loading…")
        is ArchiveWorkflowUiState.Content -> {
            Text(text = "Archive list: ${listState.value.size} archive(s)")
            listState.value.take(3).forEach { item ->
                Text(text = "• ${item.archive.displayName} (${item.sizeBytes ?: 0} bytes)", style = MaterialTheme.typography.bodySmall)
            }
        }
        is ArchiveWorkflowUiState.Failed -> Text("Archive list failed: ${listState.summary}")
    }
}

@Composable
private fun ArchiveInfoStatePanel(infoState: ArchiveWorkflowUiState<ArchiveInfoSummary>) {
    when (infoState) {
        ArchiveWorkflowUiState.Idle -> Text("Archive info: not loaded")
        ArchiveWorkflowUiState.Loading -> Text("Archive info: loading…")
        is ArchiveWorkflowUiState.Content -> {
            val info = infoState.value
            val entryLabel = if (info.entryCount == 1L) "entry" else "entries"
            Text(text = "Archive info: ${info.entryCount ?: 0} $entryLabel")
            info.formatVersion?.let { Text(text = "Format: $it", style = MaterialTheme.typography.bodySmall) }
            info.manifestHash?.let { Text(text = "Manifest: $it", style = MaterialTheme.typography.bodySmall) }
            info.warnings.forEach { warning -> Text(text = "Info warning: $warning", style = MaterialTheme.typography.bodySmall) }
        }
        is ArchiveWorkflowUiState.Failed -> Text("Archive info failed: ${infoState.summary}")
    }
}


@Composable
private fun RestoreSafetyScreen(restore: RestoreUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Restore safety", style = MaterialTheme.typography.titleMedium)
            Text(text = "Restore always previews first. Extraction is disabled until the preview still matches the archive, destination, subset, and output mode.")
            restore.draft.archive?.let { archive -> Text(text = "Archive: ${archive.displayName}") } ?: Text("Archive: not selected")
            restore.draft.destination?.let { destination -> Text(text = "Destination: ${destination.label}") } ?: Text("Destination: fresh directory default")
            Text(text = "Output mode: ${restore.draft.outputMode.protocolValue}")
            Text(text = "Subset: ${restore.draft.subset.selectedPaths.ifEmpty { listOf("full restore") }.joinToString()}")
            Text(text = "Fresh destination default: ${restore.isFreshDestinationDefault}")
            if (restore.validationIssues.isEmpty()) {
                Text("Restore preview request is available.")
            } else {
                restore.validationIssues.forEach { issue -> Text(text = "Blocked: ${issue.message}") }
            }
            restore.preview?.let { preview ->
                Text(text = "Preview token: ${preview.planToken}")
                Text(text = if (preview.fullRestore) "Preview covers a full restore." else "Preview covers ${preview.selectedPathCount} selected path(s).")
                Text(text = "Overwrite count: ${preview.overwriteSummary.totalOverwrites}")
                preview.overwriteSummary.paths.forEach { path -> Text(text = "Overwrite: $path") }
                preview.pathSafetySummary.unsafePaths.forEach { path -> Text(text = "Unsafe path: $path") }
                preview.pathSafetySummary.blockedLinks.forEach { link -> Text(text = "Blocked link: $link") }
                preview.pathSafetySummary.blockedSpecialFiles.forEach { special -> Text(text = "Blocked special file: $special") }
                preview.verificationHash?.let { hash -> Text(text = "Verification hash: $hash") }
                preview.warnings.forEach { warning -> Text(text = "Preview: $warning") }
                Text(text = if (preview.requiresDestructiveConfirmation) "Destructive restore requires typed confirmation." else "No destructive overwrite confirmation required by preview.")
            } ?: Text("Preview: not loaded")
            Text(text = if (restore.canExtract) "Extract is enabled from the matching preview." else "Extract is disabled until preview and confirmation gates pass.")
            if (restore.draft.outputMode == RestoreOutputMode.ExistingDirectory) {
                Text(text = "Existing directory mode never bypasses the preview/confirmation gate.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransferSafetyScreen(transfer: TransferUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Transfer safety", style = MaterialTheme.typography.titleMedium)
            Text(text = "Transfer runs only after a topology preview. Android renders mapping, collisions, file policies, and verification truthfully.")
            Text(text = "Mode: ${transfer.draft.mode.protocolValue}")
            Text(text = "Sources: ${transfer.draft.sources.map { it.label }.ifEmpty { listOf("not selected") }.joinToString()}")
            transfer.draft.destination?.let { destination -> Text(text = "Destination: ${destination.label}") } ?: Text("Destination: not selected")
            Text(text = "Verification: ${transfer.draft.verifyAfterTransfer}")
            if (transfer.draft.mode == TransferMode.Sync) {
                Text(text = "Sync delete extraneous: ${transfer.draft.deleteExtraneousOnSync}")
            }
            Text(text = "Symlink policy: ${transfer.draft.symlinkPolicy.protocolValue}")
            Text(text = "Special-file policy: ${transfer.draft.specialFilePolicy.protocolValue}")
            if (transfer.validationIssues.isEmpty()) {
                Text("Transfer preview request is available.")
            } else {
                transfer.validationIssues.forEach { issue -> Text(text = "Blocked: ${issue.message}") }
            }
            transfer.preview?.let { preview ->
                Text(text = "Topology: ${preview.topologyLabel}")
                Text(text = "Mappings: ${preview.mappings.size}")
                preview.mappings.take(4).forEach { mapping -> Text(text = "${mapping.action}: ${mapping.relativePath}") }
                preview.collisionSummary.collisions.forEach { collision -> Text(text = "Collision: $collision") }
                preview.collisionSummary.overlaps.forEach { overlap -> Text(text = "Overlap: $overlap") }
                Text(text = "Verify plan: ${preview.verificationPlan.enabled}")
                preview.verificationPlan.expectedEvidence.forEach { evidence -> Text(text = "Evidence: $evidence") }
                preview.warnings.forEach { warning -> Text(text = "Preview: $warning") }
            } ?: Text("Preview: not loaded")
            when (val result = transfer.result) {
                TransferResultUiState.NotStarted -> Text("Transfer result: not started")
                is TransferResultUiState.Succeeded -> Text("Transfer succeeded: ${result.summary}")
                is TransferResultUiState.PartialFailure -> {
                    Text("Transfer partial failure: ${result.summary}")
                    result.errors.forEach { error -> Text(text = "${error.code}: ${error.message}") }
                }
                is TransferResultUiState.VerificationFailed -> {
                    Text("Transfer verification failed: ${result.summary}")
                    result.errors.forEach { error -> Text(text = "${error.code}: ${error.message}") }
                }
                is TransferResultUiState.Failed -> Text("Transfer failed: ${result.summary}")
                is TransferResultUiState.Cancelled -> Text("Transfer cancelled: ${result.summary}")
            }
            Text(text = if (transfer.canRun) "Run is enabled from this topology preview." else "Run is disabled until topology preview passes.")
        }
    }
}

@Composable
private fun ToolsBackendScreen(
    tools: BackendToolState,
    isRefreshing: Boolean,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BackendReadinessCard(tools.readiness)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Backend tools", style = MaterialTheme.typography.titleMedium)
            Text(text = "Wrapper: ${tools.wrapperPath}")
            Text(text = "Bridge protocol: ${tools.protocolVersion}")
            Text(text = "BKX version: ${tools.bkxVersion ?: "not verified"}")
            Text(text = "Capabilities: ${tools.capabilities.ifEmpty { listOf("not verified") }.joinToString()}")
            Button(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }, enabled = !isRefreshing) {
                Text(if (isRefreshing) "Checking…" else "Re-check backend")
            }
        }
    }
    tools.remediationActions.forEach { action ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = action.title, style = MaterialTheme.typography.titleMedium)
                action.steps.forEach { step -> Text(text = "• $step", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: BkxSettings) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Settings", style = MaterialTheme.typography.titleMedium)
            Text(text = "Backend: ${settings.backend}")
            Text(text = "Staging: ${settings.stagingLocationLabel}")
            Text(text = "Log privacy: ${settings.logPrivacyMode}")
            Text(text = "Progress: ${settings.progressPreference}")
            Text(
                text = "SAF grants are kept deliberate; content URIs are staged before BKX receives filesystem paths.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InitialScreenPanel(destination: BkxDestination, status: ScreenStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = destination.label, style = MaterialTheme.typography.titleMedium)
            InitialStatusText(status)
        }
    }
}

@Composable
private fun InitialStatusText(status: ScreenStatus) {
    when (status) {
        ScreenStatus.Loading -> Text("Loading…")
        is ScreenStatus.Empty -> Text(status.message)
        is ScreenStatus.Error -> Text("${status.title}: ${status.message}")
        ScreenStatus.Content -> Text("Ready.")
    }
}

@Composable
private fun BackendReadinessCard(readiness: BackendReadiness) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = readiness.title, style = MaterialTheme.typography.titleMedium)
            Text(text = readiness.message, style = MaterialTheme.typography.bodyMedium)
            readiness.remediationSteps.forEach { step ->
                Text(text = "• $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
