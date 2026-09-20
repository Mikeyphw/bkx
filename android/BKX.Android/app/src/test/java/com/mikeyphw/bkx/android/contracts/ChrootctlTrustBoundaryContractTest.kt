package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlTrustBoundaryContractTest {
    private val main = File("src/main")

    @Test
    fun `privileged API exposes one fixed chrootctl readiness method and no command executor`() {
        val aidl = File(main, "aidl/com/mikeyphw/bkx/android/root/IBkxRootEngineService.aidl").readText()
        val service = File(main, "java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt").readText()
        assertTrue("String chrootctlReadinessJson();" in aidl)
        assertTrue("ChrootctlRuntimeResolver.resolve()" in service)
        assertFalse("execRoot" in aidl)
        assertFalse("shellCommand" in aidl)
        assertFalse("executablePath" in aidl)
    }

    @Test
    fun `readiness port respects BKX no-root mode before binding privileged transport`() {
        val port = File(main, "java/com/mikeyphw/bkx/android/root/RootChrootctlReadinessPort.kt").readText()
        assertTrue("modeProvider() == RootAccessMode.Disabled" in port)
        assertTrue("rootClient.rootAvailable()" in port)
        assertTrue("rootClient.chrootctlReadinessJson()" in port)
    }
}
