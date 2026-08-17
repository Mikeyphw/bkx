package com.mikeyphw.bkx.android.app.navigation

import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndToolsContractTest {
    @Test
    fun initialNavigationGraphContainsEveryPlannedScreenWithState() {
        val state = BkxHomeUiState()

        assertEquals(BkxDestination.entries.toList(), state.destinations)
        BkxDestination.entries.forEach { destination ->
            assertTrue("Missing screen status for $destination", state.screenStatuses.containsKey(destination))
        }
    }

    @Test
    fun backendToolStateProvidesRemediationActionsForNotReadyStates() {
        val tools = BackendToolState(
            readiness = BackendReadiness.RunCommandPermissionMissing,
            protocolVersion = 1,
        )

        assertEquals("run_command_permission_missing", tools.remediationActions.single().code)
        assertTrue(tools.remediationActions.single().steps.any { it.contains("Run commands") })
    }
}
