package com.mikeyphw.bkx.android.r1

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Pure argument denials. Does not open a socket, issue grants, or start R1. */
@RunWith(AndroidJUnit4::class)
class SkybindR1ClientSafetyInstrumentationTest {
    @Test fun deniesPathEscapeBeforeConnecting() {
        try {
            SkybindR1ReadClient.read("rclone.lsjson", "approved", "../private")
            fail("Traversal accepted")
        } catch (_: IllegalArgumentException) { /* expected before IPC */ }
    }
    @Test fun deniesWriteOperationsBeforeConnecting() {
        try {
            SkybindR1ReadClient.read("rclone.copy", "approved", "")
            fail("Write accepted")
        } catch (_: IllegalArgumentException) { /* expected before IPC */ }
    }
}
