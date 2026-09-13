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
                filesDone = 4,
                filesTotal = 8,
                bytesDone = 4096,
                bytesTotal = 8192,
            ),
            observedAtEpochMillis = 150,
        )

        assertEquals("scanning", next.activeFor("req-1")?.lastPhase)
        assertEquals(0.5, next.activeFor("req-1")?.progress)
        assertEquals(4L, next.activeFor("req-1")?.filesDone)
        assertEquals(8L, next.activeFor("req-1")?.filesTotal)
        assertEquals(4096L, next.activeFor("req-1")?.bytesDone)
        assertEquals(8192L, next.activeFor("req-1")?.bytesTotal)
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
    fun terminalOperationsAreNotResurrectedByLateProgress() {
        val started = tracker.trackStarted(OperationRuntimeState(), "req-terminal", "backup.create", 100)
        val completed = tracker.finish(
            state = started,
            result = BridgeResultEnvelope(
                protocolVersion = 1,
                requestId = "req-terminal",
                status = BridgeResultStatus.Success,
                summary = "done",
            ),
            nowEpochMillis = 200,
        )

        val afterLateProgress = tracker.consume(
            state = completed,
            envelope = BridgeProgressEnvelope(requestId = "req-terminal", seq = 1, phase = "late", message = "late progress"),
            observedAtEpochMillis = 300,
        )

        assertEquals(ActiveOperationStatus.Success, afterLateProgress.activeFor("req-terminal")?.status)
        assertEquals(1.0, afterLateProgress.activeFor("req-terminal")?.progress)
        assertEquals(null, afterLateProgress.progressEvents["req-terminal"])
    }

    @Test
    fun cancellingOperationsStayCancellingWhenProgressArrives() {
        val started = tracker.trackStarted(OperationRuntimeState(), "req-cancel-sticky", "backup.snapshot", 100)
        val cancelling = tracker.markCancelling(started, "req-cancel-sticky", "cancel-req-cancel-sticky", 150)
        val progressed = tracker.consume(
            state = cancelling,
            envelope = BridgeProgressEnvelope(
                requestId = "req-cancel-sticky",
                seq = 1,
                phase = "copying",
                message = "copying data",
                progress = 0.25,
            ),
            observedAtEpochMillis = 160,
        )

        assertEquals(ActiveOperationStatus.Cancelling, progressed.activeFor("req-cancel-sticky")?.status)
        assertEquals(0.25, progressed.activeFor("req-cancel-sticky")?.progress)
        assertTrue(progressed.hasActiveWork)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRequestIdCannotBeReboundToDifferentPayload() {
        val started = tracker.trackStarted(
            state = OperationRuntimeState(),
            requestId = "req-dup",
            operation = "backup.create",
            startedAtEpochMillis = 100,
            requestFingerprint = "payload-a",
        )
        tracker.trackStarted(
            state = started,
            requestId = "req-dup",
            operation = "backup.create",
            startedAtEpochMillis = 200,
            requestFingerprint = "payload-b",
        )
    }

    @Test
    fun duplicateRequestIdWithSamePayloadDoesNotResetActiveOperation() {
        val started = tracker.trackStarted(
            state = OperationRuntimeState(),
            requestId = "req-same",
            operation = "backup.create",
            startedAtEpochMillis = 100,
            requestFingerprint = "payload-a",
        )
        val duplicate = tracker.trackStarted(
            state = started,
            requestId = "req-same",
            operation = "backup.create",
            startedAtEpochMillis = 999,
            requestFingerprint = "payload-a",
        )

        assertEquals(100L, duplicate.activeFor("req-same")?.startedAtEpochMillis)
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
    @Test
    fun focusedNotificationRequestIdIsTrackedForReconnect() {
        val tracker = OperationProgressTracker()
        val started = tracker.trackStarted(
            state = OperationRuntimeState(),
            requestId = "req-focus-1",
            operation = "backup.create",
            startedAtEpochMillis = 1_786_000_000_000,
        )

        val focused = tracker.focus(started, "req-focus-1")

        assertEquals("req-focus-1", focused.focusedRequestId)
        assertEquals("backup.create", focused.focusedOperation?.operation)
    }

    @Test
    fun interruptedWorkAndPersistenceBoundsAreExplicit() {
        val started = tracker.trackStarted(OperationRuntimeState(), "req-interrupted", "backup.create", 100)
        val interrupted = tracker.interrupt(started, "req-interrupted", OperationFailureMapper.interrupted(), 200)
        assertEquals(ActiveOperationStatus.Interrupted, interrupted.activeFor("req-interrupted")?.status)
        assertEquals(OperationFailureKind.Interrupted, interrupted.failureStates["req-interrupted"]?.kind)

        var state = OperationRuntimeState()
        repeat(80) { index ->
            state = tracker.trackStarted(state, "bounded-$index", "backup.create", index.toLong())
        }
        assertTrue(state.bounded(maxOperations = 10).activeOperations.size <= 10)
    }

}
