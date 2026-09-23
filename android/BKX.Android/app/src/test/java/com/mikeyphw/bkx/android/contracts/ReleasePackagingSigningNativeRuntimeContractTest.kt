package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleasePackagingSigningNativeRuntimeContractTest {
    private val androidRoot = File(requireNotNull(System.getProperty("user.dir"))).parentFile
        ?: error("Unable to resolve Android project root")
    private val repoRoot = androidRoot.parentFile.parentFile

    @Test
    fun gradleBuildStampsNativeRuntimeAndModelsInputs() {
        val build = File(androidRoot, "app/build.gradle.kts").readText()
        assertTrue(build.contains("bkxResolvedBuildCommit"))
        assertTrue(build.contains("""environment("BKX_BUILD_COMMIT", buildCommit.get())"""))
        assertTrue(build.contains("""inputs.file(bkxRepositoryRoot.resolve("go.sum"))"""))
        assertTrue(build.contains("""inputs.property("bkxBuildCommit", bkxBuildCommit)"""))
        assertTrue(build.contains("bkxAndroidVersionCode"))
        assertTrue(build.contains("major * 1_000_000 + minor * 1_000 + patch"))
    }

    @Test
    fun releaseBuildIsShrunkAndHasSeparateReleaseLintAndMergedManifestGate() {
        val build = File(androidRoot, "app/build.gradle.kts").readText()
        val verification = File(repoRoot, "build-logic/android-verification.gradle.kts").readText()
        assertTrue(build.contains("isMinifyEnabled = true"))
        assertTrue(build.contains("isShrinkResources = true"))
        assertTrue(File(androidRoot, "app/proguard-rules.pro").isFile)
        assertTrue(verification.contains("verifyBkxReleaseMergedManifestContract"))
        assertTrue(verification.contains("processReleaseMainManifest"))
        assertTrue(verification.contains("lintRelease"))
    }

    @Test
    fun exactReleaseQualificationAndCiArePresent() {
        val qualify = File(androidRoot, "scripts/qualify-device.sh").readText()
        val ci = File(repoRoot, ".github/workflows/ci.yml").readText()
        assertTrue(qualify.contains("--release-apk"))
        assertTrue(qualify.contains("--android-test-apk"))
        assertTrue(qualify.contains("bkxExactReleaseArtifact true"))
        assertTrue(ci.contains("android-release-packaging"))
        assertTrue(ci.contains("verifyBkxReleasePackagingSigningNativeRuntimeSeal"))
    }

    @Test
    fun releaseSealIsStagedAndNativePageSizeIsNotPreflightOnly() {
        val verification = File(repoRoot, "build-logic/android-verification.gradle.kts").readText()
        val devtool = File(repoRoot, ".devtool.toml").readText()
        assertTrue(verification.contains("bkxAndroidReleasePreflightSeal"))
        assertTrue(verification.contains("bkxAndroidReleasePackageSeal"))
        assertTrue(verification.contains("bkxAndroidReleaseInstrumentationSeal"))
        assertTrue(verification.contains("verifyBkxReleasePackagingSigningNativeRuntimeSeal"))
        val preflightPhase = devtool.substringAfter("preflight = [").substringBefore("]")
        val targetTasks = devtool.substringAfter("[targets.bkx_android.tasks]").substringBefore("[targets.bkx_android.artifacts]")
        val validationPhases = devtool.substringAfter("[validation.phases]").substringBefore("[validation]")
        val packagePhase = validationPhases.substringAfter("package = [").substringBefore("]")
        assertFalse(preflightPhase.contains("verifyBkx16KbNativePageSizeContract"))
        assertTrue(targetTasks.contains("package = [\":app:bkxPersonalPackageAndReleaseLintSeal\"]"))
        assertTrue(
            packagePhase.contains("verifyBkx16KbNativePageSizeContract") ||
                packagePhase.contains("bkxBr37PackageAndReleaseLintSeal"),
        )
        val packageAggregate = verification.substringAfter("val bkxBr37PackageAndReleaseLintTasks = listOf")
            .substringBefore("""tasks.register("bkxBr37PackageAndReleaseLintSeal")""")
        assertTrue(packageAggregate.contains("bkxAndroidReleasePackageSeal"))
        assertTrue(verification.contains("verifyBkx16KbNativePageSizeContract"))

    }
}
