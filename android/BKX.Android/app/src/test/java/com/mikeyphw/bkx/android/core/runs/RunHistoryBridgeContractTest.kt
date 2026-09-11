package com.mikeyphw.bkx.android.core.runs

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunHistoryBridgeContractTest {
    @Test
    fun canonicalGoRunArtifactDecodesForAndroidHistoryAndReplay() {
        val artifact = BridgeArtifact(
            label = "run",
            path = "embedded://runs/run-1",
            metadata = mapOf(
                "run_json" to """{"run_id":"run-1","operation":"backup","status":"success","phase":"succeeded","job_id":"phone-backup","job_revision":3,"started_at":"2026-09-10T10:00:00Z","completed_at":"2026-09-10T10:01:00Z","source_paths":["/ignored/by/compact/ui"]}""",
            ),
        )

        val run = RunHistoryBridgeCodec.decodeArtifact(artifact)!!
        assertEquals("run-1", run.requestId)
        assertEquals("phone-backup", run.jobId)
        assertEquals(3L, run.jobRevision)
        assertEquals("succeeded", run.phase)
        assertEquals(RunStatus.Success, run.status)
        assertEquals(60_000L, run.durationMillis)
    }

    @Test
    fun canonicalLegacyLocalIsoTimestampAlsoDecodes() {
        val artifact = BridgeArtifact(
            label = "run",
            path = "embedded://runs/run-local",
            metadata = mapOf(
                "run_json" to """{"run_id":"run-local","operation":"backup","status":"success","started_at":"2026-09-10T10:00:00.123456789","completed_at":"2026-09-10T10:00:01.123456789"}""",
            ),
        )
        val run = RunHistoryBridgeCodec.decodeArtifact(artifact)!!
        assertEquals(1_000L, run.durationMillis)
    }

    @Test
    fun interruptedCanonicalRunRemainsDistinctFromUserCancellation() {
        val artifact = BridgeArtifact(
            label = "run",
            path = "embedded://runs/run-2",
            metadata = mapOf(
                "run_json" to """{"run_id":"run-2","operation":"backup","status":"interrupted","phase":"interrupted","started_at":"2026-09-10T10:00:00Z","completed_at":"2026-09-10T10:00:05Z"}""",
            ),
        )
        assertEquals(RunStatus.Interrupted, RunHistoryBridgeCodec.decodeArtifact(artifact)!!.status)
    }

    @Test
    fun listRequestUsesSharedRunsService() {
        val request = RunHistoryRequestBuilder.list("history-1", 50)
        assertEquals("runs.list", request.operation)
        assertTrue(request.payload.toString().contains("50"))
    }
}
