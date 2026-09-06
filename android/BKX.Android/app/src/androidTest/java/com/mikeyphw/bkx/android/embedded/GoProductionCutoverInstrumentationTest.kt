package com.mikeyphw.bkx.android.embedded

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device proof that the Android local runtime is Go-only on every packaged ABI. */
@RunWith(AndroidJUnit4::class)
class GoProductionCutoverInstrumentationTest {
    @Test
    fun productionLocalRouteUsesPackagedGoProtocolRuntime() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val production = GoNativeGateway(context)
        assertTrue("Packaged Go runtime must be extracted and executable.", production.isAvailable())

        val readiness = BridgeJson.json.parseToJsonElement(production.readinessJson()).jsonObject
        assertEquals("go-native-android", readiness["runtime"]?.jsonPrimitive?.content)
        assertEquals("1", readiness["protocol_version"]?.jsonPrimitive?.content)

        val requestId = "go14-storage-${System.currentTimeMillis()}"
        val request = buildJsonObject {
            put("protocol_version", 1)
            put("request_id", requestId)
            put("operation", "storage.locations")
            put("payload", buildJsonObject {})
        }.toString()
        val result = BridgeJson.json.parseToJsonElement(production.executeJson(request)).jsonObject
        assertEquals(requestId, result["request_id"]?.jsonPrimitive?.content)
        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        val progress = BridgeJson.json.parseToJsonElement(production.progressJson(requestId)).jsonObject
        assertEquals(requestId, progress["request_id"]?.jsonPrimitive?.content)
        assertEquals("success", progress["phase"]?.jsonPrimitive?.content)
        production.close()
    }
}
