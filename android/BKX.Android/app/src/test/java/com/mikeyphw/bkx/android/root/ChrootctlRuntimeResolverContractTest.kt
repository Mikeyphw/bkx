package com.mikeyphw.bkx.android.root

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlRuntimeResolverContractTest {
    @Test
    fun `root-owned non-writable metadata is trusted`() {
        assertTrue(ChrootctlRuntimeResolver.trustedRootMetadata(uid = 0, mode = 0x81ed)) // 0755 regular file
        assertFalse(ChrootctlRuntimeResolver.trustedRootMetadata(uid = 10291, mode = 0x81ed))
        assertFalse(ChrootctlRuntimeResolver.trustedRootMetadata(uid = 0, mode = 0x81fd)) // 0775
        assertFalse(ChrootctlRuntimeResolver.trustedRootMetadata(uid = 0, mode = 0x81ef)) // 0757
    }

    @Test
    fun `sealed inventory paths reject traversal and manifest self references`() {
        assertTrue(ChrootctlRuntimeResolver.isSafeModuleRelativePath("system/bin/chrootctl"))
        assertFalse(ChrootctlRuntimeResolver.isSafeModuleRelativePath("../system/bin/chrootctl"))
        assertFalse(ChrootctlRuntimeResolver.isSafeModuleRelativePath("/data/adb/chrootctl"))
        assertFalse(ChrootctlRuntimeResolver.isSafeModuleRelativePath("config/chrootctl/module-package-manifest.json"))
        assertTrue(ChrootctlRuntimeResolver.isModuleManagerMetadata("disable"))
        assertFalse(ChrootctlRuntimeResolver.isModuleManagerMetadata("system/bin/extra"))
        assertTrue(
            ChrootctlRuntimeResolver.hasRequiredModulePackageMembers(
                setOf("system/bin/chrootctl", "runtime/arm64-v8a/chrootctl", "uninstall.sh"),
            ),
        )
        assertFalse(
            ChrootctlRuntimeResolver.hasRequiredModulePackageMembers(
                setOf("system/bin/chrootctl", "runtime/arm64-v8a/chrootctl"),
            ),
        )
    }

    @Test
    fun `external compatibility ignores companion app id but binds module runtime and protocol`() {
        val identity = ChrootctlRuntimeResolver.PackageIdentity(
            moduleVersion = "1.3.0",
            moduleVersionCode = 15,
            backendVersion = "0.11.0",
            bridgeProtocolVersion = 1,
            runtimeSchemaVersion = 2,
            runtimeKind = "go-native",
            runtimeAbi = "arm64-v8a",
            runtimeEntrypoint = "chrootctl",
        )
        val document = Json.parseToJsonElement(
            """{
              "schemaVersion":3,
              "project":"chrootctl",
              "bridgeProtocolVersion":1,
              "backend":{"implementation":"go","version":"0.11.0"},
              "nativeRuntime":{"schemaVersion":2,"kind":"go-native","platform":"android","abi":"arm64-v8a","entrypoint":"chrootctl"},
              "module":{"id":"chrootctl","version":"1.3.0","versionCode":15,"minimumSupportedBridgeProtocolVersion":1}
            }""",
        ).jsonObject
        assertTrue(ChrootctlRuntimeResolver.validateExternalCompatibilityDocument(document, identity))

        val wrongProtocol = Json.parseToJsonElement(document.toString().replace("\"bridgeProtocolVersion\":1", "\"bridgeProtocolVersion\":2"))
            .jsonObject
        assertFalse(ChrootctlRuntimeResolver.validateExternalCompatibilityDocument(wrongProtocol, identity))
    }

    @Test
    fun `current chrootctl 1_3_3 module 18 remains compatible without raising minimum`() {
        val identity = ChrootctlRuntimeResolver.PackageIdentity(
            moduleVersion = "1.3.3",
            moduleVersionCode = 18,
            backendVersion = "0.11.0",
            bridgeProtocolVersion = 1,
            runtimeSchemaVersion = 2,
            runtimeKind = "go-native",
            runtimeAbi = "arm64-v8a",
            runtimeEntrypoint = "chrootctl",
        )
        val document = Json.parseToJsonElement(
            """{
              "schemaVersion":3,
              "project":"chrootctl",
              "bridgeProtocolVersion":1,
              "backend":{"implementation":"go","version":"0.11.0"},
              "nativeRuntime":{"schemaVersion":2,"kind":"go-native","platform":"android","abi":"arm64-v8a","entrypoint":"chrootctl"},
              "module":{"id":"chrootctl","version":"1.3.3","versionCode":18,"minimumSupportedBridgeProtocolVersion":1}
            }""",
        ).jsonObject
        assertTrue(ChrootctlRuntimeResolver.validateExternalCompatibilityDocument(document, identity))
        val source = File("src/main/java/com/mikeyphw/bkx/android/root/ChrootctlRuntimeResolver.kt").readText()
        assertTrue("MIN_MODULE_VERSION_CODE = 15" in source)
    }

    @Test
    fun `capability validation is bounded to the fixed protocol and fails malformed output without throwing`() {
        val identity = ChrootctlRuntimeResolver.PackageIdentity(
            moduleVersion = "1.3.0",
            moduleVersionCode = 15,
            backendVersion = "0.11.0",
            bridgeProtocolVersion = 1,
            runtimeSchemaVersion = 2,
            runtimeKind = "go-native",
            runtimeAbi = "arm64-v8a",
            runtimeEntrypoint = "chrootctl",
        )
        val malformed = ChrootctlRuntimeResolver.validateCapabilitiesResponse("{broken", 0, identity)
        assertFalse(malformed.compatible)
        assertTrue(malformed.detail.startsWith("probe failed:"))

        val required = listOf(
            "chroots.list", "chroots.get", "transfer.artifact_root", "transfer.inspect_import",
            "transfer.import_preview", "transfer.preview_export", "transfer.import", "transfer.export",
            "artifacts.list", "operations.get", "operations.find_request",
        ).joinToString(",") { "{\"name\":\"$it\"}" }
        val valid = """{"protocolVersion":1,"ok":true,"data":{"protocolVersion":1,"backendVersion":"0.11.0","arbitraryShell":false,"operations":[$required]}}"""
        val accepted = ChrootctlRuntimeResolver.validateCapabilitiesResponse(valid, 0, identity)
        assertTrue(accepted.compatible)
        assertEquals("0.11.0", accepted.backendVersion)

        val unsafe = valid.replace("\"arbitraryShell\":false", "\"arbitraryShell\":true")
        val rejected = ChrootctlRuntimeResolver.validateCapabilitiesResponse(unsafe, 0, identity)
        assertFalse(rejected.compatible)
        assertTrue(rejected.detail.startsWith("incompatible:"))
    }

    @Test
    fun `resolver source never promotes modules_update into an executable candidate`() {
        val source = File("src/main/java/com/mikeyphw/bkx/android/root/ChrootctlRuntimeResolver.kt").readText()
        assertTrue("modules_update/chrootctl-magisk" in source)
        assertTrue("pendingModuleDirs" in source)
        assertFalse("su -c" in source)
        assertFalse("/system/bin/sh" in source)
        assertTrue("ProcessBuilder(executable.path, \"app\", \"capabilities\")" in source)
        assertTrue("Pending modules_update trees are diagnostics only" in source)
    }
}
