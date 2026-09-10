package com.mikeyphw.bkx.android.core.jobs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedJobModelsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun savedJobUsesTheCrossClientV2WireContract() {
        val job = SavedJob(
            jobId = "phone-backup",
            name = "Phone backup",
            revision = 1,
            enabled = true,
            operation = OperationSpecV2(
                operation = "backup.create",
                parameters = buildJsonObject { put("verify", true) },
                repositoryRef = "drive-main",
                credentialRefs = listOf(SecretReference("restic-drive-main", "repository encryption")),
            ),
            createdAt = "2026-09-10T10:00:00Z",
            updatedAt = "2026-09-10T10:00:00Z",
        )

        val encoded = json.encodeToString(job)
        listOf(
            "schema_version", "job_id", "revision", "enabled", "operation", "parameters",
            "repository_ref", "credential_refs", "created_at", "updated_at",
        ).forEach { key -> assertTrue("missing $key in $encoded", encoded.contains("\"$key\"")) }
        assertFalse(encoded.contains("repository-password"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun operationSpecRejectsInlineSecretsRecursively() {
        OperationSpecV2(
            operation = "backup.create",
            parameters = buildJsonObject {
                put("auth", buildJsonObject { put("refresh_token", "do-not-store") })
            },
        )
    }

    @Test
    fun operationSpecAcceptsSecretReferences() {
        val spec = OperationSpecV2(
            operation = "backup.create",
            credentialRefs = listOf(SecretReference("drive-main", "cloud auth")),
        )
        assertEquals("drive-main", spec.credentialRefs.single().id)
    }
}
