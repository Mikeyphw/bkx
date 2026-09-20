package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlExportHandoffContractTest {
    private val main = File("src/main")

    @Test
    fun `root AIDL adds one fixed managed export handoff and no arbitrary root file API`() {
        val aidl = File(main, "aidl/com/mikeyphw/bkx/android/root/IBkxRootEngineService.aidl").readText()
        assertTrue("String chrootctlCopyManagedExportArtifact(String copyId, String path, in ParcelFileDescriptor output);" in aidl)
        assertFalse("readRootFile" in aidl)
        assertFalse("copyRootPath" in aidl)
        assertFalse("shellCommand" in aidl)
        assertFalse("executablePath" in aidl)
    }

    @Test
    fun `root handoff verifies chrootctl registry membership and chroot export kind`() {
        val service = File(main, "java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt").readText()
        val policy = File(main, "java/com/mikeyphw/bkx/android/root/ChrootctlManagedArtifactPolicy.kt").readText()
        assertTrue(".put(\"operation\", \"artifacts.list\")" in service)
        assertTrue("listOf(\"app\", \"query\", \"--request-file\", \"-\")" in service)
        assertTrue("EXPORT_KIND = \"chroot-export\"" in policy)
        assertTrue("Requested export is not registered as a managed chrootctl artifact." in policy)
        assertTrue("Managed chrootctl export size changed after registry inspection." in policy)
    }

    @Test
    fun `handoff stages under external files and verifies chrootctl checksum before publication`() {
        val handoff = File(main, "java/com/mikeyphw/bkx/android/root/ChrootctlArtifactHandoff.kt").readText()
        assertTrue("getExternalFilesDir(null)?.resolve(\"bkx/chrootctl-staging\")" in handoff)
        assertTrue("DigestOutputStream" in handoff)
        assertTrue("actualChecksum.equals(artifact.checksumSha256" in handoff)
        assertTrue("partial.renameTo(target)" in handoff)
        assertFalse("filesDir.resolve(\"bkx/chrootctl-staging\")" in handoff)
    }

    @Test
    fun `coordinator keeps chrootctl local and publishes through BKX transfer plan run`() {
        val coordinator = File(main, "java/com/mikeyphw/bkx/android/app/chrootctl/ChrootctlExportCoordinator.kt").readText()
        assertTrue("ChrootctlBridgeCodec.exportPayload(plan.chrootName)" in coordinator)
        assertTrue("transferRequests.buildPreviewRequest" in coordinator)
        assertTrue("transferRequests.buildRunRequest" in coordinator)
        assertTrue("verifyAfterTransfer = true" in coordinator)
        assertTrue("checksum = staged.checksumSha256" in coordinator)
        assertTrue("recordInHistory = true" in coordinator)
        assertFalse("rclone://" in coordinator)
        assertFalse("CHROOTCTL_RCLONE_CONFIG" in coordinator)
    }

    @Test
    fun `cancelling the bridge stream explicitly cancels the persistent chrootctl operation`() {
        val bridge = File(main, "java/com/mikeyphw/bkx/android/root/RootChrootctlBridgePort.kt").readText()
        assertTrue("catch (cancelled: CancellationException)" in bridge)
        assertTrue("operationId ?: operationLedger.operationId(request.requestId)" in bridge)
        assertTrue("withContext(NonCancellable)" in bridge)
        assertTrue("rootClient.chrootctlCancelOperationJson(id)" in bridge)
    }

    @Test
    fun `publication cleanup occurs only after successful BKX transfer and failures retain staging`() {
        val coordinator = File(main, "java/com/mikeyphw/bkx/android/app/chrootctl/ChrootctlExportCoordinator.kt").readText()
        val successCheck = coordinator.indexOf("if (transferResult.status != BridgeResultStatus.Success)")
        val cleanup = coordinator.indexOf("handoff.cleanup(staged)")
        assertTrue(successCheck >= 0)
        assertTrue(cleanup > successCheck)
        assertTrue("The staged artifact was retained for recovery." in coordinator)
        assertTrue("retainedBkxStagingPath" in coordinator)
    }
}
