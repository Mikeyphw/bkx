
package com.mikeyphw.bkx.android.core.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxAndroidReleaseContractTest {
    @Test
    fun releaseSealDeclaresFailClosedValidationTasks() {
        assertEquals(1, BkxAndroidReleaseContract.REQUIRED_PROTOCOL_VERSION)
        assertTrue(BkxAndroidReleaseContract.requiredDevtoolTasks.contains(":app:testDebugUnitTest"))
        assertTrue(BkxAndroidReleaseContract.requiredDevtoolTasks.contains(":app:lintDebug"))
        assertTrue(BkxAndroidReleaseContract.requiredDevtoolTasks.contains(":app:connectedDebugAndroidTest"))
        assertTrue(BkxAndroidReleaseContract.requiredDevtoolTasks.contains(":app:assembleRelease"))
        assertTrue(BkxAndroidReleaseContract.requiredDevtoolTasks.contains(":app:bkxAndroidReleaseSeal"))
    }

    @Test
    fun releaseSealCarriesAdversarialCoverageList() {
        val cases = BkxAndroidReleaseContract.requiredAdversarialCases.joinToString("|")
        assertTrue(cases.contains("root denied"))
        assertTrue(cases.contains("Termux private path"))
        assertTrue(cases.contains("SAF fallback grant loss"))
        assertTrue(cases.contains("malformed bridge JSON"))
        assertTrue(cases.contains("confirmation bypass"))
    }
}
