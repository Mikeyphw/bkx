package com.mikeyphw.bkx.android.core.operations

import com.mikeyphw.bkx.android.core.bridge.BridgeProtocolException
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationProgressPollingTest {
    private val poller = OperationProgressPoller(OperationProgressTracker())

    @Test
    fun parsesNdjsonProgressAndConsumesItByExpectedRequestId() {
        val started = OperationProgressTracker().trackStarted(
            state = OperationRuntimeState(),
            requestId = "req-1",
            operation = "backup.create",
            startedAtEpochMillis = 100,
            cancelFilePath = "/tmp/bkx/req-1/cancel.json",
        )
        val next = poller.consumePolledProgress(
            state = started,
            rawProgress = """
                {"protocol_version":1,"request_id":"req-1","seq":1,"phase":"scan","message":"Scanning","progress":0.1}
                {"protocol_version":1,"request_id":"req-1","seq":2,"phase":"write","message":"Writing","progress":0.8}
            """.trimIndent(),
            expectedRequestId = "req-1",
            observedAtEpochMillis = 200,
        )

        assertEquals("write", next.activeFor("req-1")?.lastPhase)
        assertEquals(2, next.progressEvents["req-1"]?.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProgressForDifferentRequestId() {
        poller.parseProgressEvents(
            rawProgress = """{"protocol_version":1,"request_id":"other","seq":1,"phase":"x","message":"x"}""",
            expectedRequestId = "req-1",
            observedAtEpochMillis = 200,
        )
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsMalformedProgressJson() {
        poller.parseProgressEvents(
            rawProgress = "{" ,
            expectedRequestId = "req-1",
            observedAtEpochMillis = 200,
        )
    }
}
