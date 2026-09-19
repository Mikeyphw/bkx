package com.mikeyphw.bkx.android.core.backup

import com.mikeyphw.bkx.android.core.repositories.RepositoryDefinition
import com.mikeyphw.bkx.android.core.repositories.RepositoryRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupEngineUxContractTest {
    private val source = BackupSourceSelection("source", "Documents", "/storage/emulated/0/Documents")

    @Test fun resticDraftRequiresRepositoryInsteadOfArchiveDestination() {
        val draft = BackupPlanDraft(engine = BackupEngineKind.Restic, source = source, repositoryId = "phone")
        assertTrue(draft.validationIssues().isEmpty())
        assertFalse(draft.validationIssues().contains(BackupPlanIssue.MissingDestination))
    }

    @Test fun bakeryDraftStillRequiresDestination() {
        val draft = BackupPlanDraft(engine = BackupEngineKind.Bakery, source = source)
        assertTrue(draft.validationIssues().contains(BackupPlanIssue.MissingDestination))
    }

    @Test fun resticReviewUsesBackendDryRun() {
        val repository = RepositoryDefinition(1, "phone", "Phone", "restic", "local", "/storage/emulated/0/BKX/restic", null, false, "now", "now")
        val request = RepositoryRequestBuilder.snapshot("review", repository, listOf(source.stagedPath), dryRun = true)
        assertEquals("backup.snapshot", request.operation)
        assertEquals(true, request.payload["dry_run"]?.toString()?.toBoolean())
    }
}
