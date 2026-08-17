package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun declaresTermuxRunCommandPermission() {
        assertTrue(manifest.contains("com.termux.permission.RUN_COMMAND"))
    }

    @Test
    fun declaresTermuxPackageVisibility() {
        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("<package android:name=\"com.termux\""))
        assertTrue(manifest.contains("<action android:name=\"com.termux.RUN_COMMAND\""))
    }
}
