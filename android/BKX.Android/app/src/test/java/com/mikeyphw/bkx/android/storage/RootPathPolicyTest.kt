package com.mikeyphw.bkx.android.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPathPolicyTest {
    @Test
    fun termuxPrivateTreeIsRootOnly() {
        assertTrue(RootPathPolicy.isTermuxPrivate("/data/data/com.termux/files"))
        assertTrue(RootPathPolicy.isTermuxPrivate("/data/data/com.termux/files/home"))
        assertTrue(RootPathPolicy.isTermuxPrivate("/data/data/com.termux/files/home/storage/downloads"))
        assertTrue(RootPathPolicy.isTermuxPrivate("/data/data/com.termux/files/usr/bin/python"))
        assertTrue(RootPathPolicy.requiresRoot("/data/data/com.termux/files/home"))
    }

    @Test
    fun sharedStorageDoesNotRequireRoot() {
        assertFalse(RootPathPolicy.requiresRoot("/storage/emulated/0/Download"))
        assertFalse(RootPathPolicy.requiresRoot("/sdcard/Documents"))
    }

    @Test
    fun privilegedDeviceTreesRequireRoot() {
        assertTrue(RootPathPolicy.requiresRoot("/data/adb/modules"))
        assertTrue(RootPathPolicy.requiresRoot("/data/user/0/com.termux"))
        assertTrue(RootPathPolicy.requiresRoot("/mnt/media_rw/1234-5678"))
    }
}
