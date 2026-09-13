package com.mikeyphw.bkx.android.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStagingContractTest {
    @Test
    fun stagedProviderItemCarriesLeaseAndRejectsContentPathOutput() {
        val item = ProviderStagedItem(
            sourceUri = "content://provider/document/1",
            displayName = "archive.bakery",
            stagedPath = "/data/user/0/com.mikeyphw.bkx/cache/bkx/provider-staging/item-1/archive.bakery",
            kind = UserSelectionKind.Document,
            filesCopied = 1,
            bytesCopied = 10,
            sha256 = "0".repeat(64),
            leaseId = "item-1",
            leaseExpiresAtEpochMillis = 100,
        )
        assertEquals("item-1", item.leaseId)
        assertTrue(item.leaseExpiresAtEpochMillis > 0L)
        assertFalse(ContentUriPathPolicy.isContentUri(item.stagedPath))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stagedProviderItemRequiresLease() {
        ProviderStagedItem(
            sourceUri = "content://provider/document/1",
            displayName = "archive.bakery",
            stagedPath = "/tmp/archive.bakery",
            kind = UserSelectionKind.Document,
            filesCopied = 1,
            bytesCopied = 10,
            leaseId = " ",
            leaseExpiresAtEpochMillis = 100,
        )
    }
}
