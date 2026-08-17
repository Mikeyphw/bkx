
package com.mikeyphw.bkx.android.core.notifications

import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxOperationNotificationPlannerTest {
    private val active = ActiveOperation(
        requestId = "req-notify-1",
        operation = "backup.create",
        status = ActiveOperationStatus.Running,
        startedAtEpochMillis = 1_786_000_000_000,
        lastSeenEpochMillis = 1_786_000_001_000,
        lastPhase = "copying",
        lastMessage = "Copying files",
        progress = 0.42,
        cancelFilePath = "/shared/bkx/cancel/req-notify-1.json",
    )

    @Test
    fun grantedPermissionCreatesRedactedUserVisiblePlan() {
        val decision = BkxOperationNotificationPlanner.plan(
            operation = active,
            permissionStatus = BkxNotificationPermissionStatus.Granted,
            policy = OperationNotificationPolicy(enabled = true, privacyMode = true),
        )

        val plan = (decision as BkxOperationNotificationDecision.Show).plan
        assertEquals("bkx_operations", plan.channelId)
        assertEquals(42, plan.progressPercent)
        assertTrue(plan.ongoing)
        assertTrue(plan.allowCancelAction)
        assertTrue(plan.redacted)
        assertFalse(plan.text.contains(active.requestId))
        assertFalse(plan.text.contains(active.cancelFilePath.orEmpty()))
    }

    @Test
    fun deniedPermissionBlocksNotificationBeforeForegroundOperation() {
        val decision = BkxOperationNotificationPlanner.plan(
            operation = active,
            permissionStatus = BkxNotificationPermissionStatus.Denied,
        )

        val blocked = decision as BkxOperationNotificationDecision.Blocked
        assertEquals("notification_permission_required", blocked.code)
        assertTrue(blocked.userAction.contains("Grant", ignoreCase = true))
    }
}
