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
    fun termuxIsNotAnExecutionDependency() {
        assertFalse(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertFalse(manifest.contains("com.termux.RUN_COMMAND"))
        assertFalse(manifest.contains("<package android:name=\"com.termux\""))
    }
}
