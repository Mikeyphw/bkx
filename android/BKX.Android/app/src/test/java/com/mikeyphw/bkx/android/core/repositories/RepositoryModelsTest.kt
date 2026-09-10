package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryModelsTest {
    @Test
    fun managedDriveSpecStaysUnderReservedBkxRootAndStoresOnlyReferences() {
        val spec = RepositorySpecs.managedGoogleDrive(
            repositoryId = "phone-drive",
            name = "Phone Drive",
            credentialRef = "phone-restic-password",
        )
        assertEquals(RepositoryBackendKind.ManagedGoogleDrive.wireValue, spec.backend)
        assertTrue(spec.location.startsWith("${GoogleDriveRcloneConfig.rootUri}/"))
        val encoded = Json.encodeToString(spec)
        assertTrue(encoded.contains("\"credential_ref\":\"phone-restic-password\""))
        assertFalse(encoded.contains("access_token"))
        assertFalse(encoded.contains("password\":"))
    }

    @Test
    fun requestBuilderUsesNamedRepositoryWithoutEmbeddingLocationOrCredentials() {
        val repository = RepositoryDefinition(
            schemaVersion = 1,
            repositoryId = "phone-drive",
            name = "Phone Drive",
            engine = "restic",
            backend = RepositoryBackendKind.ManagedGoogleDrive.wireValue,
            location = "${GoogleDriveRcloneConfig.rootUri}/restic/phone-drive",
            credentialRef = "phone-restic-password",
            managedByBkx = true,
            createdAt = "2026-09-10T00:00:00Z",
            updatedAt = "2026-09-10T00:00:00Z",
        )
        val request = RepositoryRequestBuilder.snapshot(
            requestId = "req-1",
            repository = repository,
            sources = listOf("/storage/emulated/0/Documents"),
        )
        assertEquals("backup.snapshot", request.operation)
        val payload = request.payload.toString()
        assertTrue(payload.contains("repository_ref"))
        assertFalse(payload.contains("rclone://"))
        assertFalse(payload.contains("credential"))
        assertTrue(payload.contains("managed_google_drive"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun managedDriveFactoryRejectsEscapingChildPath() {
        RepositorySpecs.managedGoogleDrive("phone-drive", "Phone Drive", "../outside")
    }
}
