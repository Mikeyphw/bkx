package com.mikeyphw.bkx.android.core.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

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
        for (selector in listOf("latest", "latest~0", "latest~1", " latest~2 ", "latest~-1", "latest~bad")) {
            try {
                RepositoryRequestBuilder.snapshotForget("r1", repository, selector, confirm = true)
                fail("Expected IllegalArgumentException for relative snapshot selector: $selector")
            } catch (_: IllegalArgumentException) {
                // Expected: destructive forget requires an immutable snapshot ID.
            }
        }
    }

    @Test fun pruneDefaultsToDryRunAndUnconfirmed() {
        val request = RepositoryRequestBuilder.prune("r1", repository)
        assertEquals("true", request.payload["dry_run"].toString())
        assertFalse(request.payload["confirm"].toString().toBoolean())
    }

    @Test fun retentionCountsCannotBeZero() {
        try {
            RetentionPolicy(keepLast = 0)
            fail("Expected IllegalArgumentException for zero retention count")
        } catch (_: IllegalArgumentException) {
            // Expected: enabled retention buckets must keep at least one snapshot.
        }
        assertEquals(7, RetentionPolicy(keepDaily = 7).keepDaily)
    }
}
