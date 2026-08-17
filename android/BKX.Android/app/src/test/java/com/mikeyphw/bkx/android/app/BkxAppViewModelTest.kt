package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.core.archives.ArchiveBrowsePage
import com.mikeyphw.bkx.android.core.archives.ArchiveBrowsePageRequest
import com.mikeyphw.bkx.android.core.archives.ArchiveEntrySummary
import com.mikeyphw.bkx.android.core.archives.ArchiveEntryType
import com.mikeyphw.bkx.android.core.archives.ArchiveInfoSummary
import com.mikeyphw.bkx.android.core.archives.ArchiveListItem
import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.archives.ArchiveVerificationUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveWorkflowUiState
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupPlanPreview
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.backend.FakeBackendPort
import com.mikeyphw.bkx.android.core.runs.InMemoryRunSummaryStore
import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunStatus
import com.mikeyphw.bkx.android.core.restore.RestoreDraft
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestorePreviewSummary
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferMappingAction
import com.mikeyphw.bkx.android.core.transfer.TransferMappingSummary
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferPreviewSummary
import com.mikeyphw.bkx.android.core.transfer.TransferResultUiState
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import com.mikeyphw.bkx.android.core.transfer.TransferCollisionSummary
import com.mikeyphw.bkx.android.core.transfer.TransferVerificationPlan
import com.mikeyphw.bkx.android.core.runs.VerificationState
import com.mikeyphw.bkx.android.core.settings.InMemorySettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BkxAppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshBackendUpdatesStructuredReadinessState() = runTest(dispatcher) {
        val viewModel = viewModel(backend = FakeBackendPort(BackendReadiness.RunCommandPermissionMissing))

        advanceUntilIdle()

        assertEquals(BackendReadiness.RunCommandPermissionMissing, viewModel.state.value.readiness)
        assertEquals(false, viewModel.state.value.isRefreshing)
    }

    @Test
    fun loadsRecentRunsAndSwitchesDestinationsThroughTypedEvents() = runTest(dispatcher) {
        val runs = InMemoryRunSummaryStore(
            listOf(
                RunSummary(
                    requestId = "req-1",
                    operation = "backup.plan",
                    status = RunStatus.Success,
                    startedAtEpochMillis = 100,
                    durationMillis = 25,
                    verification = VerificationState.Passed,
                    summary = "planned safely",
                ),
            ),
        )
        val viewModel = viewModel(runSummaryStore = runs)

        advanceUntilIdle()
        viewModel.onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Tools))

        assertEquals(BkxDestination.Tools, viewModel.state.value.selectedDestination)
        assertEquals("req-1", viewModel.state.value.recentRuns.single().requestId)
    }


    @Test
    fun backupAndArchiveEventsUpdateTypedScreenState() = runTest(dispatcher) {
        val viewModel = viewModel()
        val draft = BackupPlanDraft(
            source = BackupSourceSelection("src", "Source", "/shared/bkx/source"),
            destination = BackupDestinationSelection("dst", "Destination", "/shared/bkx/destination"),
        )
        val preview = BackupPlanPreview(
            requestId = "req-plan",
            planToken = "token",
            draftFingerprint = draft.fingerprint(),
        )
        val archive = ArchiveLocation("archive", "backup.bkx", "/shared/bkx/backup.bkx")
        val browsePage = ArchiveBrowsePage(
            request = ArchiveBrowsePageRequest(archive = archive, pageSize = 1),
            entries = listOf(ArchiveEntrySummary("manifest.json", ArchiveEntryType.File)),
            nextPageToken = "next",
        )

        advanceUntilIdle()
        viewModel.onEvent(BkxHomeEvent.UpdateBackupDraft(draft))
        viewModel.onEvent(BkxHomeEvent.ApplyBackupPlanPreview(preview))
        viewModel.onEvent(BkxHomeEvent.SelectArchive(archive))
        viewModel.onEvent(BkxHomeEvent.ApplyArchiveList(listOf(ArchiveListItem(archive = archive, sizeBytes = 128))))
        viewModel.onEvent(BkxHomeEvent.ApplyArchiveInfo(ArchiveInfoSummary(archive = archive, entryCount = 1)))
        viewModel.onEvent(BkxHomeEvent.ApplyArchiveBrowsePage(browsePage))
        viewModel.onEvent(
            BkxHomeEvent.ApplyArchiveVerification(
                BridgeResultEnvelope(
                    protocolVersion = 1,
                    requestId = "req-verify",
                    status = BridgeResultStatus.Failure,
                    summary = "corrupt",
                    errors = listOf(BridgeError("archive_corrupt", "hash mismatch")),
                ),
            ),
        )

        assertEquals(true, viewModel.state.value.backupPlanning.canCreateBackup)
        assertEquals("backup.bkx", viewModel.state.value.archiveReadOnly.selectedArchive?.displayName)
        assertTrue(viewModel.state.value.archiveReadOnly.listState is ArchiveWorkflowUiState.Content)
        assertTrue(viewModel.state.value.archiveReadOnly.infoState is ArchiveWorkflowUiState.Content)
        assertEquals(true, viewModel.state.value.archiveReadOnly.hasMoreBrowsePages)
        assertTrue(viewModel.state.value.archiveReadOnly.verification is ArchiveVerificationUiState.Failed)
    }

    @Test
    fun restoreAndTransferEventsUpdateTypedSafetyState() = runTest(dispatcher) {
        val viewModel = viewModel()
        val archive = ArchiveLocation("archive", "backup.bkx", "/shared/bkx/backup.bkx")
        val restoreDraft = RestoreDraft(
            archive = archive,
            destination = RestoreDestinationSelection("restore-dst", "Restore output", "/shared/bkx/restores"),
        )
        val restorePreview = RestorePreviewSummary(
            requestId = "restore-preview",
            draftFingerprint = restoreDraft.fingerprint(),
            planToken = "restore-plan",
            selectedPathCount = 0,
            fullRestore = true,
        )
        val transferDraft = TransferTopologyDraft(
            mode = TransferMode.Push,
            sources = listOf(TransferEndpoint("src", "Source", "/shared/bkx/backup.bkx")),
            destination = TransferEndpoint("dst", "Remote", "user@example:/backups", isRemote = true),
        )
        val transferPreview = TransferPreviewSummary(
            requestId = "transfer-preview",
            draftFingerprint = transferDraft.fingerprint(),
            topologyLabel = "push archive",
            mappings = listOf(TransferMappingSummary("Source", "Remote", "backup.bkx", TransferMappingAction.Create)),
            collisionSummary = TransferCollisionSummary(),
            verificationPlan = TransferVerificationPlan(enabled = true),
        )

        advanceUntilIdle()
        viewModel.onEvent(BkxHomeEvent.UpdateRestoreDraft(restoreDraft))
        viewModel.onEvent(BkxHomeEvent.ApplyRestorePreview(restorePreview))
        viewModel.onEvent(
            BkxHomeEvent.ConfirmRestoreExtraction(
                RestoreExtractionConfirmation(
                    draftFingerprint = restoreDraft.fingerprint(),
                    planToken = "restore-plan",
                    acknowledgedOverwriteCount = 0,
                    confirmationPhrase = RestoreExtractionConfirmation.CONFIRMATION_PHRASE,
                    confirmedAtEpochMillis = 1_786_000_000_000,
                ),
            ),
        )
        viewModel.onEvent(BkxHomeEvent.UpdateTransferDraft(transferDraft))
        viewModel.onEvent(BkxHomeEvent.ApplyTransferPreview(transferPreview))
        viewModel.onEvent(
            BkxHomeEvent.ApplyTransferResult(
                BridgeResultEnvelope(
                    protocolVersion = 1,
                    requestId = "transfer-run",
                    status = BridgeResultStatus.Failure,
                    summary = "partial",
                    errors = listOf(BridgeError("partial_failure", "one file failed")),
                ),
            ),
        )

        assertEquals(true, viewModel.state.value.restore.canExtract)
        assertEquals(true, viewModel.state.value.transfer.canRun)
        assertTrue(viewModel.state.value.transfer.result is TransferResultUiState.PartialFailure)
    }

    private fun viewModel(
        backend: FakeBackendPort = FakeBackendPort(),
        runSummaryStore: InMemoryRunSummaryStore = InMemoryRunSummaryStore(),
    ) = BkxAppViewModel(
        backendPort = backend,
        runSummaryStore = runSummaryStore,
        settingsStore = InMemorySettingsStore(),
    )
}
