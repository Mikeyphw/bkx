package com.mikeyphw.bkx.android.core.chrootctl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlReadinessCodecTest {
    @Test
    fun `ready report round trips without losing trust evidence`() {
        val expected = ChrootctlReadiness(
            code = ChrootctlReadinessCode.Ready,
            detail = "ready",
            selectedPath = "/data/adb/modules/chrootctl-magisk/system/bin/chrootctl",
            selectedSource = "module-launcher",
            activeModulePath = "/data/adb/modules/chrootctl-magisk",
            moduleInstalled = true,
            packageSealReady = true,
            runtimeSealReady = true,
            pendingUpdate = true,
            pendingUpdatePaths = listOf("/data/adb/modules_update/chrootctl-magisk"),
            rootProvider = "KernelSU",
            abi = "arm64-v8a",
            moduleVersion = "1.3.0",
            moduleVersionCode = 15,
            backendVersion = "0.11.0",
            bridgeProtocolVersion = 1,
            operations = listOf("chroots.list", "transfer.export"),
            candidates = listOf(
                ChrootctlBinaryCandidate(
                    path = "/data/adb/modules/chrootctl-magisk/system/bin/chrootctl",
                    source = "module-launcher",
                    exists = true,
                    executable = true,
                    trusted = true,
                    compatible = true,
                    exitCode = 0,
                    detail = "protocol-1 bridge ready",
                    uid = 0,
                    gid = 0,
                    mode = 0x81ed,
                ),
            ),
        )

        val actual = ChrootctlReadinessCodec.decode(ChrootctlReadinessCodec.encode(expected))

        assertEquals(expected, actual)
        assertTrue(actual.ready)
    }

    @Test
    fun `root disabled is distinct from missing root authorization`() {
        assertEquals(ChrootctlReadinessCode.RootDisabled, ChrootctlReadiness.rootDisabled().code)
        assertEquals(ChrootctlReadinessCode.RootUnavailable, ChrootctlReadiness.rootUnavailable().code)
    }
}
