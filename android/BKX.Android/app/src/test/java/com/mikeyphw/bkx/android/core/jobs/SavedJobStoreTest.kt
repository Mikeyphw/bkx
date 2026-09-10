package com.mikeyphw.bkx.android.core.jobs

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedJobStoreTest {
    private fun draft(name: String = "Phone backup") = SavedJobDraft(
        jobId = "phone-backup",
        name = name,
        operation = OperationSpecV2(
            operation = "backup.create",
            parameters = buildJsonObject { put("verify", true) },
            repositoryRef = "drive-main",
        ),
    )

    @Test
    fun createUpdateAndRevisionHistoryAreDeterministic() = runTest {
        val times = ArrayDeque(listOf("2026-09-10T10:00:00Z", "2026-09-10T11:00:00Z"))
        val store = InMemorySavedJobStore { times.removeFirst() }

        val created = store.create(draft())
        val updated = store.update("phone-backup", 1, draft("Phone backup nightly"))

        assertEquals(1, created.revision)
        assertEquals(2, updated.revision)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals("2026-09-10T11:00:00Z", updated.updatedAt)
        assertEquals(listOf(1L, 2L), store.revisions("phone-backup").map { it.revision })
    }

    @Test(expected = SavedJobRevisionConflictException::class)
    fun staleUpdateFailsClosed() = runTest {
        val store = InMemorySavedJobStore { "2026-09-10T10:00:00Z" }
        store.create(draft())
        store.update("phone-backup", 99, draft("stale"))
    }

    @Test
    fun deleteRemovesCurrentAndRevisionHistory() = runTest {
        val store = InMemorySavedJobStore { "2026-09-10T10:00:00Z" }
        store.create(draft())
        assertTrue(store.delete("phone-backup"))
        assertNull(store.get("phone-backup"))
        assertTrue(store.revisions("phone-backup").isEmpty())
    }
}
