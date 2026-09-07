package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun declaresAllFilesAccessAndRootService() {
        assertTrue(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertTrue(manifest.contains(".root.BkxRootEngineService"))
    }

    @Test
    fun operationNotificationsDeclareForegroundRuntimeAndLiveUpdatePermissions() {
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
    }

    @Test
    fun termuxIsNotAnExecutionDependency() {
        assertFalse(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertFalse(manifest.contains("com.termux.RUN_COMMAND"))
        assertFalse(manifest.contains("<package android:name=\"com.termux\""))
    }
}
