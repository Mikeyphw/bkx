package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlBridgeTransportContractTest {
    private val main = File("src/main")

    @Test
    fun `AIDL exposes only fixed chrootctl bridge methods`() {
        val aidl = File(main, "aidl/com/mikeyphw/bkx/android/root/IBkxRootEngineService.aidl").readText()
        assertTrue("String chrootctlQueryJson(String requestJson);" in aidl)
        assertTrue("String chrootctlPreviewJson(String requestJson);" in aidl)
        assertTrue("String chrootctlStartOperation(String requestJson, in ParcelFileDescriptor output);" in aidl)
        assertTrue("String chrootctlOperationJson(String operationId);" in aidl)
        assertTrue("String chrootctlCancelOperationJson(String operationId);" in aidl)
        assertTrue("String chrootctlReconcileOperationJson(String operationId);" in aidl)
        assertFalse("shellCommand" in aidl)
        assertFalse("executablePath" in aidl)
        assertFalse("List<String> argv" in aidl)
    }

    @Test
    fun `root bridge executes only trusted binary plus fixed app subcommands`() {
        val process = File(main, "java/com/mikeyphw/bkx/android/root/ChrootctlBridgeProcess.kt").readText()
        val policy = File(main, "java/com/mikeyphw/bkx/android/root/ChrootctlRootBridgePolicy.kt").readText()
        val service = File(main, "java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt").readText()
        assertTrue("Only the fixed chrootctl app bridge may be executed." in process)
        assertTrue("ProcessBuilder(listOf(executable) + arguments)" in process)
        assertTrue("arguments.first() == \"app\"" in process)
        assertTrue("ChrootctlRuntimeResolver.resolve()" in service)
        assertTrue("listOf(\"app\", \"run\", \"--request-file\", \"-\", \"--events\", \"ndjson\")" in service)
        assertTrue("\"chroots.list\"" in policy)
        assertTrue("\"transfer.export\"" in policy)
        assertTrue("\"transfer.import\"" in policy)
        assertFalse("su -c" in process + service)
        assertFalse("/system/bin/sh" in process + service)
    }

    @Test
    fun `root bridge mirrors current import compatibility aliases without widening operations`() {
        val policy = File(main, "java/com/mikeyphw/bkx/android/root/ChrootctlRootBridgePolicy.kt").readText()
        assertTrue("\"allow_legacy_metadata\"" in policy)
        assertTrue("\"import_strategy\"" in policy)
        assertTrue("\"post_import\"" in policy)
        assertFalse("arbitraryShell" in policy)
    }

    @Test
    fun `NDJSON transport is pipe based and per-event bounded`() {
        val client = File(main, "java/com/mikeyphw/bkx/android/root/RootBkxServiceClient.kt").readText()
        assertTrue("ParcelFileDescriptor.createPipe()" in client)
        assertTrue("CHROOTCTL_MAX_EVENT_BYTES = 256 * 1024" in client)
        assertTrue("readBoundedNdjson" in client)
        assertTrue("No operation start is automatically retried" in client)
    }
    @Test
    fun `operation identity is durably recorded without persisting request payloads`() {
        val port = File(main, "java/com/mikeyphw/bkx/android/root/RootChrootctlBridgePort.kt").readText()
        val ledger = File(main, "java/com/mikeyphw/bkx/android/root/ChrootctlOperationLedger.kt").readText()
        assertTrue("operationLedger.recordStarted" in port)
        assertTrue("operationLedger.recordTerminal" in port)
        assertTrue("getSharedPreferences" in ledger)
        assertFalse("password" in ledger)
        assertFalse("requestJson" in ledger)
        assertFalse("payload" in ledger)
    }

}
