package com.mikeyphw.bkx.android.core.transfer

import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSafetyContractTest {
    private val source = TransferEndpoint("src-1", "Phone archive", "/shared/bkx/archives/photos.bakery")
    private val destination = TransferEndpoint("dst-1", "Remote laptop", "rclone://drive/backups", isRemote = true)
    private val draft = TransferTopologyDraft(
        mode = TransferMode.Push,
        sources = listOf(source),
        destination = destination,
        verifyAfterTransfer = true,
        symlinkPolicy = TransferFilePolicy.Block,
        specialFilePolicy = TransferFilePolicy.Block,
    )

    @Test
    fun transferDraftModelsPushPullAndSyncTopology() {
        assertTrue(draft.validationIssues().isEmpty())
        assertEquals("push", draft.mode.protocolValue)

        val pull = TransferTopologyDraft(
            mode = TransferMode.Pull,
            sources = listOf(TransferEndpoint("remote", "Remote backup", "rclone://drive/backups/photos.bakery", isRemote = true)),
            destination = TransferEndpoint("local", "Phone staging", "/shared/bkx/incoming"),
        )
        val sync = TransferTopologyDraft(
            mode = TransferMode.Sync,
            sources = listOf(TransferEndpoint("sync-src", "Sync source", "/shared/bkx/source")),
            destination = TransferEndpoint("sync-dst", "Sync destination", "/shared/bkx/destination"),
            deleteExtraneousOnSync = true,
        )

        assertTrue(pull.validationIssues().isEmpty())
        assertTrue(sync.validationIssues().isEmpty())
        assertEquals("sync", sync.mode.protocolValue)
    }

    @Test
    fun previewRequestContainsMappingPolicyAndVerificationPlanInputs() {
        val request = TransferRequestBuilder().buildPreviewRequest("transfer-preview", draft)

        assertEquals("transfer.plan", request.operation)
        assertEquals("push", request.payload["mode"]?.jsonPrimitive?.content)
        assertEquals(true, request.payload["dry_run"]?.jsonPrimitive?.boolean)
        assertEquals(true, request.payload["verify"]?.jsonPrimitive?.boolean)
        assertEquals("block", request.payload["policies"]?.jsonObject?.get("symlink")?.jsonPrimitive?.content)
        assertEquals("/shared/bkx/archives/photos.bakery", request.payload["sources"]?.jsonArray?.single()?.jsonObject?.get("location")?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun runRequiresTopologyPreview() {
        TransferRequestBuilder().buildRunRequest("transfer-run", draft, preview = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun runRejectsStalePreview() {
        val preview = TransferPreviewSummary(
            requestId = "transfer-preview",
            draftFingerprint = draft.fingerprint(),
            topologyLabel = "push one file",
            mappings = listOf(TransferMappingSummary("Phone archive", "Remote laptop", "photos.bakery", TransferMappingAction.Create)),
            collisionSummary = TransferCollisionSummary(),
            verificationPlan = TransferVerificationPlan(enabled = true),
        )
        val changed = draft.copy(overwriteExisting = true)

        TransferRequestBuilder().buildRunRequest("transfer-run", changed, preview)
    }

    @Test(expected = IllegalArgumentException::class)
    fun collisionPreviewBlocksRun() {
        val preview = TransferPreviewSummary(
            requestId = "transfer-preview",
            draftFingerprint = draft.fingerprint(),
            topologyLabel = "push one file",
            mappings = listOf(TransferMappingSummary("Phone archive", "Remote laptop", "photos.bakery", TransferMappingAction.Update)),
            collisionSummary = TransferCollisionSummary(collisions = listOf("photos.bakery already exists")),
            verificationPlan = TransferVerificationPlan(enabled = true),
        )

        TransferRequestBuilder().buildRunRequest("transfer-run", draft, preview)
    }

    @Test
    fun runRequestUsesMatchingPreviewAndKeepsDryRunFalse() {
        val preview = TransferPreviewSummary(
            requestId = "transfer-preview",
            draftFingerprint = draft.fingerprint(),
            topologyLabel = "push one file",
            mappings = listOf(TransferMappingSummary("Phone archive", "Remote laptop", "photos.bakery", TransferMappingAction.Create)),
            collisionSummary = TransferCollisionSummary(),
            verificationPlan = TransferVerificationPlan(enabled = true),
        )
        val request = TransferRequestBuilder().buildRunRequest("transfer-run", draft, preview)

        assertTrue(canRunTransfer(draft, preview))
        assertEquals("transfer.run", request.operation)
        assertEquals(false, request.payload["dry_run"]?.jsonPrimitive?.boolean)
        assertEquals("transfer-preview", request.payload["plan_request_id"]?.jsonPrimitive?.content)
        assertEquals("transfer-preview", request.payload["preview_request_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun partialAndVerificationFailuresRenderAsTypedFailures() {
        val partial = BridgeResultEnvelope(
            protocolVersion = 1,
            requestId = "transfer-run",
            status = BridgeResultStatus.Failure,
            summary = "one file failed",
            errors = listOf(BridgeError("partial_failure", "one file failed")),
        ).toTransferResultUiState()
        val verification = BridgeResultEnvelope(
            protocolVersion = 1,
            requestId = "transfer-run",
            status = BridgeResultStatus.Failure,
            summary = "checksum mismatch",
            errors = listOf(BridgeError("verification_failed", "sha mismatch")),
        ).toTransferResultUiState()

        assertTrue(partial is TransferResultUiState.PartialFailure)
        assertTrue(verification is TransferResultUiState.VerificationFailed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferWithoutTopologyIsBlocked() {
        TransferRequestBuilder().buildPreviewRequest("transfer-preview", TransferTopologyDraft())
    }
}
