package com.mikeyphw.bkx.android.app.runtime

import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRoot
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxApplicationRuntimeBehaviorTest {
    @Test
    fun concurrentAuthorizedIngressRunsColdStartBarrierExactlyOnceInOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val steps = RecordingStartupSteps()
        val runtime = BkxApplicationRuntime(steps, dispatcher)

        val first = async { runtime.awaitReadyForExecution(BkxStartupIngress.InteractiveUi) }
        val second = async { runtime.awaitReadyForExecution(BkxStartupIngress.AuthorizedAutomation) }
        testScheduler.advanceUntilIdle()

        assertTrue(first.await().phase is BkxApplicationRuntimePhase.Ready)
        assertTrue(second.await().phase is BkxApplicationRuntimePhase.Ready)
        assertEquals(
            listOf("resolve", "operations", "automation", "schedules"),
            steps.calls,
        )
        assertEquals(1, steps.operationRecoveries)
        assertEquals(1, steps.automationRecoveries)
        assertEquals(1, steps.scheduleReconciliations)
        runtime.shutdownForTests()
    }

    @Test
    fun startupFailureIsIsolatedAndLaterStepsStillRun() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val steps = RecordingStartupSteps(failAutomation = true)
        val runtime = BkxApplicationRuntime(steps, dispatcher)

        val state = runtime.awaitReadyForExecution(BkxStartupIngress.RecoveryBroadcast)

        val degraded = state.phase as BkxApplicationRuntimePhase.Degraded
        assertTrue(degraded.failures.single().startsWith("automation-recovery:"))
        assertEquals(
            listOf("resolve", "operations", "automation", "schedules"),
            steps.calls,
        )
        assertTrue(state.completedSteps.any { it.startsWith("control-plane:") })
        assertTrue("operation-recovery" in state.completedSteps)
        assertTrue("schedule-reconcile" in state.completedSteps)
        runtime.shutdownForTests()
    }

    private class RecordingStartupSteps(
        private val failAutomation: Boolean = false,
    ) : BkxStartupSteps {
        val calls = mutableListOf<String>()
        var operationRecoveries = 0
        var automationRecoveries = 0
        var scheduleReconciliations = 0

        override suspend fun resolveControlPlaneRoot(): BkxControlPlaneRoot {
            calls += "resolve"
            return BkxControlPlaneRoot(
                directory = File("/tmp/bkx-br36-control-plane"),
                source = BkxControlPlaneRoot.Source.PrivateFallback,
                sharedMarkerValid = false,
                reason = "test",
            )
        }

        override suspend fun recoverOperations() {
            calls += "operations"
            operationRecoveries += 1
        }

        override suspend fun recoverAutomation() {
            calls += "automation"
            automationRecoveries += 1
            if (failAutomation) error("automation failed")
        }

        override suspend fun reconcileSchedules() {
            calls += "schedules"
            scheduleReconciliations += 1
        }
    }
}
