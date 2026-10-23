package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEditionGradleContractTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "go.mod").isFile && File(it, "build-logic/android-verification.gradle.kts").isFile }

    @Test
    fun flavorIsolationAndBothNativePackagingContractsAreDeclared() {
        val gradle = File(root, "android/BKX.Android/app/build.gradle.kts").readText()
        assertTrue(gradle.contains("flavorDimensions += \"edition\""))
        assertTrue(gradle.contains("create(\"embedded\") { dimension = \"edition\" }"))
        assertTrue(gradle.contains("create(\"lite\") { dimension = \"edition\" }"))
        val goProducer = gradle.substringAfter("onVariants(selector().all())")
            .substringBefore("onVariants(selector().withFlavor")
        assertTrue(goProducer.contains("buildBkxGoAndroidRuntime"))
        assertFalse(goProducer.contains("installBkxRcloneAndroidRuntime"))
        val embeddedProducer = gradle.substringAfter("onVariants(selector().withFlavor(\"edition\" to \"embedded\"))")
        assertTrue(embeddedProducer.contains("installBkxRcloneAndroidRuntime"))
        assertTrue(gradle.contains("verifyBkxEditionApkContents"))
        assertTrue(gradle.contains("assets/bkx-rclone-runtime.properties"))
    }
}
