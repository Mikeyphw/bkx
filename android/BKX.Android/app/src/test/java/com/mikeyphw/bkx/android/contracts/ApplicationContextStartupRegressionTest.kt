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
    fun rootServiceProvidesChaquopyARealApplicationContext() {
        assertFalse(
            rootServiceSource.contains(
                "AndroidPlatform(applicationContext)",
            ),
        )
        assertTrue(
            rootServiceSource.contains(
                "AndroidPlatform(rootPythonApplication)",
            ),
        )
        assertTrue(
            rootServiceSource.contains(
                "RootChaquopyApplication(baseContext)",
            ),
        )
        assertTrue(
            rootServiceSource.contains(
                "override fun getApplicationContext(): Context = this",
            ),
        )
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
