package com.mikeyphw.bkx.android.core.restore

import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
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

class RestoreSafetyContractTest {
    private val archive = ArchiveLocation(
        archiveId = "archive-1",
        displayName = "photos.bakery",
        stagedPath = "/shared/bkx/archives/photos.bakery",
    )
    private val destination = RestoreDestinationSelection(
        destinationId = "dst-1",
        label = "Fresh restore output",
        stagedDirectoryPath = "/shared/bkx/restores/photos",
    )
    private val draft = RestoreDraft(
        archive = archive,
        destination = destination,
        subset = RestoreSubsetSelection.Paths(listOf("Pictures/2026/a.jpg")),
    )

    @Test
    fun restoreDraftCreatesArchiveDestinationSubsetAndFreshPreviewWorkflow() {
        assertEquals(archive, draft.archive)
        assertEquals(destination, draft.destination)
        assertEquals(listOf("Pictures/2026/a.jpg"), draft.subset.selectedPaths)
        assertTrue(draft.freshDestination)
        assertEquals(RestoreOutputMode.FreshDirectory, draft.outputMode)
        assertTrue(draft.validationIssues().isEmpty())
    }

    @Test
    fun previewRequestUsesDryRunAndDoesNotOverwrite() {
        val request = RestoreRequestBuilder().buildPreviewRequest("restore-preview", draft)

        assertEquals("restore.preview", request.operation)
        assertEquals(true, request.payload["dry_run"]?.jsonPrimitive?.boolean)
        assertEquals(false, request.payload["overwrite"]?.jsonPrimitive?.boolean)
        assertEquals(false, request.payload["confirm_overwrite"]?.jsonPrimitive?.boolean)
        assertEquals(null, request.payload["confirmation_evidence"])
        assertEquals("fresh_directory", request.payload["output_mode"]?.jsonPrimitive?.content)
        assertEquals("Pictures/2026/a.jpg", request.payload["paths"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractionRequiresPreviewFirst() {
        RestoreRequestBuilder().buildExtractRequest("restore-extract", draft, preview = null, confirmation = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stalePreviewCannotExtractAfterDestinationOrOutputModeChange() {
        val preview = RestorePreviewSummary(
            requestId = "restore-preview",
            draftFingerprint = draft.fingerprint(),
            planToken = "plan-token",
            selectedPathCount = 1,
            fullRestore = false,
        )
        val changed = draft.copy(outputMode = RestoreOutputMode.ExistingDirectory)

        RestoreRequestBuilder().buildExtractRequest("restore-extract", changed, preview, confirmation = null)
    }

    @Test
    fun destructiveExtractionRequiresExplicitMatchingConfirmation() {
        val preview = RestorePreviewSummary(
            requestId = "restore-preview",
            draftFingerprint = draft.fingerprint(),
            planToken = "plan-token",
            selectedPathCount = 1,
            fullRestore = false,
            overwriteSummary = RestoreOverwriteSummary(listOf("Pictures/2026/a.jpg")),
        )
        assertFalse(canExtractRestore(draft, preview, confirmation = null))

        val confirmation = RestoreExtractionConfirmation(
            draftFingerprint = draft.fingerprint(),
            planToken = "plan-token",
            acknowledgedOverwriteCount = 1,
            confirmationPhrase = RestoreExtractionConfirmation.CONFIRMATION_PHRASE,
            confirmedAtEpochMillis = 1_786_000_000_000,
        )
        val request = RestoreRequestBuilder().buildExtractRequest("restore-extract", draft, preview, confirmation)

        assertTrue(canExtractRestore(draft, preview, confirmation))
        assertEquals("restore.extract", request.operation)
        assertEquals(false, request.payload["dry_run"]?.jsonPrimitive?.boolean)
        assertEquals(true, request.payload["overwrite"]?.jsonPrimitive?.boolean)
        assertEquals(true, request.payload["confirm_overwrite"]?.jsonPrimitive?.boolean)
        val evidence = request.payload["confirmation_evidence"]?.jsonObject
        assertEquals(RestoreExtractionConfirmation.CONFIRMATION_PHRASE, evidence?.get("phrase")?.jsonPrimitive?.content)
        assertEquals("1", evidence?.get("acknowledged_overwrite_count")?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathSafetyPreviewBlocksExtractionEvenWithConfirmation() {
        val preview = RestorePreviewSummary(
            requestId = "restore-preview",
            draftFingerprint = draft.fingerprint(),
            planToken = "plan-token",
            selectedPathCount = 1,
            fullRestore = false,
            pathSafetySummary = RestorePathSafetySummary(unsafePaths = listOf("../escape")),
        )
        val confirmation = RestoreExtractionConfirmation(
            draftFingerprint = draft.fingerprint(),
            planToken = "plan-token",
            acknowledgedOverwriteCount = 0,
            confirmationPhrase = RestoreExtractionConfirmation.CONFIRMATION_PHRASE,
            confirmedAtEpochMillis = 1_786_000_000_000,
        )

        RestoreRequestBuilder().buildExtractRequest("restore-extract", draft, preview, confirmation)
    }

    @Test
    fun bridgeResultFieldsBecomeOverwriteLinkAndPathSafetySummary() {
        val preview = BridgeResultEnvelope(
            protocolVersion = 1,
            requestId = "restore-preview",
            status = BridgeResultStatus.Failure,
            summary = "preview contains safety issues",
            errors = listOf(
                BridgeError("overwrite_collision", "existing Pictures/2026/a.jpg"),
                BridgeError("unsafe_path", "archive member escapes destination"),
                BridgeError("archive_symlink", "blocked symlink member"),
            ),
        ).toRestorePreviewSummary(draft)

        assertEquals(1, preview.overwriteSummary.totalOverwrites)
        assertEquals(listOf("archive member escapes destination"), preview.pathSafetySummary.unsafePaths)
        assertEquals(listOf("blocked symlink member"), preview.pathSafetySummary.blockedLinks)
        assertFalse(preview.isExecutable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeSubsetPathsAreRejected() {
        RestoreSubsetSelection.Paths(listOf("../escape"))
    }
    @Test
    fun everyRestoreModeRequiresExplicitDestinationAndRootSensitivePathsNeedRootPhrase() {
        val missing = RestoreDraft(archive = archive, outputMode = RestoreOutputMode.FreshDirectory)
        assertTrue(missing.validationIssues().any { it is RestoreValidationIssue.MissingDestination })
        assertTrue(RestoreDestinationPolicy.blockedReason("/system/etc") != null)
        assertTrue(RestoreDestinationPolicy.blockedReason("/data/system/users") != null)
        assertTrue(RestoreDestinationPolicy.requiresRootConfirmation("/data/data/com.termux/files/home/restore-test"))

        val rootDraft = draft.copy(
            destination = RestoreDestinationSelection(
                destinationId = "termux-root",
                label = "Termux restore test",
                stagedDirectoryPath = "/data/data/com.termux/files/home/bkx-restore-test",
            ),
        )
        assertEquals(RestoreExtractionConfirmation.ROOT_CONFIRMATION_PHRASE, RestoreExtractionConfirmation.requiredPhrase(rootDraft))
    }

}
