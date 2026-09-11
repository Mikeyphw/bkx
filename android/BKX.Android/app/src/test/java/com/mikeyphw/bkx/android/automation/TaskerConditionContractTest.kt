package com.mikeyphw.bkx.android.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerConditionContractTest {
    @Test
    fun progressBucketsAreCanonicalAndCadenceFiltersAreDeterministic() {
        assertEquals(25, BkxTaskerConditionMatcher.canonicalProgressBucket(0.26))
        assertEquals(95, BkxTaskerConditionMatcher.canonicalProgressBucket(0.999))
        assertEquals(100, BkxTaskerConditionMatcher.canonicalProgressBucket(1.0))
        assertTrue(BkxTaskerConditionMatcher.progressMatches("every_25", 25))
        assertFalse(BkxTaskerConditionMatcher.progressMatches("every_25", 20))
        assertTrue(BkxTaskerConditionMatcher.progressMatches("recommended", 75))
        assertFalse(BkxTaskerConditionMatcher.progressMatches("recommended", 80))
    }

    @Test
    fun eventFiltersSupportOperationRequestAndRemoteMatching() {
        val signal = signal(
            event = BkxTaskerEventType.BackupCompleted.id,
            operation = "backup.create",
            requestId = "nightly-20260909",
            remotes = listOf("google-drive"),
        )
        assertTrue(BkxTaskerConditionMatcher.eventMatches(
            BkxTaskerEventInput(
                eventType = "backup_completed",
                operation = "backup.*",
                remoteFilter = "google_drive",
                requestIdPattern = "nightly-*",
            ), signal,
        ))
        assertFalse(BkxTaskerConditionMatcher.eventMatches(
            BkxTaskerEventInput(eventType = "restore_completed"), signal,
        ))
    }


    @Test
    fun v2IdentityFiltersMatchSavedJobRepositoryAndSnapshot() {
        val signal = signal(
            event = BkxTaskerEventType.SnapshotCreated.id,
            operation = "jobs.run",
            requestId = "tasker-job-1-job-run",
            jobId = "phone-backup",
            repositoryId = "phone-drive",
            snapshotId = "a1b2c3d4",
        )
        assertTrue(BkxTaskerConditionMatcher.eventMatches(
            BkxTaskerEventInput(
                eventType = BkxTaskerEventType.SnapshotCreated.id,
                jobId = "phone-*",
                repositoryId = "*-drive",
                snapshotId = "a1b2*",
            ), signal,
        ))
        assertFalse(BkxTaskerConditionMatcher.eventMatches(
            BkxTaskerEventInput(eventType = BkxTaskerEventType.SnapshotCreated.id, repositoryId = "offsite"), signal,
        ))
    }

    @Test
    fun v2ConditionOutputPreservesRunIdentity() {
        val output = signal(
            event = BkxTaskerEventType.JobCompleted.id,
            operation = "jobs.run",
            requestId = "run-123",
            jobId = "phone-backup",
            repositoryId = "phone-drive",
            snapshotId = "snapshot-1",
        ).copy(runId = "run-123").toOutput()

        assertEquals("phone-backup", output.jobId)
        assertEquals("run-123", output.runId)
        assertEquals("phone-drive", output.repositoryId)
        assertEquals("snapshot-1", output.snapshotId)
    }

    @Test
    fun processedCountAliasesMirrorLegacyProgressOutputs() {
        val output = BkxTaskerSignal(
            event = BkxTaskerEventType.ProgressMilestone.id,
            filesDone = 7,
            filesTotal = 10,
            bytesDone = 700,
            bytesTotal = 1000,
            timestampEpochMillis = 1L,
        ).toOutput()
        assertEquals("7", output.filesDone)
        assertEquals("7", output.filesProcessed)
        assertEquals("700", output.bytesDone)
        assertEquals("700", output.bytesProcessed)
    }

    @Test
    fun namedRcloneRemoteFilterDoesNotMatchAnotherRemote() {
        assertTrue(BkxTaskerConditionMatcher.remoteMatches("rclone", "nas", listOf("rclone:nas")))
        assertFalse(BkxTaskerConditionMatcher.remoteMatches("rclone", "nas", listOf("rclone:offsite")))
        assertTrue(BkxTaskerConditionMatcher.remoteMatches("remote", "", listOf("google-drive", "rclone:nas")))
    }

    @Test
    fun errorCodeFilterUsesGlobSemantics() {
        val input = BkxTaskerEventInput(eventType = "operation_failed", errorCode = "BKX_*AUTH*")
        assertTrue(BkxTaskerConditionMatcher.eventMatches(input, signal(event = "operation_failed", errorCode = "BKX_GOOGLE_DRIVE_AUTH_REQUIRED")))
        assertFalse(BkxTaskerConditionMatcher.eventMatches(input, signal(event = "operation_failed", errorCode = "BKX_IO_FAILURE")))
    }

    private fun signal(
        event: String,
        operation: String = "",
        requestId: String = "",
        remotes: List<String> = emptyList(),
        errorCode: String = "",
        jobId: String = "",
        repositoryId: String = "",
        snapshotId: String = "",
    ) = BkxTaskerSignal(
        event = event,
        operation = operation,
        requestId = requestId,
        remotes = remotes,
        errorCode = errorCode,
        jobId = jobId,
        repositoryId = repositoryId,
        snapshotId = snapshotId,
        timestampEpochMillis = 1L,
    )
}
