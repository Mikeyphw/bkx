package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApplicationContextStartupRegressionTest {
    private val graphSource = File(
        "src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt",
    ).readText()

    private val rootServiceSource = File(
        "src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt",
    ).readText()

    private val gatewaySource = File(
        "src/main/java/com/mikeyphw/bkx/android/embedded/ChaquopyPythonGateway.kt",
    ).readText()

    private val storesSource = File(
        "src/main/java/com/mikeyphw/bkx/android/app/store/AndroidJsonStores.kt",
    ).readText()

    private val applicationSource = File(
        "src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt",
    ).readText()

    @Test
    fun backendGraphFallsBackWhenApplicationContextIsUnavailable() {
        assertTrue(
            graphSource.contains("applicationContext ?: this"),
        )
        assertFalse(
            graphSource.contains("context.applicationContext"),
        )
    }

    @Test
    fun rootServiceUsesAppOwnedGoWithoutChaquopyBootstrap() {
        assertTrue(rootServiceSource.contains("GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)"))
        assertTrue(rootServiceSource.contains("Os.getuid() == ROOT_UID"))
        assertFalse(rootServiceSource.contains("AndroidPlatform("))
        assertFalse(rootServiceSource.contains("getModule(\"bkx.android_api\")"))
    }

    @Test
    fun localGatewayFallsBackToSuppliedContext() {
        assertTrue(
            gatewaySource.contains(
                "context.applicationContext ?: context",
            ),
        )
    }

    @Test
    fun pythonRollbackRuntimeStartsLazilyAfterGoCutover() {
        assertFalse(applicationSource.contains("Python.start("))
        assertTrue(gatewaySource.contains("Python.start(AndroidPlatform(appContext))"))
    }

    @Test
    fun sharedPreferencesStoresDoNotDereferenceApplicationContext() {
        assertFalse(
            storesSource.contains(
                "applicationContext.getSharedPreferences",
            ),
        )

        assertEquals(
            3,
            Regex(
                "context\\.getSharedPreferences\\(",
            ).findAll(storesSource).count(),
        )
    }
}
