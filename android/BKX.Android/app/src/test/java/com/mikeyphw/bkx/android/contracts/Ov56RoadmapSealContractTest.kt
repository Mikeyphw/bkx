package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ov56RoadmapSealContractTest {
    private val repositoryRoot = locateRepositoryRoot()

    private fun locateRepositoryRoot(): File =
        generateSequence(File(".").canonicalFile) { current -> current.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "go.mod").isFile &&
                    File(candidate, "build-logic/android-verification.gradle.kts").isFile &&
                    File(candidate, "scripts/validate.sh").isFile
            }
            ?: error("Unable to locate BKX repository root from ${File(".").canonicalPath}")

    @Test
    fun finalRoadmapMatrixNamesEveryPromisedOverlayAndLiveReleaseGate() {
        val roadmap = File(repositoryRoot, "docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md").readText()
        (1..6).forEach { phase -> assertTrue("OV$phase missing from roadmap seal", roadmap.contains("OV$phase")) }
        assertTrue(roadmap.contains("verifyBkxCloudProductReleaseSeal"))
        assertTrue(roadmap.contains("scripts/validate.sh"))
    }

    @Test
    fun devtoolAndGradleRunTheCombinedCloudReleaseContracts() {
        val devtool = File(repositoryRoot, ".devtool.toml").readText()
        val gradle = File(repositoryRoot, "build-logic/android-verification.gradle.kts").readText()
        listOf(
            ":app:bkxBr37StaticPreflightSeal",
            ":app:bkxBr37BehavioralUnitSeal",
            ":app:bkxBr37PackageAndReleaseLintSeal",
        ).forEach { task -> assertTrue("Devtool optimized validation missing $task", devtool.contains(task)) }
        assertTrue(gradle.contains("""tasks.register("verifyBkxGoogleDriveProductContract")"""))
        assertTrue(gradle.contains("""tasks.register("verifyBkxCloudProductReleaseSeal")"""))
        assertTrue(gradle.contains("""tasks.named("bkxAndroidReleaseSeal")"""))
        assertTrue(gradle.contains("\"verifyBkxCloudProductReleaseSeal\""))
        val targetTasks = devtool.substringAfter("[targets.bkx_android.tasks]").substringBefore("[targets.bkx_android.artifacts]")
        val validationPhases = devtool.substringAfter("[validation.phases]").substringBefore("[validation]")
        val packagePhase = validationPhases.substringAfter("package = [").substringBefore("]")
        assertTrue(targetTasks.contains("package = [\":app:bkxPersonalPackageAndReleaseLintSeal\"]"))
        assertTrue(packagePhase.contains(":app:bkxBr37PackageAndReleaseLintSeal"))
    }

    @Test
    fun currentDocsDoNotReintroduceRetiredAndroidPythonOrPreDriveRemoteLimits() {
        val setup = File(repositoryRoot, "android/BKX.Android/docs/SETUP_GUIDE.md").readText()
        val remote = File(repositoryRoot, "docs/guide/05_RCLONE_REMOTE_TRANSPORT.md").readText()
        assertTrue(setup.contains("packaged Go runtime"))
        assertTrue(setup.contains("More → Google Drive"))
        assertFalse(setup.contains("embedded Python runtime"))
        assertFalse(remote.contains("Backup/restore do not become remote-enabled"))
    }

    @Test
    fun ov4ActionableUseKtxWarningIsClosedRatherThanSuppressed() {
        val viewModel = File(
            repositoryRoot,
            "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt",
        ).readText()
        assertTrue(viewModel.contains("import androidx.core.net.toUri"))
        assertTrue(viewModel.contains("path.toUri()"))
        assertFalse(viewModel.contains("Uri.parse(path)"))
    }
}
