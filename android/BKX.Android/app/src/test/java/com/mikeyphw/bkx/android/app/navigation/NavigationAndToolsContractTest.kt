package com.mikeyphw.bkx.android.app.navigation

import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndToolsContractTest {
    @Test
    fun primaryNavigationIsTheFiveDestinationProductShell() {
        val state = BkxHomeUiState()

        assertEquals(
            listOf(BkxDestination.Home, BkxDestination.Backup, BkxDestination.Archives, BkxDestination.Activity, BkxDestination.More),
            state.destinations,
        )
        BkxDestination.entries.forEach { destination ->
            assertTrue("Missing screen status for $destination", state.screenStatuses.containsKey(destination))
        }
        assertFalse(state.destinations.any { it.label == "Tools" || it.label == "Settings" || it.label == "Restore" || it.label == "Transfers" })
    }

    @Test
    fun backendToolStateProvidesRemediationActionsForNotReadyStates() {
        val tools = BackendToolState(
            readiness = BackendReadiness.RootPermissionRequired,
            protocolVersion = 1,
        )

        assertEquals("root_permission_required", tools.remediationActions.single().code)
        assertTrue(tools.remediationActions.single().steps.any { it.contains("root", ignoreCase = true) })
    }
}
