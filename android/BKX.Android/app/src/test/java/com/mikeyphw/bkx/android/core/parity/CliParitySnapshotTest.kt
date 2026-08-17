package com.mikeyphw.bkx.android.core.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliParitySnapshotTest {
    @Test
    fun representativeBridgeRequestsKeepCliJsonSemanticOperations() {
        val snapshots = CliParitySnapshots.representativeAndroidRequests().map(CliParitySnapshots::semanticSnapshot)

        assertEquals(listOf("backup.plan", "restore.preview", "transfer.plan"), snapshots.map { it.operation })
        assertTrue(snapshots.all { it.outputMode == "json" })
        assertTrue(snapshots.first { it.operation == "backup.plan" }.hasSemanticPayloadKey("source"))
        assertTrue(snapshots.first { it.operation == "restore.preview" }.hasSemanticPayloadKey("archive"))
        assertTrue(snapshots.first { it.operation == "transfer.plan" }.hasSemanticPayloadKey("mode"))
    }

    @Test
    fun representativeBridgeResultsKeepCliJsonSemanticOperations() {
        val snapshots = CliParitySnapshots.representativeCliJsonResults().map(CliParitySnapshots::semanticResultSnapshot)

        assertEquals(listOf("backup.plan", "restore.preview", "transfer.plan"), snapshots.map { it.operation })
        assertTrue(snapshots.all { it.ok })
        assertTrue(snapshots.all { it.requestId.startsWith("parity-") })
        assertTrue(snapshots.none { it.verificationStatus.isBlank() })
    }
}
