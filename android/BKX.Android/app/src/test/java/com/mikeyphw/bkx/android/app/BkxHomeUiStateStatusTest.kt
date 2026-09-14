package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.archives.ArchiveReadOnlyUiState
import com.mikeyphw.bkx.android.core.archives.ArchiveWorkflowUiState
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxHomeUiStateStatusTest {
    @Test
    fun destinationStatusIsDerivedFromCanonicalFeatureState() {
        val loading = BkxHomeUiState(
            readiness = BackendReadiness.Checking,
            archiveReadOnly = ArchiveReadOnlyUiState(listState = ArchiveWorkflowUiState.Loading),
        )
        assertEquals(ScreenStatus.Loading, loading.screenStatus(BkxDestination.Home))
        assertEquals(ScreenStatus.Loading, loading.screenStatus(BkxDestination.Archives))

        val failed = BkxHomeUiState(
            readiness = BackendReadiness.ProbeFailed("engine offline"),
            archiveReadOnly = ArchiveReadOnlyUiState(
                listState = ArchiveWorkflowUiState.Failed(emptyList(), "archive bridge failed"),
            ),
        )
        val home = failed.screenStatus(BkxDestination.Home)
        val archives = failed.screenStatus(BkxDestination.Archives)
        assertTrue(home is ScreenStatus.Error && home.message == "engine offline")
        assertTrue(archives is ScreenStatus.Error && archives.message == "archive bridge failed")
    }

    @Test
    fun aggregateStatusMapCannotDivergeBecauseStateHasNoStoredScreenStatusesProperty() {
        val storedNames = BkxHomeUiState::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("screenStatuses" !in storedNames)
    }
}
