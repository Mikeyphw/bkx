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

class RootExecutionPolicyBr23Test {
    @Test
    fun preferredUsesLocalForSharedPathAndRootOnlyForSensitivePaths() {
        val shared = RootExecutionRequest("backup.snapshot", listOf("/storage/emulated/0/BKX"))
        val private = RootExecutionRequest("backup.snapshot", listOf("/data/data/com.termux/files/home"))
        assertEquals(RootExecutionRoute.Local, RootExecutionPolicy.select(RootAccessMode.Preferred, rootAvailable = true, request = shared))
        assertEquals(RootExecutionRoute.Root, RootExecutionPolicy.select(RootAccessMode.Preferred, rootAvailable = true, request = private))
        assertEquals(RootExecutionRoute.Blocked, RootExecutionPolicy.select(RootAccessMode.Preferred, rootAvailable = false, request = private))
    }

    @Test
    fun disabledFailsClosedForRootSensitivePaths() {
        val private = RootExecutionRequest("restore.extract", listOf("/data/adb/modules"))
        assertEquals(RootExecutionRoute.Blocked, RootExecutionPolicy.select(RootAccessMode.Disabled, rootAvailable = true, request = private))
    }
}
