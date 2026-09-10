package com.mikeyphw.bkx.android.core.jobs

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedJobBridgeUiContractTest {
    @Test
    fun codecRoundTripsSharedGoStoreArtifact() {
        val job = SavedJob(
            jobId = "phone-backup",
            name = "Phone backup",
            revision = 3,
            operation = OperationSpecV2(
                operation = "backup.snapshot",
                parameters = buildJsonObject { put("verify", true) },
                repositoryRef = "phone-drive",
                credentialRefs = listOf(SecretReference("phone-restic-password")),
            ),
            createdAt = "2026-09-10T10:00:00Z",
            updatedAt = "2026-09-10T11:00:00Z",
        )
        val artifact = BridgeArtifact(
            label = "saved_job",
            path = job.jobId,
            metadata = mapOf("saved_job_json" to BridgeJson.json.encodeToString(job)),
        )

        val decoded = SavedJobBridgeCodec.decodeArtifact(artifact)!!
        assertEquals(job, decoded)
    }

    @Test
    fun jobRunUsesReferenceAndBackendHintWithoutSecrets() {
        val request = SavedJobRequestBuilder.run("req-1", "phone-backup", "managed_google_drive")
        val payload = request.payload.toString()
        assertEquals("jobs.run", request.operation)
        assertTrue(payload.contains("phone-backup"))
        assertTrue(payload.contains("managed_google_drive"))
        assertFalse(payload.contains("password"))
        assertFalse(payload.contains("token"))
    }

    @Test
    fun updateCarriesOptimisticRevision() {
        val draft = SavedJobDraft(
            jobId = "phone-backup",
            name = "Phone backup",
            operation = OperationSpecV2(operation = "backup.snapshot", repositoryRef = "phone-drive"),
        )
        val request = SavedJobRequestBuilder.update("req-2", "phone-backup", 7, draft)
        assertTrue(request.payload.toString().contains("\"expected_revision\":7"))
    }
}
