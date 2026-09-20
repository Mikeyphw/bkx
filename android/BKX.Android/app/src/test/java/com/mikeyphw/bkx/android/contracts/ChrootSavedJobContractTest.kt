package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPlan
import com.mikeyphw.bkx.android.core.jobs.ChrootctlSavedJobContract
import com.mikeyphw.bkx.android.core.jobs.OperationSpecV2
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChrootSavedJobContractTest {
    @Test fun exportRoundTripsNestedRemoteDestination() {
        val plan = ChrootctlExportPlan("androidos", BackupDestinationSelection("remote:gdrive", "Google Drive", "rclone://gdrive/BKX/Chroots/androidos"), true)
        val decoded = ChrootctlSavedJobContract.exportPlan(ChrootctlSavedJobContract.exportSpec(plan))
        assertEquals(plan, decoded)
    }

    @Test fun importsAreNeverSchedulable() {
        val spec = OperationSpecV2(operation = "chrootctl.import", parameters = buildJsonObject { })
        assertFailsWith<IllegalArgumentException> { ChrootctlSavedJobContract.requireSchedulable(spec) }
    }
}
