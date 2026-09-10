package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryBridgeUiContractTest {
    @Test
    fun codecDecodesRepositoryArtifactWithoutCredentialMaterial() {
        val policy = RetentionPolicy(keepLast = 10, keepDaily = 7)
        val artifact = BridgeArtifact(
            label = "repository",
            path = "rclone://bkx-google-drive/BKX/restic/phone",
            metadata = mapOf(
                "schema_version" to "1",
                "repository_id" to "phone-drive",
                "name" to "Phone Drive",
                "engine" to "restic",
                "backend" to "managed_google_drive",
                "managed_by_bkx" to "true",
                "credential_ref" to "phone-restic-password",
                "created_at" to "2026-09-10T10:00:00Z",
                "updated_at" to "2026-09-10T11:00:00Z",
                "retention" to BridgeJson.json.encodeToString(policy),
            ),
        )

        val repository = RepositoryBridgeCodec.decodeArtifact(artifact)!!
        assertEquals("phone-drive", repository.repositoryId)
        assertEquals(policy, repository.retention)
        assertEquals("phone-restic-password", repository.credentialRef)
    }

    @Test
    fun destructiveMaintenanceStaysExplicit() {
        val repository = RepositoryDefinition(
            schemaVersion = 1,
            repositoryId = "local",
            name = "Local",
            engine = "restic",
            backend = "local",
            location = "/tmp/restic",
            managedByBkx = false,
            createdAt = "2026-09-10T10:00:00Z",
            updatedAt = "2026-09-10T10:00:00Z",
            retention = RetentionPolicy(keepLast = 10),
        )
        val preview = RepositoryRequestBuilder.prune("preview", repository)
        val apply = RepositoryRequestBuilder.prune("apply", repository, dryRun = false, confirm = true)
        assertTrue(preview.payload.toString().contains("\"dry_run\":true"))
        assertTrue(preview.payload.toString().contains("\"confirm\":false"))
        assertTrue(apply.payload.toString().contains("\"dry_run\":false"))
        assertTrue(apply.payload.toString().contains("\"confirm\":true"))
        assertFalse(apply.payload.toString().contains("restic-password"))
    }
}
