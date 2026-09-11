package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSigningContractTest {
    private val androidRoot = File(requireNotNull(System.getProperty("user.dir"))).parentFile
        ?: error("Unable to resolve Android project root")

    @Test
    fun permanentSigningStaysExternalAndCoversDebugAndRelease() {
        val build = File(androidRoot, "app/build.gradle.kts").readText()
        val setup = File(androidRoot, "tools/setup-personal-signing.sh").readText()
        val fingerprint = File(androidRoot, "tools/print-personal-signing-fingerprint.sh").readText()

        assertTrue(build.contains("BKX_ANDROID_SIGNING_PROPERTIES"))
        assertTrue(build.contains(".config/bkx/android-signing.properties"))
        assertTrue(build.contains("create(\"bkxPersonal\")"))
        assertTrue(build.contains("getByName(\"debug\")"))
        assertTrue(build.contains("getByName(\"release\")"))
        assertTrue(build.contains("signingConfig = signingConfigs.getByName(\"bkxPersonal\")"))
        assertTrue(build.contains("abstract class BkxSigningStatusTask"))
        assertTrue(build.contains("abstract class VerifyBkxPersonalSigningConfiguredTask"))
        assertTrue(build.contains("tasks.register<BkxSigningStatusTask>(\"bkxSigningStatus\")"))
        assertTrue(build.contains("tasks.register<VerifyBkxPersonalSigningConfiguredTask>(\"verifyBkxPersonalSigningConfigured\")"))
        assertFalse(build.contains("tasks.register(\"bkxSigningStatus\")"))
        assertFalse(build.contains("tasks.register(\"verifyBkxPersonalSigningConfigured\")"))
        assertTrue(build.contains("verifyBkxPersonalSigningConfigured"))
        assertTrue(build.contains("assemblePersonalDebug"))
        assertTrue(build.contains("assemblePersonalRelease"))
        assertTrue(setup.contains("\$HOME/.config/bkx/bkx-personal.p12"))
        assertTrue(setup.contains("-storepass:env BKX_SIGNING_SETUP_PASSWORD"))
        assertTrue(setup.contains("SHA1:"))
        assertTrue(fingerprint.contains("-storepass:env BKX_SIGNING_FINGERPRINT_PASSWORD"))
        assertTrue(fingerprint.contains("SHA1:"))
        assertFalse(setup.contains("REPLACE_ME"))
    }
}
