package com.mikeyphw.bkx.android.core.jobs

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedJobScheduleContractTest {
    @Test
    fun scheduleRequestUsesSharedServiceWireContract() {
        val schedule = SavedJobScheduleDefinition(
            scheduleId = "nightly",
            jobId = "phone-backup",
            intervalMinutes = 60,
            requireCharging = true,
            network = "unmetered",
        )
        val request = SavedJobScheduleRequestBuilder.put("schedule-1", schedule)
        assertEquals("schedules.put", request.operation)
        assertEquals("nightly", request.payload["schedule_id"]?.toString()?.trim('"'))
        assertEquals("phone-backup", request.payload["job_id"]?.toString()?.trim('"'))
        assertEquals("60", request.payload["interval_minutes"]?.toString())
        assertEquals("true", request.payload["require_charging"]?.toString())
    }

    @Test
    fun scheduleDefaultsToNoNetworkConstraint() {
        val schedule = SavedJobScheduleDefinition(
            scheduleId = "local",
            jobId = "local-backup",
            intervalMinutes = 30,
        )
        assertEquals("none", schedule.network)
    }

    @Test
    fun scheduleArtifactRoundTrips() {
        val raw = """{"schema_version":1,"schedule_id":"nightly","job_id":"phone-backup","enabled":true,"interval_minutes":60,"require_charging":false,"network":"any","created_at":"2026-09-10T00:00:00Z","updated_at":"2026-09-10T00:00:00Z"}"""
        val decoded = SavedJobScheduleBridgeCodec.decodeArtifacts(
            listOf(BridgeArtifact(label = "saved_job_schedule", path = "nightly", metadata = mapOf("schedule_json" to raw))),
        )
        assertEquals(1, decoded.size)
        assertEquals("nightly", decoded.single().scheduleId)
        assertTrue(decoded.single().enabled)
    }
}
