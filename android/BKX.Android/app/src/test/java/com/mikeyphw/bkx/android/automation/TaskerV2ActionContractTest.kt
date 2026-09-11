package com.mikeyphw.bkx.android.automation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerV2ActionContractTest {
    @Test
    fun savedJobAndSnapshotBuildersUseSharedBridgeOperations() {
        val job = TaskerV2RequestBuilder.runSavedJob(
            "run-1", "phone-backup", "phone-drive", listOf("verify=false", "tags=[\"night\"]"),
        )
        assertEquals("jobs.run", job.operation)
        assertEquals("phone-backup", (job.payload["job_id"] as JsonPrimitive).content)
        assertEquals("phone-drive", (job.payload["repository_ref"] as JsonPrimitive).content)
        assertEquals(2, (job.payload["overrides"] as JsonArray).size)

        val snapshot = TaskerV2RequestBuilder.createSnapshot(
            requestId = "snapshot-1",
            repositoryId = "phone-drive",
            sources = listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Documents"),
        )
        assertEquals("backup.snapshot", snapshot.operation)
        assertEquals("phone-drive", (snapshot.payload["repository_ref"] as JsonPrimitive).content)
        assertEquals(2, (snapshot.payload["sources"] as JsonArray).size)
    }

    @Test
    fun destructiveMaintenanceDefaultsToPreviewAndRequiresExplicitApproval() {
        val safe = AutomationRequest(AutomationAction.RepositoryPrune, repositoryId = "phone-drive")
        assertTrue(safe.dryRun)
        assertTrue(safe.validate().isEmpty())

        val blocked = safe.copy(dryRun = false)
        assertTrue(blocked.validate().any { it.contains("confirmation", ignoreCase = true) })

        val approved = blocked.copy(confirmDestructive = true)
        assertTrue(approved.validate().isEmpty())
    }

    @Test
    fun snapshotRestoreDefaultsToNeverOverwriteAndRequiresApprovalForRealRun() {
        val preview = AutomationRequest(
            action = AutomationAction.SnapshotRestore,
            repositoryId = "phone-drive",
            snapshotId = "latest",
            destination = "/storage/emulated/0/BKX/Restores/latest",
        )
        assertEquals("never", preview.snapshotOverwrite)
        assertTrue(preview.dryRun)
        assertTrue(preview.validate().isEmpty())

        val real = preview.copy(dryRun = false)
        assertFalse(real.validate().isEmpty())
        assertTrue(real.copy(confirmDestructive = true).validate().isEmpty())
    }

    @Test
    fun savedJobOverridesAreExecutionOnlyKeyValuePairs() {
        val request = AutomationRequest(
            action = AutomationAction.RunSavedJob,
            jobId = "phone-backup",
            jobOverrides = listOf("verify=false", "tags=[\"night\"]"),
        )
        assertTrue(request.validate().isEmpty())
        assertTrue(request.copy(jobOverrides = listOf("broken")).validate().any { it.contains("KEY=VALUE") })
    }

    @Test
    fun taskerResultPublishesV2IdentityVariablesWithoutRemovingLegacyVariables() {
        val result = AutomationResult(
            requestId = "tasker-1",
            action = AutomationAction.RunSavedJob,
            status = "success",
            operationRequestId = "tasker-1-job-run",
            jobId = "phone-backup",
            runId = "tasker-1-job-run",
            repositoryId = "phone-drive",
            snapshotId = "abc123",
            filesProcessed = 42,
            bytesProcessed = 1024,
            bytesAdded = 256,
            bytesReused = 768,
            durationMillis = 987,
        )
        val values = result.taskerVariableValues()
        assertEquals("phone-backup", values["%bkx_job_id"])
        assertEquals("tasker-1-job-run", values["%bkx_run_id"])
        assertEquals("phone-drive", values["%bkx_repository_id"])
        assertEquals("abc123", values["%bkx_snapshot_id"])
        assertEquals("42", values["%bkx_files_processed"])
        assertEquals("1024", values["%bkx_bytes_processed"])
        assertEquals("256", values["%bkx_bytes_added"])
        assertEquals("768", values["%bkx_bytes_reused"])
        assertEquals("987", values["%bkx_duration_ms"])
        assertEquals("true", values["%bkx_success"])
        assertEquals("", values["%bkx_message"])
        assertTrue(AutomationResult.relevantVariables.contains("%bkx_success"))
        assertTrue(AutomationResult.relevantVariables.contains("%bkx_message"))
        assertTrue(values.containsKey("%bkx_status"))
        assertTrue(values.containsKey("%bkx_error_code"))
    }
}
