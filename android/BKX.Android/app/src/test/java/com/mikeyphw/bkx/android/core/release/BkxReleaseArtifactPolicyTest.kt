package com.mikeyphw.bkx.android.core.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxReleaseArtifactPolicyTest {
    @Test
    fun versionCodeUsesNonCollidingThreeDigitLanes() {
        assertEquals(1_000_000, BkxReleaseArtifactPolicy.androidVersionCode("1.0.0"))
        assertEquals(1_100_000, BkxReleaseArtifactPolicy.androidVersionCode("1.100.0"))
        assertEquals(1_010_000, BkxReleaseArtifactPolicy.androidVersionCode("1.10.0"))
        assertFalse(BkxReleaseArtifactPolicy.androidVersionCode("1.10.0") == BkxReleaseArtifactPolicy.androidVersionCode("1.9.100"))
    }

    @Test
    fun releaseCommitMustNotBeDevOrUnknown() {
        assertTrue(BkxReleaseArtifactPolicy.isReleaseCommit("abc123456789"))
        assertFalse(BkxReleaseArtifactPolicy.isReleaseCommit("dev"))
        assertFalse(BkxReleaseArtifactPolicy.isReleaseCommit("unknown"))
        assertFalse(BkxReleaseArtifactPolicy.isReleaseCommit("bad commit with spaces"))
    }

    @Test
    fun releaseAbiIsExactlyArm64() {
        assertTrue(BkxReleaseArtifactPolicy.validateReleaseAbi(listOf("arm64-v8a")))
        assertFalse(BkxReleaseArtifactPolicy.validateReleaseAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(BkxReleaseArtifactPolicy.validateReleaseAbi(emptyList()))
    }

    @Test
    fun releaseArtifactGateTasksAreStaged() {
        val tasks = BkxReleaseArtifactPolicy.releaseArtifactTasks
        assertTrue(tasks.contains(":app:assembleEmbeddedRelease"))
        assertTrue(tasks.contains(":app:assembleLiteRelease"))
        assertTrue(tasks.contains(":app:lintEmbeddedRelease"))
        assertTrue(tasks.contains(":app:lintLiteRelease"))
        assertTrue(tasks.contains(":app:verifyBkxReleaseMergedManifestContract"))
        assertTrue(tasks.contains(":app:verifyBkx16KbNativePageSizeContract"))
        assertTrue(tasks.contains(":app:verifyBkxEditionApkContents"))
    }
}

