
package com.mikeyphw.bkx.android.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mikeyphw.bkx.android.app.notifications.AndroidNotificationPermissionChecker
import com.mikeyphw.bkx.android.app.service.BkxOperationForegroundService
import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationNotificationInstrumentationTest {
    @Test
    fun foregroundServiceIntentCarriesReconnectableRequestId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val active = ActiveOperation(
            requestId = "req-instrumented-1",
            operation = "backup.create",
            status = ActiveOperationStatus.Running,
            startedAtEpochMillis = 1_786_000_000_000,
            lastPhase = "verifying",
            lastMessage = "Verifying archive",
            progress = 0.75,
            cancelFilePath = "/shared/bkx/cancel/req-instrumented-1.json",
        )

        val intent = BkxOperationForegroundService.intentFor(context, active)

        assertEquals("req-instrumented-1", intent.getStringExtra(BkxOperationForegroundService.EXTRA_REQUEST_ID))
        assertEquals("backup.create", intent.getStringExtra(BkxOperationForegroundService.EXTRA_OPERATION))
        assertEquals("verifying", intent.getStringExtra(BkxOperationForegroundService.EXTRA_PHASE))
        assertEquals(0.75, intent.getDoubleExtra(BkxOperationForegroundService.EXTRA_PROGRESS, -1.0), 0.0)
        assertNotNull(AndroidNotificationPermissionChecker.status(context))
    }

    @Test
    fun notificationReconnectIntentTargetsMainActivityWithRequestId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val active = ActiveOperation(
            requestId = "req-reconnect-2",
            operation = "restore.extract",
            status = ActiveOperationStatus.Running,
            startedAtEpochMillis = 1_786_000_000_000,
            lastPhase = "extracting",
            lastMessage = "Restoring selected files",
            progress = 0.5,
            cancelFilePath = "/shared/bkx/cancel/req-reconnect-2.json",
        )

        val reconnectIntent = BkxOperationForegroundService.reconnectIntentFor(context, active)

        assertEquals(BkxOperationForegroundService.ACTION_RECONNECT_OPERATION, reconnectIntent.action)
        assertEquals("req-reconnect-2", BkxOperationForegroundService.reconnectRequestId(reconnectIntent))
        assertEquals("restore.extract", reconnectIntent.getStringExtra(BkxOperationForegroundService.EXTRA_OPERATION))
        assertEquals("extracting", reconnectIntent.getStringExtra(BkxOperationForegroundService.EXTRA_PHASE))
    }

}
