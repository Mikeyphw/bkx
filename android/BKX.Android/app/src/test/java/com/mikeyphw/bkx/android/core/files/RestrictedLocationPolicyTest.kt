package com.mikeyphw.bkx.android.core.files

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedLocationPolicyTest {
    @Test
    fun refusesRestrictedOrUnavailableDirectories() {
        assertTrue(RestrictedLocationPolicy.classify("") is FileAccessIssue.UnavailableLocation)
        assertTrue(RestrictedLocationPolicy.classify("relative/staging") is FileAccessIssue.UnavailableLocation)
        assertTrue(RestrictedLocationPolicy.classify("/") is FileAccessIssue.RestrictedLocation)
        assertTrue(RestrictedLocationPolicy.classify("/Android/data/com.example") is FileAccessIssue.RestrictedLocation)
        assertTrue(RestrictedLocationPolicy.classify("/proc/self") is FileAccessIssue.RestrictedLocation)
    }

    @Test
    fun allowsExplicitSharedStagingPath() {
        assertNull(RestrictedLocationPolicy.classify("/shared/bkx/staging/request-1/file.tar"))
    }
}

class RestrictedLocationPolicyBr23Test {
    @Test
    fun rejectsTraversalAliasAndInsufficientCapacity() {
        assertTrue(RestrictedLocationPolicy.classify("/shared/bkx/../escape") is FileAccessIssue.RestrictedLocation)
        assertTrue(RestrictedLocationPolicy.classify("/storage/emulated/0/Android/data/com.example") is FileAccessIssue.RestrictedLocation)
        val grant = PersistedUriGrantRecord(
            uri = "content://provider/document/2",
            kind = UserSelectionKind.Document,
            read = true,
            write = false,
            state = UriGrantState.Active,
            persistedAtEpochMillis = 1,
        )
        val selection = UserFileSelection(grant.uri, UserSelectionKind.Document, "file", grant, 2)
        try {
            StagedFilePlanner().plan(selection, "/shared/bkx/staging/file", StagingEvidence(sizeBytes = 64, sha256 = null, availableBytes = 63))
        } catch (error: FileAccessPolicyException) {
            assertTrue(error.issue is FileAccessIssue.InsufficientStagingCapacity)
            return
        }
        throw AssertionError("insufficient staging capacity was accepted")
    }
}
