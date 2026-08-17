package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.backend.FakeBackendPort
import com.mikeyphw.bkx.android.core.runs.InMemoryRunSummaryStore
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

    private fun viewModel(
        backend: FakeBackendPort = FakeBackendPort(),
        runSummaryStore: InMemoryRunSummaryStore = InMemoryRunSummaryStore(),
    ) = BkxAppViewModel(
        backendPort = backend,
        runSummaryStore = runSummaryStore,
        settingsStore = InMemorySettingsStore(),
    )
}
