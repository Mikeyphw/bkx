package com.mikeyphw.bkx.android.contracts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChrootctlImportHandoffContractTest {
    private fun source(path: String)=File("src/main/java/com/mikeyphw/bkx/android/$path").readText()
    @Test fun importIsPreviewBoundInteractiveAndNoShellSurface() {
        val coordinator=source("app/chrootctl/ChrootctlImportCoordinator.kt")
        val aidl=File("src/main/aidl/com/mikeyphw/bkx/android/root/IBkxRootEngineService.aidl").readText()
        assertTrue("confirmationGranted" in coordinator)
        assertTrue("bindPreview" in coordinator)
        assertTrue("allowLegacyMetadata" in coordinator)
        assertTrue("chrootctl.cancel" in coordinator)
        assertTrue("chrootctlStageImportArchive" in aidl)
        assertTrue("chrootctlCleanupImportStaging" in aidl)
        assertTrue("cleanupRootStaging" in coordinator)
        assertTrue("cleanupRetained" in coordinator)
        assertTrue("acquisitionFreeBytes" in coordinator)
        assertFalse(Regex("shell|argv|executable", RegexOption.IGNORE_CASE).containsMatchIn(aidl))
    }
}
