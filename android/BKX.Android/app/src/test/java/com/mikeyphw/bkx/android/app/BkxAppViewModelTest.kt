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

    private fun viewModel(
        backend: FakeBackendPort = FakeBackendPort(),
        runSummaryStore: InMemoryRunSummaryStore = InMemoryRunSummaryStore(),
    ) = BkxAppViewModel(
        backendPort = backend,
        runSummaryStore = runSummaryStore,
        settingsStore = InMemorySettingsStore(),
    )
}
