package com.mikeyphw.bkx.android.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPathPolicyTest {
    @Test
    fun canonicalizesAliasesSlashesAndDotSegments() {
        assertEquals("/storage/emulated/0/BKX/file.bakery", AndroidPathPolicy.canonicalize("/storage/emulated/0//Download/../BKX/file.bakery"))
        assertEquals("/sdcard/BKX", AndroidPathPolicy.canonicalize("sdcard/BKX"))
    }

    @Test
    fun rejectsContentAndRestrictedSharedTrees() {
        assertNotNull(AndroidPathPolicy.restrictedWriteReason("content://provider/document/1"))
        assertNotNull(AndroidPathPolicy.restrictedWriteReason("/sdcard/Android/data/com.example"))
        assertNotNull(AndroidPathPolicy.restrictedWriteReason("/storage/emulated/0/Android/obb/com.example"))
        assertNull(AndroidPathPolicy.restrictedWriteReason("/storage/emulated/0/BKX/archive.bakery"))
    }

    @Test
    fun classifiesRootSensitiveButNotSharedPaths() {
        assertTrue(AndroidPathPolicy.requiresRoot("/data/data/com.termux/files/home"))
        assertTrue(AndroidPathPolicy.isTermuxPrivate("/data/data/com.termux/files/home"))
        assertFalse(AndroidPathPolicy.requiresRoot("/storage/emulated/0/BKX"))
    }
}
