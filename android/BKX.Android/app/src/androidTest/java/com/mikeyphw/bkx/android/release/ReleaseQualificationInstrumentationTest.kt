package com.mikeyphw.bkx.android.release

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeFileLayout
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import com.mikeyphw.bkx.android.storage.AndroidStorageAccessManager
import com.mikeyphw.bkx.android.storage.BkxStorageLocations
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device qualification.
 *
 * Normal connectedDebugAndroidTest runs skip this test. The explicit release
 * qualification script enables it only after the user has granted All files
 * access and root in their root manager.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseQualificationInstrumentationTest {
    @Test
    fun rootedPersonalBuildCanReadTermuxPrivateStorageAndStillHasLocalFallback() = runBlocking {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(ARG_DEVICE_QUALIFICATION)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue("Explicit BKX device qualification was not requested.", enabled)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(
            "All files access must be enabled before release qualification.",
            AndroidStorageAccessManager(context).hasAllFilesAccess(),
        )

        val backend = DefaultBackendGraph.backendPort(context)
        val rooted = backend.checkReadiness()
        assertTrue("Release qualification requires an active libsu root grant: $rooted", rooted is BackendReadiness.Ready && rooted.isRoot)
        assertTrue("Privileged production route must be Go-native: $rooted", (rooted as BackendReadiness.Ready).runtime == "go-native-android")

        val requestId = "device-termux-stat-${System.currentTimeMillis()}"
        val request = BridgeRequestEnvelope(
            requestId = requestId,
            operation = "filesystem.stat",
            payload = buildJsonObject { put("path", BkxStorageLocations.TERMUX_FILES) },
        )
        val base = File(context.cacheDir, "bkx-device-qualification").absolutePath
        val files = BridgeFileLayout.forRequest(base, requestId)
        val submission = backend.submit(request, files)
        assertTrue("Root BKX did not accept Termux-private filesystem.stat: ${submission.readiness}", submission.accepted)
        val result = backend.readFinalResult(File(files.resultFilePath).readText())
        assertTrue("Root BKX could not stat Termux private storage: $result", result.status == BridgeResultStatus.Success)
        assertTrue(result.artifacts.any { it.path == BkxStorageLocations.TERMUX_FILES })

        val settings = DefaultBackendGraph.settingsStore(context)
        val original = settings.read()
        try {
            settings.update(original.copy(rootAccessMode = RootAccessMode.Disabled))
            val local = backend.checkReadiness()
            assertTrue("Embedded non-root fallback must remain usable: $local", local is BackendReadiness.Ready)
            assertFalse("Disabled root mode must route the next readiness call locally.", (local as BackendReadiness.Ready).isRoot)
            assertTrue("Local production route must be Go-native on the qualified ARM64 device: $local", local.runtime == "go-native-android")
        } finally {
            settings.update(original)
        }
    }

    private companion object {
        const val ARG_DEVICE_QUALIFICATION = "bkxDeviceQualification"
    }
}
