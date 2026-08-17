
package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProtocolException
import com.mikeyphw.bkx.android.core.files.FileAccessIssue
import com.mikeyphw.bkx.android.core.operations.OperationFailureKind
import com.mikeyphw.bkx.android.core.operations.OperationFailureMapper
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.restore.RestoreDraft
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestoreOverwriteSummary
import com.mikeyphw.bkx.android.core.restore.RestorePreviewSummary
import com.mikeyphw.bkx.android.core.restore.RestoreRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aov11AdversarialContractTest {
    @Test
    fun missingTermuxMapsToStableBackendNotReadyFailure() {
        val failure = OperationFailureMapper.fromBackendReadiness(BackendReadiness.TermuxNotVisibleOrMissing)

        assertEquals(OperationFailureKind.BackendNotReady, failure.kind)
        assertEquals("TERMUX_NOT_VISIBLE_OR_MISSING", failure.code)
        assertTrue(failure.actions.any { it.contains("Termux", ignoreCase = true) || it.contains("backend", ignoreCase = true) })
    }

    @Test
    fun runCommandPermissionDenialMapsToStablePermissionFailure() {
        val failure = OperationFailureMapper.fromBackendReadiness(BackendReadiness.RunCommandPermissionMissing)

        assertEquals(OperationFailureKind.PermissionDenied, failure.kind)
        assertEquals("BKX_RUN_COMMAND_PERMISSION_MISSING", failure.code)
        assertTrue(failure.actions.any { it.contains("RUN_COMMAND") || it.contains("Grant", ignoreCase = true) })
    }

    @Test
    fun safGrantLossIsRenderedAsRecoverableFilePermissionFailure() {
        val issue = FileAccessIssue.GrantLost("content://com.android.providers.media.documents/document/image%3A1")
        val failure = OperationFailureMapper.fromBridgeError(BridgeError("SAF_GRANT_LOST", issue.message, issue.remediationSteps.single()))

        assertEquals(OperationFailureKind.SafGrantLost, failure.kind)
        assertTrue(failure.actions.any { it.contains("Pick", ignoreCase = true) || it.contains("select", ignoreCase = true) })
    }

    @Test(expected = BridgeProtocolException::class)
    fun malformedJsonBridgeOutputIsRejected() {
        BridgeJson.parseFinalResult("{\"protocol_version\":1,\"request_id\":")
    }

    @Test(expected = IllegalArgumentException::class)
    fun restoreConfirmationBypassWithMismatchedEvidenceIsRejected() {
        val archive = ArchiveLocation(
            archiveId = "archive-1",
            displayName = "photos.bakery",
            stagedPath = "/shared/bkx/archives/photos.bakery",
        )
        val destination = RestoreDestinationSelection(
            destinationId = "dst-1",
            label = "Restore output",
            stagedDirectoryPath = "/shared/bkx/restores/photos",
        )
        val draft = RestoreDraft(archive = archive, destination = destination)
        val preview = RestorePreviewSummary(
            requestId = "restore-preview",
            draftFingerprint = draft.fingerprint(),
            planToken = "plan-token",
            selectedPathCount = 0,
            fullRestore = true,
            overwriteSummary = RestoreOverwriteSummary(listOf("existing.txt")),
        )
        val forgedConfirmation = RestoreExtractionConfirmation(
            draftFingerprint = "forged-${draft.fingerprint()}",
            planToken = preview.planToken,
            acknowledgedOverwriteCount = 1,
            confirmationPhrase = RestoreExtractionConfirmation.CONFIRMATION_PHRASE,
            confirmedAtEpochMillis = 1_786_000_000_000,
        )

        RestoreRequestBuilder().buildExtractRequest("restore-extract", draft, preview, forgedConfirmation)
    }
}
