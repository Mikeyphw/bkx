package com.mikeyphw.bkx.android.embedded

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.root.RootBkxServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionGoCutoverInstrumentationTest {
    @Test
    fun productionBackendReportsGoNativeAndroidOnArm64() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val readiness = DefaultBackendGraph.backendPort(context).checkReadiness()
        assertTrue(readiness is BackendReadiness.Ready)
        readiness as BackendReadiness.Ready
        assertEquals("go-native-android", readiness.runtime)
    }

    @Test
    fun privilegedRouteUsesSameGoRuntimeWhenRootIsGranted() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = RootBkxServiceClient(context)
        assumeTrue("Root manager did not grant BKX; root Go lane skipped", root.rootAvailable())

        val readiness = Json.parseToJsonElement(root.readinessJson()).jsonObject
        assertEquals("go-native-android", readiness.getValue("runtime").jsonPrimitive.content)
        val capabilities = readiness.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("root.filesystem" in capabilities)
        assertTrue("storage.termux.private" in capabilities)

        val result = Json.parseToJsonElement(
            root.executeJson(
                """{"protocol_version":1,"request_id":"go13-root-storage","operation":"storage.locations","payload":{}}""",
            ),
        ).jsonObject
        assertEquals("success", result.getValue("status").jsonPrimitive.content)
        root.release()
    }
}
