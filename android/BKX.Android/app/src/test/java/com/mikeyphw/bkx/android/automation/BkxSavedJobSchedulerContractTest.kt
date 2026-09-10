package com.mikeyphw.bkx.android.automation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxSavedJobSchedulerContractTest {
    @Test
    fun scheduleWireModelUsesSharedGoSnakeCaseSchema() {
        val schedule = BkxSavedJobSchedule(
            scheduleId = "nightly",
            jobId = "phone-backup",
            intervalMinutes = 60,
            requireCharging = true,
            network = "unmetered",
        )
        val encoded = Json.encodeToString(BkxSavedJobSchedule.serializer(), schedule)
        assertTrue(encoded.contains("\"schedule_id\":\"nightly\""))
        assertTrue(encoded.contains("\"job_id\":\"phone-backup\""))
        assertTrue(encoded.contains("\"interval_minutes\":60"))
        assertTrue(encoded.contains("\"require_charging\":true"))
    }

    @Test
    fun scheduleSignatureChangesOnlyWhenExecutionConstraintsChange() {
        val base = BkxSavedJobSchedule(scheduleId = "nightly", jobId = "phone-backup", intervalMinutes = 60, network = "none")
        assertEquals(BkxSavedJobScheduler.scheduleSignature(base), BkxSavedJobScheduler.scheduleSignature(base.copy()))
        assertTrue(BkxSavedJobScheduler.scheduleSignature(base) != BkxSavedJobScheduler.scheduleSignature(base.copy(network = "unmetered")))
        assertTrue(BkxSavedJobScheduler.scheduleSignature(base) != BkxSavedJobScheduler.scheduleSignature(base.copy(intervalMinutes = 120)))
    }

    @Test
    fun schedulerIdsAreStableAndCadenceFloorMatchesAndroid() {
        assertEquals(BkxSavedJobScheduler.jobSchedulerId("nightly"), BkxSavedJobScheduler.jobSchedulerId("nightly"))
        assertEquals(15L, BkxSavedJobScheduler.MIN_INTERVAL_MINUTES)
    }
}
