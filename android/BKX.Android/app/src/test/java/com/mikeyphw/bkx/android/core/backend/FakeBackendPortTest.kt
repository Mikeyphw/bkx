package com.mikeyphw.bkx.android.core.backend

import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBackendPortTest {
    @Test
    fun readinessIsStructuredAndNotBoolean() = runTest {
        val backend = FakeBackendPort()
        val readiness = backend.checkReadiness()

        assertTrue(readiness is BackendReadiness.Ready)
        assertEquals("ready", readiness.code)
        assertEquals(emptyList<String>(), readiness.remediationSteps)
    }

    @Test
    fun readinessCanRepresentRunCommandPermissionFailure() = runTest {
        val backend = FakeBackendPort(BackendReadiness.RunCommandPermissionMissing)
        val readiness = backend.checkReadiness()

        assertEquals("run_command_permission_missing", readiness.code)
        assertTrue(readiness.remediationSteps.any { it.contains("Run commands in Termux") })
    }

    @Test
    fun fakeFinalResultUsesBridgeParser() = runTest {
        val backend = FakeBackendPort()
        val result = backend.readFinalResult(
            """
            {"protocol_version":${BridgeJson.SUPPORTED_PROTOCOL_VERSION},"request_id":"req-1","status":"success","summary":"ok"}
            """.trimIndent(),
        )

        assertEquals("req-1", result.requestId)
    }
}
