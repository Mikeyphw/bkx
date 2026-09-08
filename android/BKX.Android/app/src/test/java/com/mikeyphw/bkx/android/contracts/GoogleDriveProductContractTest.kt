package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveProductContractTest {
    private val repositoryRoot = locateRepositoryRoot()
    private val appProjectRoot = File(repositoryRoot, "android/BKX.Android/app")
    private val mainRoot = File(appProjectRoot, "src/main/java/com/mikeyphw/bkx/android")

    private fun locateRepositoryRoot(): File =
        generateSequence(File(".").canonicalFile) { current -> current.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "go.mod").isFile &&
                    File(candidate, "build-logic/android-verification.gradle.kts").isFile &&
                    File(candidate, "scripts/validate.sh").isFile
            }
            ?: error("Unable to locate BKX repository root from ${File(".").canonicalPath}")

    @Test
    fun driveUsesGoogleAuthorizationApiWithDriveFileScopeAndNoLegacySignIn() {
        val manager = File(mainRoot, "app/drive/GoogleDriveAuthorizationManager.kt").readText()
        val config = File(mainRoot, "core/drive/GoogleDriveRcloneConfig.kt").readText()
        val catalog = File(repositoryRoot, "android/BKX.Android/gradle/libs.versions.toml").readText()

        assertTrue(manager.contains("Identity.getAuthorizationClient"))
        assertTrue(manager.contains("AuthorizationRequest.builder()"))
        assertTrue(manager.contains("Scope(Scopes.DRIVE_FILE)"))
        assertTrue(manager.contains(".setRequestedScopes(listOf(driveScope))"))
        assertTrue(manager.contains("client.authorize(request)"))
        assertTrue(manager.contains("getAuthorizationResultFromIntent"))
        assertTrue(manager.contains("RevokeAccessRequest.builder()"))
        assertFalse(manager.contains("GoogleSignInClient"))
        assertFalse(manager.contains("GoogleSignInOptions"))
        assertFalse(manager.contains("requestOfflineAccess"))
        assertTrue(config.contains("https://www.googleapis.com/auth/drive.file"))
        assertTrue(catalog.contains("playServicesAuth = \"21.6.0\""))
    }

    @Test
    fun managedDriveConfigurationIsReservedPrivateAndCredentialFreeAtUiBoundary() {
        val config = File(mainRoot, "core/drive/GoogleDriveRcloneConfig.kt").readText()
        val manager = File(mainRoot, "core/remotes/RcloneConfigManager.kt").readText()
        val models = File(mainRoot, "core/drive/GoogleDriveModels.kt").readText()
        val remoteModels = File(mainRoot, "core/remotes/RcloneModels.kt").readText()

        assertEquals("rclone://bkx-google-drive/BKX", GoogleDriveRcloneConfig.rootUri)
        assertTrue(config.contains("scope = drive.file"))
        assertTrue(manager.contains("updateManagedGoogleDriveToken"))
        assertTrue(manager.contains("preserveManagedSection"))
        assertTrue(manager.contains("setReadable(true, true)"))
        assertTrue(remoteModels.contains("BKX_GOOGLE_DRIVE_REMOTE_NAME = \"bkx-google-drive\""))
        assertFalse(models.contains("accessToken"))
        assertFalse(models.contains("refreshToken"))
        assertFalse(models.contains("password", ignoreCase = true))
    }

    @Test
    fun driveIsFirstClassBackupArchiveRestoreProductSurface() {
        val navigation = File(mainRoot, "app/navigation/BkxNavigation.kt").readText()
        val driveScreen = File(mainRoot, "feature/more/GoogleDriveScreen.kt").readText()
        val more = File(mainRoot, "feature/more/MoreScreen.kt").readText()
        val backup = File(mainRoot, "feature/backup/BackupScreen.kt").readText()
        val viewModel = File(mainRoot, "app/BkxAppViewModel.kt").readText()
        val dispatcher = File(repositoryRoot, "internal/services/dispatcher.go").readText()
        val backupGo = File(repositoryRoot, "internal/backupops/backup.go").readText()

        assertTrue(Regex("""enum\s+class\s+MoreRoute\s*\{[^}]*\bGoogleDrive\b""").containsMatchIn(navigation))
        assertTrue(more.contains("MoreRoute.GoogleDrive -> GoogleDriveScreen"))
        assertTrue(driveScreen.contains("Back up to Google Drive"))
        assertTrue(driveScreen.contains("Open Drive archives"))
        assertTrue(backup.contains("Google Drive"))
        assertTrue(backup.contains("GoogleDriveRcloneConfig.rootUri"))
        assertTrue(viewModel.contains("authorizeGoogleDriveForOperation"))
        assertTrue(viewModel.contains("requestUsesManagedGoogleDrive"))
        assertTrue(viewModel.contains("refreshArchives()"))
        assertTrue(dispatcher.contains("materializeArchive"))
        assertTrue(dispatcher.contains("ListRecursive"))
        assertTrue(backupGo.contains("Remote archive verification failed after upload"))
    }

    @Test
    fun cloudRuntimeHasInternetPermissionAndCapabilitiesAreTruthful() {
        val manifest = File(appProjectRoot, "src/main/AndroidManifest.xml").readText()
        val bridge = File(repositoryRoot, "internal/bridge/bridge.go").readText()

        assertTrue(manifest.contains("android.permission.INTERNET"))
        listOf("backup.remote", "archive.remote", "restore.remote", "cloud.google_drive").forEach { capability ->
            assertTrue("missing $capability", bridge.contains("\"$capability\""))
        }
    }

}
