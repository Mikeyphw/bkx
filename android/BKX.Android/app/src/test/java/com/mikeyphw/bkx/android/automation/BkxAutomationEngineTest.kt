package com.mikeyphw.bkx.android.automation

import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator.OperationOutcome
import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.operations.OperationRuntimeState
import com.mikeyphw.bkx.android.core.runs.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BkxAutomationEngineTest {
    @Test
    fun backupUsesPlanThenCreateAndDeduplicatesTerminalRequestId() = runBlocking {
        val port = FakeAutomationOperationPort { request -> when (request.operation) {
            "backup.plan" -> success(request, artifacts = listOf(BridgeArtifact("backup_plan", "plan", "a".repeat(64))))
            "backup.create" -> success(request, artifacts = listOf(BridgeArtifact("archive", "/storage/emulated/0/Backups/a.bakery", "b".repeat(64))))
            else -> error("unexpected ${request.operation}")
        } }
        val store = InMemoryAutomationStateStore()
        val engine = BkxAutomationEngine(port, store, ManagedDriveRefreshPort { null })
        val request = AutomationRequest(AutomationAction.Backup, "tasker-backup-1", source = "/storage/emulated/0/DCIM", destination = "/storage/emulated/0/Backups")

        val first = engine.execute(request)
        val second = engine.execute(request)

        assertEquals("success", first.status)
        assertEquals(first, second)
        assertEquals(listOf("backup.plan", "backup.create"), port.operations)
    }

    @Test
    fun restoreKeepsExistingPreviewAndExplicitConfirmationBarrier() = runBlocking {
        val port = FakeAutomationOperationPort { request -> when (request.operation) {
            "restore.preview" -> success(request, errors = listOf(BridgeError("overwrite_collision", "Would overwrite existing.txt")))
            "restore.extract" -> success(request)
            else -> error("unexpected ${request.operation}")
        } }
        val engine = BkxAutomationEngine(port, InMemoryAutomationStateStore(), ManagedDriveRefreshPort { null })

        val blocked = engine.execute(
            AutomationRequest(
                AutomationAction.Restore,
                "restore-blocked",
                archive = "/storage/emulated/0/Backups/a.bakery",
                destination = "/storage/emulated/0/Restore",
            ),
        )
        assertEquals("restore_confirmation_required", blocked.errorCode)
        assertEquals(listOf("restore.preview"), port.operations)

        port.operations.clear()
        val allowed = engine.execute(
            AutomationRequest(
                AutomationAction.Restore,
                "restore-confirmed",
                archive = "/storage/emulated/0/Backups/a.bakery",
                destination = "/storage/emulated/0/Restore",
                restoreConfirmation = "RESTORE",
            ),
        )
        assertEquals("success", allowed.status)
        assertEquals(listOf("restore.preview", "restore.extract"), port.operations)
    }

    @Test
    fun transferPreviewBlocksCollisionBeforeRun() = runBlocking {
        val port = FakeAutomationOperationPort { request -> success(request, errors = listOf(BridgeError("collision", "destination exists"))) }
        val engine = BkxAutomationEngine(port, InMemoryAutomationStateStore(), ManagedDriveRefreshPort { null })
        val result = engine.execute(
            AutomationRequest(
                AutomationAction.Transfer,
                "transfer-1",
                source = "/storage/emulated/0/DCIM",
                destination = "rclone://box/photos",
                transferMode = "push",
            ),
        )
        assertEquals("transfer_preview_blocked", result.errorCode)
        assertEquals(listOf("transfer.plan"), port.operations)
    }

    @Test
    fun managedGoogleDriveMustRefreshBeforeRemoteOperation() = runBlocking {
        var refreshes = 0
        val port = FakeAutomationOperationPort { request -> success(request) }
        val engine = BkxAutomationEngine(
            port,
            InMemoryAutomationStateStore(),
            ManagedDriveRefreshPort { locations ->
                if (locations.any { it.startsWith("rclone://bkx-google-drive/BKX") }) refreshes++
                null
            },
        )
        engine.execute(AutomationRequest(AutomationAction.Verify, "drive-verify", archive = "rclone://bkx-google-drive/BKX/a.bakery"))
        assertEquals(1, refreshes)
        assertEquals(listOf("archive.verify"), port.operations)
    }


    @Test
    fun orphanedPersistentRequestIdFailsClosedInsteadOfRepeatingWork() = runBlocking {
        val port = FakeAutomationOperationPort { request -> success(request) }
        val store = InMemoryAutomationStateStore().apply { markActive("interrupted-1", "interrupted-1-backup-create") }
        val engine = BkxAutomationEngine(port, store, ManagedDriveRefreshPort { null })

        val result = engine.execute(
            AutomationRequest(
                AutomationAction.Backup,
                "interrupted-1",
                source = "/storage/emulated/0/DCIM",
                destination = "/storage/emulated/0/Backups",
            ),
        )

        assertEquals("automation_previous_dispatch_incomplete", result.errorCode)
        assertTrue(port.operations.isEmpty())
    }

    @Test
    fun malformedRemoteRestoreIsRejectedBeforeBackendExecution() = runBlocking {
        val port = FakeAutomationOperationPort { request -> success(request) }
        val engine = BkxAutomationEngine(port, InMemoryAutomationStateStore(), ManagedDriveRefreshPort { null })

        val result = engine.execute(
            AutomationRequest(
                AutomationAction.Restore,
                "bad-remote-restore",
                archive = "rclone://drive/path?token=forbidden",
                destination = "/storage/emulated/0/Restore",
            ),
        )

        assertEquals("automation_invalid_request", result.errorCode)
        assertTrue(port.operations.isEmpty())
    }

    @Test
    fun resultPublishesStableTaskerVariables() {
        val result = AutomationResult("id", AutomationAction.Backup, "success", "id-backup-create", "done", artifactPaths = listOf("/a"))
        val variables = result.taskerVariableValues()
        assertEquals("id", variables["%bkx_request_id"])
        assertEquals("success", variables["%bkx_status"])
        assertTrue(AutomationResult.relevantVariables.contains("%bkx_error_code"))
    }

    private fun success(
        request: BridgeRequestEnvelope,
        errors: List<BridgeError> = emptyList(),
        artifacts: List<BridgeArtifact> = emptyList(),
    ) = BridgeResultEnvelope(1, request.requestId, BridgeResultStatus.Success, "ok", errors, artifacts)
}

private class FakeAutomationOperationPort(
    private val executeResult: (BridgeRequestEnvelope) -> BridgeResultEnvelope,
) : AutomationOperationPort {
    val operations = mutableListOf<String>()
    override suspend fun execute(request: BridgeRequestEnvelope, recordInHistory: Boolean): OperationOutcome {
        operations += request.operation
        return OperationOutcome.Completed(executeResult(request))
    }
    override suspend fun cancel(requestId: String, cancelRequestId: String): OperationOutcome = OperationOutcome.CancellationAccepted(requestId)
    override suspend fun snapshot(): OperationRuntimeState = OperationRuntimeState()
    override suspend fun recentRuns(limit: Int): List<RunSummary> = emptyList()
}
