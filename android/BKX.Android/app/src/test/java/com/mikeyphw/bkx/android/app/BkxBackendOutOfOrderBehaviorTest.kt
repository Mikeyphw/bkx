package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.backend.BackendSubmission
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.operations.InMemoryOperationStateStore
import com.mikeyphw.bkx.android.core.runs.InMemoryRunSummaryStore
import com.mikeyphw.bkx.android.core.settings.InMemorySettingsStore
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
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
class BkxBackendOutOfOrderBehaviorTest {
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
    fun newerReadinessRefreshWinsWhenOlderBackendProbeCompletesLast() = runTest(dispatcher) {
        val first = CompletableDeferred<BackendReadiness>()
        val second = CompletableDeferred<BackendReadiness>()
        val backend = DelayedBackendPort(first, second)
        val viewModel = BkxAppViewModel(
            backendPort = backend,
            runSummaryStore = InMemoryRunSummaryStore(),
            operationStateStore = InMemoryOperationStateStore(),
            settingsStore = InMemorySettingsStore(),
            requestBaseDirectoryPath = "/tmp/bkx-br36-readiness",
        )

        advanceUntilIdle()
        assertEquals(1, backend.readinessCalls)

        viewModel.onEvent(BkxHomeEvent.RefreshBackend)
        advanceUntilIdle()
        assertEquals(2, backend.readinessCalls)

        second.complete(
            BackendReadiness.Ready(
                backendVersion = "fresh",
                bridgeProtocolVersion = 1,
                capabilities = emptySet(),
            ),
        )
        advanceUntilIdle()
        assertEquals("fresh", (viewModel.state.value.readiness as BackendReadiness.Ready).backendVersion)

        first.complete(BackendReadiness.RootPermissionRequired)
        advanceUntilIdle()

        val finalReadiness = viewModel.state.value.readiness
        assertTrue(finalReadiness is BackendReadiness.Ready)
        assertEquals("fresh", (finalReadiness as BackendReadiness.Ready).backendVersion)
    }

    private class DelayedBackendPort(
        vararg responses: CompletableDeferred<BackendReadiness>,
    ) : BackendPort {
        private val pending = ArrayDeque(responses.toList())
        var readinessCalls = 0
            private set

        override suspend fun checkReadiness(): BackendReadiness {
            readinessCalls += 1
            return checkNotNull(if (pending.isEmpty()) null else pending.removeFirst()) { "Unexpected readiness probe" }.await()
        }

        override suspend fun submit(request: BridgeRequestEnvelope, files: BridgeRequestFiles): BackendSubmission =
            error("submit is not used by this BR36 fake backend")

        override suspend fun progress(requestId: String): BridgeProgressEnvelope? = null

        override suspend fun cancel(requestId: String, cancelFilePath: String): BackendSubmission =
            error("cancel is not used by this BR36 fake backend")

        override suspend fun readFinalResult(rawResult: String): BridgeResultEnvelope =
            error("final-result parsing is not used by this BR36 fake backend")
    }
}
