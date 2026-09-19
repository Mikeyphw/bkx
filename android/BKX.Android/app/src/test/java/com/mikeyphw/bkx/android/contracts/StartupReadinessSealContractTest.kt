package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupReadinessSealContractTest {
    private val appRoot = locateAppRoot()
    private val repositoryRoot = locateRepositoryRoot(appRoot)
    private val mainRoot = File(appRoot, "src/main/java/com/mikeyphw/bkx/android")
    private val runtime = File(mainRoot, "app/runtime/BkxApplicationRuntime.kt").readText()
    private val graph = File(mainRoot, "app/di/DefaultBackendGraph.kt").readText()
    private val app = File(mainRoot, "app/BkxAndroidApplication.kt").readText()
    private val viewModel = File(mainRoot, "app/BkxAppViewModel.kt").readText()
    private val scheduler = File(mainRoot, "automation/BkxSavedJobScheduler.kt").readText()
    private val receiver = File(mainRoot, "automation/BkxAutomationRecoveryReceiver.kt").readText()
    private val resolver = File(mainRoot, "core/state/BkxControlPlaneRootResolver.kt").readText()
    private val gateway = File(mainRoot, "embedded/GoNativeGateway.kt").readText()
    private val verification = File(repositoryRoot, "build-logic/android-verification.gradle.kts").readText()

    private fun locateAppRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Unable to locate BKX Android app root from ${File(".").canonicalPath}")

    private fun locateRepositoryRoot(appRoot: File): File =
        generateSequence(appRoot.canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "go.mod").isFile && File(it, "build-logic/android-verification.gradle.kts").isFile }
            ?: error("Unable to locate BKX repository root from ${appRoot.canonicalPath}")

    @Test
    fun applicationOnlyInstallsRuntimeOwner() {
        assertTrue(app.contains("applicationRuntime(this).markApplicationCreated()"))
        assertFalse(app.contains("recoverInterruptedOperations()"))
        assertFalse(app.contains("recoverPending(this)"))
        assertFalse(app.contains("reconcile(this)"))
    }

    @Test
    fun startupRuntimeProvidesSuspendableRecoveryBarrier() {
        assertTrue(runtime.contains("class BkxApplicationRuntime"))
        assertTrue(runtime.contains("Mutex()"))
        assertTrue(runtime.contains("awaitReadyForExecution"))
        assertTrue(runtime.contains("initializeAndRecoverInterruptedOperations"))
        assertFalse(File(mainRoot, "app/runtime/BkxOperationCoordinator.kt").readText().contains("init {\n        scope.launch"))
        assertTrue(runtime.contains("AutomationDispatcher.recoverPending"))
        assertTrue(runtime.contains("BkxSavedJobScheduler.reconcile"))
        assertTrue(runtime.contains("BkxApplicationRuntimePhase.Ready"))
        assertTrue(graph.contains("applicationRuntimeInstance"))
        assertTrue(graph.contains("resetForTests"))
    }

    @Test
    fun authorizedIngressSurfacesUseStartupBarrier() {
        assertTrue(receiver.contains("goAsync()"))
        assertTrue(receiver.contains("BkxStartupIngress.RecoveryBroadcast"))
        assertTrue(scheduler.contains("awaitReadyForExecution(BkxStartupIngress.ScheduledJob)"))
        assertTrue(File(mainRoot, "automation/BkxAutomationService.kt").readText().contains("BkxStartupIngress.AuthorizedAutomation"))
        assertTrue(File(mainRoot, "app/service/BkxOperationForegroundService.kt").readText().contains("BkxStartupIngress.ForegroundOperation"))
    }

    @Test
    fun controlPlaneResolverIsSharedAndBounded() {
        assertTrue(resolver.contains("MAX_CONTROL_PLANE_DOCUMENT_BYTES"))
        assertTrue(resolver.contains("MAX_CONTROL_PLANE_ITEMS"))
        assertTrue(resolver.contains("markerIsValid"))
        assertFalse(scheduler.contains("BqxControlPlaneRootResolver"))
        assertTrue(scheduler.contains("BkxControlPlaneRootResolver.resolveDocument"))
        assertTrue(scheduler.contains("readBoundedDocument"))
        assertTrue(scheduler.contains("schemaVersion == 1"))
        assertTrue(gateway.contains("BkxControlPlaneRootResolver.resolve(appContext, stateNamespace)"))
        assertTrue(gateway.contains("runtimeExitError(started, stderrLog)"))
        assertTrue(gateway.contains("Runtime stderr:"))
        assertTrue(gateway.contains("exit code "))
        assertTrue(gateway.contains("stderr.writeText(\"\", Charsets.UTF_8)"))
    }

    @Test
    fun startupHydratesFeatureCatalogsLazilyAndReadinessIsGenerationBound() {
        assertTrue(viewModel.contains("readinessSequence"))
        assertTrue(viewModel.contains("generation != readinessSequence.get()"))
        assertTrue(viewModel.contains("hydrateDestination"))
        assertTrue(viewModel.contains("hydrateBackupRoute"))
        assertTrue(viewModel.contains("hydrateMoreRoute"))
        val initSection = viewModel.substringAfter("init {").substringBefore("fun onEvent")
        assertFalse(initSection.contains("refreshSavedJobs()"))
        assertFalse(initSection.contains("refreshSavedJobSchedules()"))
        assertFalse(initSection.contains("refreshRepositories()"))
    }

    @Test
    fun overlayGateTaskIsRegistered() {
        assertTrue(verification.contains("verifyBkxStartupAppGraphReadinessSeal"))
        assertTrue(verification.contains("StartupReadinessSealContractTest"))
    }
}
