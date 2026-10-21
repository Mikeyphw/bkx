package com.mikeyphw.bkx.android.contracts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChrootImportUiContractTest {
    private fun source(path: String) = File("src/main/java/com/mikeyphw/bkx/android/$path").readText()

    @Test fun dedicatedImportRouteUsesGuardedCoordinator() {
        val nav=source("app/navigation/BkxNavigation.kt")
        val vm=source("app/BkxAppViewModel.kt")
        val ui=source("feature/backup/BackupScreen.kt")
        assertTrue("ImportChroot" in nav)
        assertTrue("PreviewChrootImport" in vm && "ExecuteChrootImport" in vm)
        assertTrue("coordinator.stageAndPreview" in vm && "confirmationGranted=true" in vm)
        assertTrue("Inspect archive" in ui && "Review complete — enable import" in ui && "Import chroot" in ui)
    }

    @Test fun legacyAndRecoveryAreExplicit() {
        val vm=source("app/BkxAppViewModel.kt")
        val ui=source("feature/backup/BackupScreen.kt")
        assertTrue("SetChrootImportLegacyAllowed" in vm)
        assertTrue("allowLegacyMetadata" in ui && "never enabled silently" in ui)
        assertTrue("CleanupChrootImportStaging" in vm && "Retry safe cleanup" in ui)
    }

    @Test fun archiveChooserSupportsLocalAndConfiguredRclone() {
        val files=source("core/files/FilesystemBrowserModels.kt")
        val ui=source("feature/backup/BackupScreen.kt")
        assertTrue("ChrootImportArchive(false, false, allowRemote = true)" in files)
        assertTrue("Browse local" in ui && "Browse cloud / remote" in ui)
        assertTrue("RcloneConfigManager(context).snapshot().profiles" in ui)
    }

    @Test fun managedGoogleDriveIsAvailableAsImportSource() {
        val screen = java.io.File("src/main/java/com/mikeyphw/bkx/android/feature/backup/BackupScreen.kt").readText()
        assertTrue(screen.contains("RcloneConfigManager(context).snapshot().profiles"))
        assertTrue(screen.contains("Google Drive ·"))
        val importScreen = screen.substringAfter("private fun ChrootImportScreen(")
        val importProfiles = importScreen.substringAfter("val importRemotes =").substringBefore("\n")
        assertTrue("RcloneConfigManager(context).snapshot().profiles" in importProfiles)
        assertFalse("filterNot" in importProfiles)
    }

    @Test fun invalidatingPreviewAttemptsGuardedRootStagingCleanup() {
        val vm = java.io.File("src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt").readText()
        assertTrue(vm.contains("invalidateChrootImportPreview"))
        assertTrue(vm.contains("coordinator.cleanupRetained(stalePreview)"))
        assertTrue(vm.contains("retainedStagingId = stalePreview.stagingId"))
    }
}
