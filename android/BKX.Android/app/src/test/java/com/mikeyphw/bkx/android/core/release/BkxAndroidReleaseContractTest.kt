package com.mikeyphw.bkx.android.core.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxAndroidReleaseContractTest {
    @Test
    fun releaseSealDeclaresCompleteFinalValidationMatrix() {
        assertEquals(1, BkxAndroidReleaseContract.REQUIRED_PROTOCOL_VERSION)
        listOf(
            ":app:testDebugUnitTest",
            ":app:lintDebug",
            ":app:connectedDebugAndroidTest",
            ":app:assembleRelease",
            ":app:verifyBkxRuntimeHardeningContract",
            ":app:verifyBkxRestoreSafetySeal",
            ":app:verifyBkxPerformanceAndProgressContract",
            ":app:verifyBkxLargeTreePerformanceSeal",
            ":app:verifyBkxStorageCompletenessContract",
            ":app:verifyBkxAccessibilityVisualContract",
            ":app:verifyBkxEmbeddedParitySeal",
            ":app:verifyBkxReleaseHygiene",
            ":app:verifyBkxDeviceQualificationContract",
            ":app:verifyBkxFinalReleaseContract",
            ":app:bkxAndroidReleaseSeal",
        ).forEach { task -> assertTrue("missing $task", BkxAndroidReleaseContract.requiredDevtoolTasks.contains(task)) }
    }

    @Test
    fun releaseSealCarriesFinalAdversarialCoverageList() {
        val cases = BkxAndroidReleaseContract.requiredAdversarialCases.joinToString("|")
        assertTrue(cases.contains("root denied"))
        assertTrue(cases.contains("Termux private path"))
        assertTrue(cases.contains("process death"))
        assertTrue(cases.contains("cooperative cancellation"))
        assertTrue(cases.contains("protected Android restore root"))
        assertTrue(cases.contains("confirmation bypass"))
    }
}
