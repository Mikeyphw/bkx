package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneRemoteTransportContractTest {
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
    fun androidPackagesPinnedOfficialAndroidRcloneRuntimeAndWiresItIntoGoSession() {
        val build = File(appProjectRoot, "build.gradle.kts").readText()
        val installer = File(repositoryRoot, "scripts/install-rclone-android-runtime.sh").readText()
        val runtimeContract = File(repositoryRoot, "scripts/rclone-runtime-contract.sh").readText()
        val gateway = File(mainRoot, "embedded/GoNativeGateway.kt").readText()

        assertTrue(build.contains("installBkxRcloneAndroidRuntime"))
        assertTrue(build.contains("generated/bkx-rclone-runtime/jniLibs"))
        assertTrue(build.contains("v1.75.1/android-21-armv8a"))
        assertTrue(installer.contains("rclone-android-${'$'}{RCLONE_ANDROID_API}-${'$'}{RCLONE_ANDROID_ARCH}.gz"))
        assertTrue(installer.contains("beta.rclone.org/v${'$'}{RCLONE_VERSION}/testbuilds"))
        assertTrue(installer.contains("BKX_RCLONE_ANDROID_SHA256"))
        assertTrue(installer.contains("bkx_rclone_validate_build_info"))
        assertTrue(installer.contains("bkx_rclone_validate_version_output"))
        assertTrue(runtimeContract.contains("GOOS=android"))
        assertTrue(runtimeContract.contains("GOARCH=arm64"))
        assertTrue(runtimeContract.contains("os/type: android"))
        assertTrue(runtimeContract.contains("os/arch: arm64"))
        assertFalse(installer.contains("linux-arm64"))
        assertTrue(gateway.contains("BKX_RCLONE_PATH"))
        assertTrue(gateway.contains("BKX_RCLONE_CONFIG"))
        assertTrue(gateway.contains("libbkxrclone_exec.so"))
    }

    @Test
    fun appPrivateConfigParserAndModelsDoNotExposeCredentialValues() {
        val manager = File(mainRoot, "core/remotes/RcloneConfigManager.kt").readText()
        val parser = File(mainRoot, "core/remotes/RcloneConfigParser.kt").readText()
        val models = File(mainRoot, "core/remotes/RcloneModels.kt").readText()

        assertTrue(manager.contains("filesDir.resolve(\"bkx/rclone\")"))
        assertTrue(manager.contains("MAX_RCLONE_CONFIG_BYTES"))
        assertTrue(manager.contains("RCLONE_ENCRYPT_V0:"))
        assertTrue(manager.contains("RcloneConfigParser.parse"))
        assertTrue(manager.contains("setReadable(true, true)"))
        assertTrue(parser.contains("SAFE_RCLONE_REMOTE_NAME"))
        assertTrue(models.contains("data class RcloneRemoteProfile"))
        assertTrue(models.contains("val name: String"))
        assertTrue(models.contains("val type: String"))
        assertFalse(models.contains("token", ignoreCase = true))
        assertFalse(models.contains("password", ignoreCase = true))
    }

    @Test
    fun remoteConfigAndTransferHaveDedicatedScreensAndPickerFirstUx() {
        val more = File(mainRoot, "feature/more/MoreScreen.kt").readText()
        val remotes = File(mainRoot, "feature/more/RcloneRemotesScreen.kt").readText()
        val transfer = File(mainRoot, "feature/transfer/TransferScreen.kt").readText()
        val models = File(mainRoot, "core/transfer/TransferModels.kt").readText()
        val browserModels = File(mainRoot, "core/files/FilesystemBrowserModels.kt").readText()
        val browser = File(mainRoot, "feature/home/FilesystemBrowserDialog.kt").readText()

        assertTrue(more.contains("MoreRoute.Remotes -> RcloneRemotesScreen"))
        assertFalse(more.contains("private fun RcloneRemotesScreen"))
        assertTrue(remotes.contains("Import rclone.conf"))
        assertTrue(remotes.contains("remote.rclone"))
        assertTrue(remotes.contains("remote.rclone.configured"))
        assertTrue(transfer.contains("RemoteChoices"))
        assertTrue(transfer.contains("Browse remote"))
        assertTrue(transfer.contains("Manual path · Advanced"))
        assertTrue(models.contains("location.startsWith(\"rclone://\""))
        assertTrue(models.contains("requires explicit approval to delete destination-only files"))
        assertTrue(browserModels.contains("allowRemote"))
        assertTrue(browser.contains("rclone://"))
    }

    @Test
    fun ov3LintWarningsUseApiSafeNotificationSettingsAndAllocatableStorage() {
        val more = File(mainRoot, "feature/more/MoreScreen.kt").readText()
        val backup = File(mainRoot, "feature/backup/BackupScreen.kt").readText()

        assertTrue(more.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O"))
        assertTrue(more.contains("ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(backup.contains("getAllocatableBytes"))
        assertTrue(backup.contains("getUuidForPath"))
        assertTrue(backup.contains("StatFs"))
        assertFalse(backup.contains(".usableSpace"))
    }

    @Test
    fun repositoryHasStructuredRemoteTransportVerificationEvidence() {
        val verifier = File(repositoryRoot, "scripts/verify-rclone-remote-transport.sh")
        val guide = File(repositoryRoot, "docs/guide/05_RCLONE_REMOTE_TRANSPORT.md")
        val checklist = File(repositoryRoot, "docs/checklists/CHECKLIST_ANDROID_REMOTE_TRANSPORT.md")
        val buildLogic = File(repositoryRoot, "build-logic/android-verification.gradle.kts").readText()

        assertTrue(verifier.isFile)
        assertTrue(guide.isFile)
        assertTrue(checklist.isFile)
        assertTrue(buildLogic.contains("verifyBkxRcloneRemoteTransportContract"))
    }
}
