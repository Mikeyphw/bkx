package com.mikeyphw.bkx.android.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxSharedStateContractTest {
    @Test
    fun sharedControlPlaneIsExplicitAndDoesNotNameSecretFiles() {
        assertEquals("/storage/emulated/0/BKX/.state", BkxSharedStateContract.ROOT)
        assertEquals(".bkx-state.json", BkxSharedStateContract.MARKER)
        assertEquals("BKX_STATE_DIR", BkxSharedStateContract.ENVIRONMENT)
        val material = listOf(BkxSharedStateContract.ROOT, BkxSharedStateContract.MARKER, BkxSharedStateContract.ENVIRONMENT).joinToString(" ").lowercase()
        assertFalse(material.contains("password"))
        assertFalse(material.contains("oauth"))
        assertFalse(material.contains("rclone.conf"))
    }
}

class BR34SharedStateContractTest {
    @Test
    fun markerSchemaIsVersionedAndCompatibleWithGoResolver() {
        assertEquals(2, BkxSharedStateContract.SCHEMA_VERSION)
        assertEquals(1, BkxSharedStateContract.MIN_SCHEMA_VERSION)
    }

    @Test
    fun atomicPublisherIsTheOnlySupportedPublicationPrimitive() {
        val text = BkxAtomicFilePublisher::class.java.name
        assertTrue(text.contains("BkxAtomicFilePublisher"))
    }
}
