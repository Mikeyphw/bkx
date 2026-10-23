package com.mikeyphw.bkx.android.core.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxEditionArtifactPolicyTest {
    @Test fun embeddedAndLiteHaveMutuallyExclusiveRcloneContract() {
        val common = setOf("lib/arm64-v8a/libbkxgo.so")
        val embedded = common + "lib/arm64-v8a/libbkxrclone_exec.so" + "assets/bkx-rclone-runtime.properties"
        assertTrue(BkxReleaseArtifactPolicy.editionMembersAreValid("embedded", embedded))
        assertTrue(BkxReleaseArtifactPolicy.editionMembersAreValid("lite", common))
        assertFalse(BkxReleaseArtifactPolicy.editionMembersAreValid("lite", embedded))
        assertFalse(BkxReleaseArtifactPolicy.editionMembersAreValid("embedded", common))
        assertFalse(BkxReleaseArtifactPolicy.editionMembersAreValid("unknown", embedded))
        assertFalse(BkxReleaseArtifactPolicy.editionMembersAreValid("lite", common + "assets/bkx-rclone-runtime.properties"))
    }
}
