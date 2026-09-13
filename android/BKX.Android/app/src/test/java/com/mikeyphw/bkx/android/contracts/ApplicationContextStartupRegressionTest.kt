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

    private val goGatewaySource = File(
        "src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt",
    ).readText()

    private val storesSource = File(
        "src/main/java/com/mikeyphw/bkx/android/app/store/AndroidJsonStores.kt",
    ).readText()

    private val applicationSource = File(
        "src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt",
    ).readText()

    @Test
    fun backendGraphFallsBackWhenApplicationContextIsUnavailable() {
        assertTrue(graphSource.contains("applicationContext ?: this"))
        assertFalse(graphSource.contains("context.applicationContext"))
    }

    @Test
    fun rootServiceUsesAppOwnedGoWithoutPythonBootstrap() {
        assertTrue(rootServiceSource.contains("GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)"))
        assertTrue(rootServiceSource.contains("Os.getuid() == ROOT_UID"))
        assertFalse(rootServiceSource.contains("AndroidPlatform("))
        assertFalse(rootServiceSource.contains("getModule(\"bkx.android_api\")"))
    }

    @Test
    fun localGoGatewayFallsBackToSuppliedContext() {
        assertTrue(goGatewaySource.contains("context.applicationContext ?: context"))
    }

    @Test
    fun androidApplicationHasNoEmbeddedPythonStartupPath() {
        assertFalse(applicationSource.contains("Python.start("))
        assertFalse(graphSource.contains("ChaquopyPythonGateway"))
        assertFalse(graphSource.contains("NativePreferredGateway"))
    }

    @Test
    fun androidApplicationStartupIsSideEffectLight() {
        assertTrue(applicationSource.contains("applicationRuntime(this).markApplicationCreated()"))
        assertFalse(applicationSource.contains("recoverInterruptedOperations()"))
        assertFalse(applicationSource.contains("AutomationDispatcher.recoverPending(this)"))
        assertFalse(applicationSource.contains("BkxSavedJobScheduler.reconcile(this)"))
    }

    @Test
    fun sharedPreferencesStoresDoNotDereferenceApplicationContext() {
        assertFalse(storesSource.contains("applicationContext.getSharedPreferences"))
        assertEquals(3, Regex("context\\.getSharedPreferences\\(").findAll(storesSource).count())
    }
}
