package com.mikeyphw.bkx.android.root

import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RootExecutionPolicyTest {
    @Test
    fun preferredUsesRootWhenAvailableAndLocalOtherwise() {
        assertEquals(RootExecutionRoute.Root, RootExecutionPolicy.select(RootAccessMode.Preferred, true))
        assertEquals(RootExecutionRoute.Local, RootExecutionPolicy.select(RootAccessMode.Preferred, false))
    }

    @Test
    fun requiredFailsClosedWithoutRoot() {
        assertEquals(RootExecutionRoute.Root, RootExecutionPolicy.select(RootAccessMode.Required, true))
        assertEquals(RootExecutionRoute.Blocked, RootExecutionPolicy.select(RootAccessMode.Required, false))
    }

    @Test
    fun disabledNeverEscalates() {
        assertEquals(RootExecutionRoute.Local, RootExecutionPolicy.select(RootAccessMode.Disabled, true))
    }
}
