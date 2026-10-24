package com.mikeyphw.bkx.android.embedded

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoNativeRuntimeInstrumentationTest {
    @Test
    fun packagedGoRuntimeSpeaksProtocolV1OnSupportedAbi() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = GoNativeGateway(context)
        val extractedGo = java.io.File(context.applicationInfo.nativeLibraryDir, "libbkxgo.so")
        assertTrue("Android did not extract libbkxgo.so into nativeLibraryDir: ${context.applicationInfo.nativeLibraryDir}", extractedGo.isFile)
        assertTrue("Extracted Go runtime is not executable", extractedGo.canExecute())
        assertTrue("Generated Go runtime must be packaged on this supported ABI", gateway.isAvailable())

        val readiness = Json.parseToJsonElement(gateway.readinessJson()).jsonObject
        assertEquals(BridgeJson.SUPPORTED_PROTOCOL_VERSION, readiness.getValue("protocol_version").jsonPrimitive.content.toInt())
        assertEquals("go-native-android", readiness.getValue("runtime").jsonPrimitive.content)
        assertEquals("none", readiness.getValue("python_version").jsonPrimitive.content)

        val requestId = "go12-device-storage"
        val result = BridgeJson.parseFinalResult(
            gateway.executeJson(
                """{"protocol_version":1,"request_id":"$requestId","operation":"storage.locations","payload":{}}""",
            ),
        )
        assertEquals(requestId, result.requestId)
        assertEquals("success", result.status.name.lowercase())
        val progress = BridgeJson.parseProgress(gateway.progressJson(requestId))
        assertEquals(requestId, progress?.requestId)
        assertEquals("success", progress?.phase)

        val cancelledId = "go12-device-cancelled"
        BridgeJson.parseFinalResult(gateway.cancelJson(cancelledId))
        val cancelled = BridgeJson.parseFinalResult(
            gateway.executeJson(
                """{"protocol_version":1,"request_id":"$cancelledId","operation":"storage.locations","payload":{}}""",
            ),
        )
        assertEquals("cancelled", cancelled.status.name.lowercase())
    }
}
