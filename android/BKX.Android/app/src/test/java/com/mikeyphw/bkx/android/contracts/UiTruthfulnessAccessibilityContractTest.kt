package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.BkxProductAction
import com.mikeyphw.bkx.android.app.actionGate
import com.mikeyphw.bkx.android.app.uiTruthSurface
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.drive.GoogleDriveConnectionStatus
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRemoteReadiness
import com.mikeyphw.bkx.android.core.drive.GoogleDriveUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiTruthfulnessAccessibilityContractTest {
    private val appRoot = locateAppRoot()
    private val repositoryRoot = locateRepositoryRoot(appRoot)
    private val mainRoot = File(appRoot, "src/main/java/com/mikeyphw/bkx/android")
    private val resources = File(appRoot, "src/main/res/values/bkx_product_copy.xml").readText()
    private val components = File(mainRoot, "app/ui/components/BkxComponents.kt").readText()
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
    fun readyCopyRequiresVerifiedBackendReadiness() {
        val checking = BkxHomeUiState(readiness = BackendReadiness.Checking)
        assertFalse(checking.uiTruthSurface().engine.verified)
        assertFalse(checking.actionGate(BkxProductAction.StartBackup).allowed)

        val ready = BkxHomeUiState(
            readiness = BackendReadiness.Ready(
                backendVersion = "1.0.0",
                bridgeProtocolVersion = 1,
                capabilities = setOf("backup.create"),
            ),
        )
        assertTrue(ready.uiTruthSurface().engine.verified)
        assertTrue(ready.actionGate(BkxProductAction.StartBackup).allowed)
    }

    @Test
    fun driveConnectedLabelRequiresManagedRemoteReadiness() {
        val authorizedButNoRemote = BkxHomeUiState(
            googleDrive = GoogleDriveUiState(
                status = GoogleDriveConnectionStatus.Connected,
                remoteReadiness = GoogleDriveRemoteReadiness.MissingManagedRemote,
            ),
        )
        assertFalse(authorizedButNoRemote.googleDrive.connected)
        assertFalse(authorizedButNoRemote.uiTruthSurface().googleDrive.verified)
        assertTrue(authorizedButNoRemote.uiTruthSurface().googleDrive.badge == "ATTENTION")
    }

    @Test
    fun productCopyIsResourceBackedForAuditedTruthAndAccessibilityMessages() {
        listOf(
            "bkx_truth_engine_verified",
            "bkx_truth_drive_verified",
            "bkx_gate_backend_unverified",
            "bkx_gate_operation_busy",
            "bkx_a11y_progress_updated",
        ).forEach { token -> assertTrue("Missing localized copy token $token", resources.contains(token)) }
    }

    @Test
    fun sharedComponentsExposeAccessibilitySemanticsAndLiveRegions() {
        assertTrue(components.contains("heading()"))
        assertTrue(components.contains("LiveRegionMode.Polite"))
        assertTrue(components.contains("progressBarRangeInfo"))
        assertTrue(components.contains("stateDescription"))
        assertTrue(components.contains("contentDescription"))
    }

    @Test
    fun overlayGateTaskIsRegistered() {
        assertTrue(verification.contains("verifyBkxUiTruthfulnessAccessibilityProductCopySeal"))
        assertTrue(verification.contains("UiTruthfulnessAccessibilityContractTest"))
    }
}
