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
