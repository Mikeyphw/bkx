package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedRunHistoryContractTest {
    private val gateway = File(
        "src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt",
    ).readText()

    @Test
    fun embeddedRuntimeUsesCanonicalSharedControlPlaneForRunHistory() {
        assertTrue(gateway.contains("desiredStateRoot.resolve(\"runs\").resolve(\"runs.jsonl\")"))
        assertTrue(gateway.contains("builder.environment()[\"BKX_LEGACY_RUN_HISTORY\"] = legacyRunHistory.absolutePath"))
        assertTrue(gateway.contains("builder.environment()[BkxSharedStateContract.ENVIRONMENT] = desiredStateRoot.absolutePath"))
    }

}
