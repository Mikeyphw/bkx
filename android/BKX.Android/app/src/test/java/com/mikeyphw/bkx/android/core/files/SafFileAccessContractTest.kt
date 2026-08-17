package com.mikeyphw.bkx.android.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFileAccessContractTest {
    private val grant = PersistedUriGrantRecord(
        uri = "content://provider/document/1",
        kind = UserSelectionKind.Document,
        read = true,
        write = false,
        state = UriGrantState.Active,
        persistedAtEpochMillis = 10,
    )

    @Test(expected = FileAccessPolicyException::class)
    fun contentUriCannotBeTreatedAsPosixPath() {
        ContentUriPathPolicy.requireNotContentUriPath("content://provider/document/1")
    }

    @Test
    fun stagingRecordKeepsSourceUriStagedPathSizeHashAndLifetime() {
        val selection = UserFileSelection(
            uri = grant.uri,
            kind = UserSelectionKind.Document,
            displayName = "archive.bkx",
            grant = grant,
            selectedAtEpochMillis = 11,
        )
        val record = StagedFilePlanner().plan(
            selection = selection,
            stagingPath = "/shared/bkx/staging/request-1/archive.bkx",
            evidence = StagingEvidence(
                sizeBytes = 42,
                sha256 = "0".repeat(64),
                requiresHashing = false,
            ),
        )

        assertEquals("content://provider/document/1", record.sourceUri)
        assertEquals("/shared/bkx/staging/request-1/archive.bkx", record.stagedPath)
        assertEquals(42L, record.sizeBytes)
        assertEquals(StagingLifetime.PerRequest, record.lifetime)
        assertTrue(record.cost.userVisibleMessage.contains("copied"))
    }

    @Test(expected = FileAccessPolicyException::class)
    fun lostGrantBlocksStaging() {
        val lostGrant = grant.copy(state = UriGrantState.Lost)
        StagedFilePlanner().plan(
            selection = UserFileSelection(
                uri = lostGrant.uri,
                kind = UserSelectionKind.Document,
                displayName = "archive.bkx",
                grant = lostGrant,
                selectedAtEpochMillis = 12,
            ),
            stagingPath = "/shared/bkx/archive.bkx",
            evidence = StagingEvidence(sizeBytes = null, sha256 = null),
        )
    }
}
