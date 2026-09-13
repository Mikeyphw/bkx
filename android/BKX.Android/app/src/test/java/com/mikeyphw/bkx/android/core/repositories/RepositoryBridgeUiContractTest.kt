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


class RepositoryBridgeBr25ContractTest {
    private val repository = RepositoryDefinition(
        schemaVersion = 1,
        repositoryId = "phone",
        name = "Phone",
        engine = "restic",
        backend = "local",
        location = "/tmp/restic",
        managedByBkx = false,
        createdAt = "2026-09-10T10:00:00Z",
        updatedAt = "2026-09-10T10:00:00Z",
    )

    @Test
    fun repositoryRemoveCarriesExplicitDanglingConfirmation() {
        val request = RepositoryRequestBuilder.remove("remove-1", "phone", confirmDanglingDependents = true)
        assertEquals("repositories.remove", request.operation)
        assertTrue(request.payload.toString().contains("\"confirm\":true"))
    }

    @Test
    fun snapshotRequestsCarryBoundedResultLimits() {
        val list = RepositoryRequestBuilder.snapshots("list-1", repository, limit = 100)
        val diff = RepositoryRequestBuilder.snapshotDiff("diff-1", repository, "latest~1", "latest", maxEntries = 500)
        assertTrue(list.payload.toString().contains("\"limit\":100"))
        assertTrue(diff.payload.toString().contains("\"max_entries\":500"))
    }

    @Test
    fun credentialRefsRejectCollisionProneShapes() {
        listOf("Phone-Repo", "phone_repo", "phone.repo").forEach { bad ->
            var failed = false
            try {
                RepositorySpecs.local(repositoryId = "repo-${bad.lowercase().replace("_", "-").replace(".", "-")}", name = "Bad", location = "/tmp/repo", credentialRef = bad)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue("expected $bad to fail", failed)
        }
    }
}
