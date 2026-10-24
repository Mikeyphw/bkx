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
    fun androidBuildsReviewedBcloneReleaseAndWiresItIntoGoSession() {
        val build = File(appProjectRoot, "build.gradle.kts").readText()
        val installer = File(repositoryRoot, "scripts/install-rclone-android-runtime.sh").readText()
        val reviewedLock = File(repositoryRoot, "config/bclone-android-source.lock").readText()
        val runtimeContract = File(repositoryRoot, "scripts/rclone-runtime-contract.sh").readText()
        val gateway = File(mainRoot, "embedded/GoNativeGateway.kt").readText()

        assertTrue(build.contains("installBkxRcloneAndroidRuntime"))
        assertTrue(build.contains("generated/bkx-rclone-runtime/jniLibs"))
        assertTrue(build.contains("bclone/source-reviewed-or-development-latest/android-24-arm64/16k"))
        assertTrue(build.contains("config/bclone-android-source.lock"))
        assertTrue(build.contains("scripts/verify-bclone-source-lock.py"))
        assertTrue(reviewedLock.contains("schema=bkx-bclone-source-lock-v1"))
        assertTrue(reviewedLock.contains("repository=https://github.com/BenjiThatFoxGuy/bclone.git"))
        assertTrue(Regex("(?m)^tag=v[0-9]+\\.[0-9]+\\.[0-9]+$").containsMatchIn(reviewedLock))
        assertTrue(Regex("(?m)^commit=[a-f0-9]{40}$").containsMatchIn(reviewedLock))
        assertTrue(build.contains("@UntrackedTask"))
        assertTrue(build.contains("androidComponents.sdkComponents.ndkDirectory"))
        assertTrue(build.contains("verifyBkx16KbNativePageSizeContract"))
        assertTrue(installer.contains("BCLONE_REPOSITORY=\"https://github.com/BenjiThatFoxGuy/bclone.git\""))
        assertTrue(installer.contains("BCLONE_LATEST_RELEASE_URL=\"https://github.com/BenjiThatFoxGuy/bclone/releases/latest\""))
        assertTrue(installer.contains("git ls-remote --tags \"${'$'}BCLONE_REPOSITORY\""))
        assertTrue(installer.contains("git -C \"${'$'}source_dir\" checkout --quiet --detach \"${'$'}BCLONE_COMMIT\""))
        // The mutable latest lane remains available for development; release CI selects the reviewed lock.
        assertTrue(installer.contains("BCLONE_RESOLUTION=\"github-latest-stable-release\""))
        assertTrue(installer.contains("BCLONE_RESOLUTION=\"reviewed-source-lock\""))
        assertTrue(installer.contains("resolve_reviewed_bclone_release"))
        assertTrue(installer.contains("BKX_RCLONE_REQUIRE_LOCK"))
        assertTrue(installer.contains("BKX_RCLONE_SOURCE_LOCK"))
        assertTrue(installer.contains("bkx-rclone-runtime.properties"))
        assertTrue(runtimeContract.contains("vcs.revision="))
        assertTrue(installer.contains("go build -mod=readonly"))
        assertTrue(installer.contains("CGO_ENABLED=1"))
        assertTrue(installer.contains("rejected by Go/cgo + 16 KiB probe; trying next candidate"))
        assertTrue(installer.contains("termux-native-clang-ndk-sysroot"))
        assertTrue(installer.contains("make_termux_ndk_sysroot_shim"))
        assertTrue(installer.contains("max-page-size=${'$'}{BKX_ANDROID_PAGE_SIZE}"))
        assertTrue(installer.contains("common-page-size=${'$'}{BKX_ANDROID_PAGE_SIZE}"))
        assertTrue(installer.contains("verify-native-page-size.go"))
        // Python validates the reviewed source lock; it is not an alternate native runtime compiler.
        assertTrue(installer.contains("python3 \"${'$'}validator\" \"${'$'}lock\""))
        assertFalse(build.contains("python3"))
        assertTrue(File(repositoryRoot, "scripts/verify-native-page-size.go").isFile)
        assertFalse(File(repositoryRoot, "scripts/verify-elf-page-size.py").exists())
        assertFalse(File(repositoryRoot, "scripts/verify-apk-native-page-size.py").exists())
        assertTrue(installer.contains("bkx_rclone_validate_build_info"))
        assertTrue(installer.contains("bkx_rclone_validate_version_output"))
        assertTrue(runtimeContract.contains("GOOS=android"))
        assertTrue(runtimeContract.contains("GOARCH=arm64"))
        assertTrue(runtimeContract.contains("CGO_ENABLED=1"))
        assertTrue(runtimeContract.contains("os/type: android"))
        assertTrue(runtimeContract.contains("os/arch: arm64"))
        assertFalse(installer.contains("RCLONE_VERSION=\"1.75.1\""))
        assertFalse(installer.contains("RCLONE_SOURCE_SHA256="))
        assertFalse(installer.contains("RCLONE_VENDOR_SHA256="))
        assertFalse(installer.contains("downloads.rclone.org/"))
        assertFalse(installer.contains("beta.rclone.org"))
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
        assertTrue(buildLogic.contains("verifyBkx16KbNativePageSizeContract"))
        assertTrue(File(repositoryRoot, "scripts/verify-native-page-size.go").isFile)
    }
}
