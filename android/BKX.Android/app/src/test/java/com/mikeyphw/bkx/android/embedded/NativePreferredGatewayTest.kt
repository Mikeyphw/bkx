package com.mikeyphw.bkx.android.embedded

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativePreferredGatewayTest {
    @Test
    fun enabledAvailableGoRuntimeOwnsProductionRoute() = runBlocking {
        val gateway = NativePreferredGateway(
            nativeGateway = FakeAvailableGateway(available = true, label = "go"),
            pythonFallback = FakeGateway("python"),
            goEnabledProvider = { true },
            goRequiredProvider = { true },
        )
        assertEquals("go:ready", gateway.readinessJson())
        assertEquals("go:execute", gateway.executeJson(REQUEST))
        assertEquals("go:progress", gateway.progressJson("native-route"))
        assertEquals("go:cancel", gateway.cancelJson("native-route"))
    }

    @Test
    fun arm64CutoverFailsClosedWhenPackagedGoRuntimeIsMissing() {
        val gateway = NativePreferredGateway(
            nativeGateway = FakeAvailableGateway(available = false, label = "go"),
            pythonFallback = FakeGateway("python"),
            goEnabledProvider = { true },
            goRequiredProvider = { true },
        )
        assertThrows(ProductionGoRuntimeUnavailableException::class.java) {
            runBlocking { gateway.readinessJson() }
        }
    }

    @Test
    fun nonArm64RollbackLaneMayUsePythonWhenGoIsUnavailable() = runBlocking {
        val gateway = NativePreferredGateway(
            nativeGateway = FakeAvailableGateway(available = false, label = "go"),
            pythonFallback = FakeGateway("python"),
            goEnabledProvider = { true },
            goRequiredProvider = { false },
        )
        assertEquals("python:ready", gateway.readinessJson())
    }

    @Test
    fun explicitRollbackSealDisablesGo() = runBlocking {
        val gateway = NativePreferredGateway(
            nativeGateway = FakeAvailableGateway(available = true, label = "go"),
            pythonFallback = FakeGateway("python"),
            goEnabledProvider = { false },
            goRequiredProvider = { false },
        )
        assertEquals("python:ready", gateway.readinessJson())
    }

    private open class FakeGateway(private val label: String) : EmbeddedRuntimeGateway {
        override suspend fun readinessJson(): String = "$label:ready"
        override suspend fun executeJson(requestJson: String): String = "$label:execute"
        override suspend fun progressJson(requestId: String): String = "$label:progress"
        override suspend fun cancelJson(requestId: String): String = "$label:cancel"
    }

    private class FakeAvailableGateway(
        private val available: Boolean,
        label: String,
    ) : FakeGateway(label), AvailableEmbeddedRuntimeGateway {
        override fun isAvailable(): Boolean = available
    }

    private companion object {
        const val REQUEST = """{"request_id":"native-route"}"""
    }
}
