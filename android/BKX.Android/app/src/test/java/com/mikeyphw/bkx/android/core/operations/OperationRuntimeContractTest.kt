package com.mikeyphw.bkx.android.core.operations

import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRuntimeContractTest {
    private val tracker = OperationProgressTracker(maxEventsPerRequest = 4)

    @Test
    fun progressEventsAreConsumedByRequestIdAndSequence() {
        val started = tracker.trackStarted(
            state = OperationRuntimeState(),
            requestId = "req-1",
            operation = "backup.create",
            startedAtEpochMillis = 100,
            cancelFilePath = "/tmp/bkx/req-1/cancel.json",
        )
        val next = tracker.consume(
            state = started,
            envelope = BridgeProgressEnvelope(
                requestId = "req-1",
                seq = 1,
                phase = "scanning",
                message = "Scanning sources",
                progress = 0.5,
            ),
            observedAtEpochMillis = 150,
        )

        assertEquals("scanning", next.activeFor("req-1")?.lastPhase)
        assertEquals(0.5, next.activeFor("req-1")?.progress)
        assertEquals(1, next.progressEvents["req-1"]?.size)
        assertEquals("/tmp/bkx/req-1/cancel.json", next.activeFor("req-1")?.cancelFilePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun progressForUnknownRequestIsRejected() {
        tracker.consume(
            state = OperationRuntimeState(),
            envelope = BridgeProgressEnvelope(requestId = "unknown", seq = 1, phase = "x", message = "x"),
            observedAtEpochMillis = 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOrOutOfOrderProgressIsRejected() {
        val one = tracker.trackStarted(OperationRuntimeState(), "req-1", "backup.create", 100)
        val two = tracker.consume(one, BridgeProgressEnvelope(requestId = "req-1", seq = 1, phase = "a", message = "a"), 101)
        tracker.consume(two, BridgeProgressEnvelope(requestId = "req-1", seq = 1, phase = "b", message = "b"), 102)
    }

    @Test
    fun reconnectSnapshotRestoresActiveOperationsAfterProcessRecreation() {
        val active = ActiveOperation(
            requestId = "req-1",
            operation = "transfer.run",
            status = ActiveOperationStatus.Running,
            startedAtEpochMillis = 100,
            lastSeenEpochMillis = 200,
            lastPhase = "copying",
            lastMessage = "Copying",
        )
        val reconnected = tracker.reconnect(OperationReconnectSnapshot(listOf(active), capturedAtEpochMillis = 250))

        assertEquals(active, reconnected.activeFor("req-1"))
        assertTrue(reconnected.hasActiveWork)
    }

    @Test
    fun cancellationAndTypedTimeoutFailureAreRepresented() {
        val started = tracker.trackStarted(OperationRuntimeState(), "req-1", "restore.extract", 100)
        val cancelling = tracker.markCancelling(started, requestId = "req-1", cancelRequestId = "cancel-req-1", nowEpochMillis = 150)
        val failed = tracker.fail(cancelling, "req-1", OperationFailureMapper.timeout(), nowEpochMillis = 300)

        assertEquals(ActiveOperationStatus.Failure, failed.activeFor("req-1")?.status)
        assertEquals(OperationFailureKind.BackendTimeout, failed.failureStates["req-1"]?.kind)
    }

    @Test
    fun cancellationFinalResultRendersTruthfully() {
        val started = tracker.trackStarted(OperationRuntimeState(), "req-1", "backup.create", 100)
        val cancelled = tracker.finish(
            state = started,
            result = BridgeResultEnvelope(
                protocolVersion = 1,
                requestId = "req-1",
                status = BridgeResultStatus.Cancelled,
                summary = "user requested cancellation",
            ),
            nowEpochMillis = 200,
        )

        assertEquals(ActiveOperationStatus.Cancelled, cancelled.activeFor("req-1")?.status)
    }
}
