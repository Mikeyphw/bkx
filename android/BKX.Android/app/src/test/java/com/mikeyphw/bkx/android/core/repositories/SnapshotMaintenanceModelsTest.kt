package com.mikeyphw.bkx.android.core.repositories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SnapshotMaintenanceModelsTest {
    private val repository = RepositoryDefinition(
        schemaVersion = 1,
        repositoryId = "phone",
        name = "Phone",
        engine = "restic",
        backend = "local",
        location = "/tmp/repo",
        managedByBkx = false,
        createdAt = "2026-09-10T10:00:00Z",
        updatedAt = "2026-09-10T10:00:00Z",
    )

    @Test fun restoreDefaultsToNeverOverwrite() {
        val request = RepositoryRequestBuilder.snapshotRestore("r1", repository, "abc12345", "/tmp/restore")
        assertEquals("snapshots.restore", request.operation)
        assertEquals("\"never\"", request.payload["overwrite"].toString())
    }

    @Test fun forgetRefusesLatestSelector() {
        assertFailsWith<IllegalArgumentException> { RepositoryRequestBuilder.snapshotForget("r1", repository, "latest", confirm = true) }
    }

    @Test fun pruneDefaultsToDryRunAndUnconfirmed() {
        val request = RepositoryRequestBuilder.prune("r1", repository)
        assertEquals("true", request.payload["dry_run"].toString())
        assertFalse(request.payload["confirm"].toString().toBoolean())
    }

    @Test fun retentionCountsCannotBeZero() {
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(keepLast = 0) }
        assertEquals(7, RetentionPolicy(keepDaily = 7).keepDaily)
    }
}
