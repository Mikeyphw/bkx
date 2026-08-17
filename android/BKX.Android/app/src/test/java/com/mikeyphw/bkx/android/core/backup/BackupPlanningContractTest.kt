package com.mikeyphw.bkx.android.core.backup

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlanningContractTest {
    private val draft = BackupPlanDraft(
        source = BackupSourceSelection(
            sourceId = "src-1",
            label = "Pictures",
            stagedPath = "/shared/bkx/staging/src-1",
            originalUri = "content://media/pictures",
        ),
        destination = BackupDestinationSelection(
            destinationId = "dst-1",
            label = "Download",
            stagedDirectoryPath = "/shared/bkx/backups",
        ),
        filters = BackupFilterRules(
            includePatterns = listOf("**/*.jpg"),
            excludePatterns = listOf("cache/**"),
            includeHidden = false,
            followSymlinks = false,
        ),
        compression = BackupCompression.Zstd,
        split = BackupSplitPolicy(enabled = true, chunkSizeBytes = 64L * 1024L * 1024L),
        verification = BackupVerificationPolicy(verifyAfterCreate = true),
    )

    @Test
    fun backupDraftCapturesSourceDestinationFiltersCompressionSplitAndVerify() {
        assertEquals("Pictures", draft.source?.label)
        assertEquals("Download", draft.destination?.label)
        assertEquals(listOf("**/*.jpg"), draft.filters.includePatterns)
        assertEquals(listOf("cache/**"), draft.filters.excludePatterns)
        assertEquals(BackupCompression.Zstd, draft.compression)
        assertEquals(64L * 1024L * 1024L, draft.split.chunkSizeBytes)
        assertTrue(draft.verification.verifyAfterCreate)
        assertTrue(draft.validationIssues().isEmpty())
    }

    @Test
    fun backupCreateIsGeneratedOnlyFromMatchingPlanPreview() {
        val builder = BackupRequestBuilder()
        val plan = builder.buildPlanRequest("req-plan", draft)
        val preview = BackupPlanPreview(
            requestId = plan.requestId,
            planToken = "token-1",
            draftFingerprint = draft.fingerprint(),
            warnings = listOf(BackupPlanWarning("large_source", "Large backup may take a while.")),
            verificationExpectations = listOf(BackupVerificationExpectation("verify_hash", "BKX must verify SHA-256.")),
        )

        val create = builder.buildCreateRequest("req-create", draft, preview)

        assertEquals("backup.plan", plan.operation)
        assertEquals("backup.create", create.operation)
        assertEquals("req-plan", create.payload["plan_request_id"]?.jsonPrimitive?.content)
        assertEquals("token-1", create.payload["plan_token"]?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDraftCannotPlan() {
        BackupRequestBuilder().buildPlanRequest("req-plan", BackupPlanDraft())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unpreviewedDraftCannotCreate() {
        BackupRequestBuilder().buildCreateRequest("req-create", draft, preview = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stalePreviewCannotCreate() {
        BackupRequestBuilder().buildCreateRequest(
            requestId = "req-create",
            draft = draft.copy(compression = BackupCompression.Gzip),
            preview = BackupPlanPreview(
                requestId = "req-plan",
                planToken = "token-1",
                draftFingerprint = draft.fingerprint(),
            ),
        )
    }

    @Test
    fun planningStateRendersWarningsAndVerificationExpectationsBeforeExecution() {
        val preview = BackupPlanPreview(
            requestId = "req-plan",
            planToken = "token-1",
            draftFingerprint = draft.fingerprint(),
            warnings = listOf(BackupPlanWarning("filter_overlap", "An exclude rule overlaps an include rule.")),
            verificationExpectations = listOf(BackupVerificationExpectation("verify_manifest", "Manifest must be verified before success.")),
        )
        val state = BackupPlanningUiState.withPreview(draft, preview)

        assertTrue(state.canRequestPlan)
        assertTrue(state.canCreateBackup)
        assertEquals("filter_overlap", state.preview?.warnings?.single()?.code)
        assertEquals("verify_manifest", state.preview?.verificationExpectations?.single()?.code)
    }

    @Test
    fun uiToRequestPayloadMatchesEquivalentCliBackupIntent() {
        val intent = BackupCliIntent(
            sourcePath = "/home/user/photos",
            destinationPath = "/sdcard/Download/bkx",
            includePatterns = listOf("**/*.jpg"),
            excludePatterns = listOf("cache/**"),
            includeHidden = false,
            compression = BackupCompression.Zstd,
            splitChunkSizeBytes = 32L * 1024L * 1024L,
            verifyAfterCreate = true,
        )
        val request = BackupRequestBuilder().buildPlanRequest(
            requestId = "req-cli-parity",
            draft = BackupPlanDraft.fromCliIntent(intent),
        )

        assertEquals("backup.plan", request.operation)
        assertEquals("/home/user/photos", request.payload["source"]?.jsonObject?.get("staged_path")?.jsonPrimitive?.content)
        assertEquals("/sdcard/Download/bkx", request.payload["destination"]?.jsonObject?.get("staged_directory_path")?.jsonPrimitive?.content)
        assertEquals("zstd", request.payload["archive"]?.jsonObject?.get("compression")?.jsonPrimitive?.content)
        assertTrue(request.payload["archive"]?.jsonObject?.get("verification")?.jsonObject?.get("verify_after_create")?.jsonPrimitive?.boolean == true)
        assertFalse(request.payload.toString().contains("backup.create"))
    }
}
