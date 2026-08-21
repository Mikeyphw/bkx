
package com.mikeyphw.bkx.android.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mikeyphw.bkx.android.R
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.app.notifications.AndroidNotificationPermissionChecker
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSettingsStore
import com.mikeyphw.bkx.android.core.notifications.BkxNotificationPermissionStatus
import com.mikeyphw.bkx.android.core.notifications.BkxNotificationIds
import com.mikeyphw.bkx.android.core.notifications.BkxOperationNotificationChannels
import com.mikeyphw.bkx.android.core.notifications.BkxOperationNotificationDecision
import com.mikeyphw.bkx.android.core.notifications.BkxOperationNotificationPlanner
import com.mikeyphw.bkx.android.core.notifications.OperationNotificationPolicy
import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.core.settings.LogPrivacyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * User-visible service shell for active BKX operations.
 *
 * BKX runs through the embedded root-aware engine. This service does not
 * execute backup/archive/restore/transfer logic; it only supplies Android's
 * required visible lifecycle/notification surface for a request id that the app
 * can later reconnect to through persisted run state.
 */
class BkxOperationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeRequestId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val operation = intent?.getStringExtra(EXTRA_OPERATION).orEmpty().ifBlank { "operation" }
        if (requestId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeRequestId = requestId

        val activeOperation = ActiveOperation(
            requestId = requestId,
            operation = operation,
            status = ActiveOperationStatus.Running,
            startedAtEpochMillis = intent?.getLongExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, System.currentTimeMillis())
                ?: System.currentTimeMillis(),
            lastSeenEpochMillis = System.currentTimeMillis(),
            lastPhase = intent?.getStringExtra(EXTRA_PHASE).orEmpty().ifBlank { "running" },
            lastMessage = "BKX operation is active.",
            progress = intent?.takeIf { it.hasExtra(EXTRA_PROGRESS) }?.getDoubleExtra(EXTRA_PROGRESS, 0.0),
            cancelFilePath = intent?.getStringExtra(EXTRA_CANCEL_FILE_PATH),
        )
        val permission = AndroidNotificationPermissionChecker.status(this)
        // Android still requires an FGS notification even when the app-level
        // POST_NOTIFICATIONS permission is denied. Keep the service alive and
        // let the system surface the mandatory FGS disclosure.
        val fgsPermission = if (permission == BkxNotificationPermissionStatus.Denied || permission == BkxNotificationPermissionStatus.Unknown) {
            BkxNotificationPermissionStatus.NotRequired
        } else {
            permission
        }
        val privacyMode = SharedPreferencesSettingsStore(this).readNow().logPrivacyMode == LogPrivacyMode.Private
        val decision = BkxOperationNotificationPlanner.plan(
            operation = activeOperation,
            permissionStatus = fgsPermission,
            policy = OperationNotificationPolicy(enabled = true, privacyMode = privacyMode),
        )
        if (decision !is BkxOperationNotificationDecision.Show) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureOperationChannel()
        val notification = buildNotification(activeOperation, decision.plan.title, decision.plan.text, decision.plan.progressPercent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(BkxNotificationIds.OPERATION_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(BkxNotificationIds.OPERATION_FOREGROUND, notification)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val requestId = activeRequestId
        if (requestId == null) {
            stopSelf(startId)
            return
        }
        // A data-sync FGS timeout must not leave the Python operation running
        // unprotected in the background. Route the timeout through the same
        // cooperative cancellation path used by the product UI.
        serviceScope.launch {
            runCatching {
                DefaultBackendGraph.operationCoordinator(this@BkxOperationForegroundService).cancel(
                    requestId = requestId,
                    cancelRequestId = "fgs-timeout-${System.currentTimeMillis()}",
                )
            }
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        activeRequestId = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureOperationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            BkxOperationNotificationChannels.OPERATIONS,
            "BKX operations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for active embedded BKX operations."
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(operation: ActiveOperation, title: String, text: String, progressPercent: Int?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, BkxOperationNotificationChannels.OPERATIONS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val reconnectPendingIntent = PendingIntent.getActivity(
            this,
            operation.requestId.hashCode(),
            reconnectIntentFor(this, operation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(reconnectPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "com.mikeyphw.bkx.android.extra.REQUEST_ID"
        const val EXTRA_OPERATION = "com.mikeyphw.bkx.android.extra.OPERATION"
        const val EXTRA_STARTED_AT_EPOCH_MILLIS = "com.mikeyphw.bkx.android.extra.STARTED_AT_EPOCH_MILLIS"
        const val EXTRA_PHASE = "com.mikeyphw.bkx.android.extra.PHASE"
        const val EXTRA_PROGRESS = "com.mikeyphw.bkx.android.extra.PROGRESS"
        const val EXTRA_CANCEL_FILE_PATH = "com.mikeyphw.bkx.android.extra.CANCEL_FILE_PATH"
        const val ACTION_RECONNECT_OPERATION = "com.mikeyphw.bkx.android.action.RECONNECT_OPERATION"

        fun intentFor(context: Context, operation: ActiveOperation): Intent = Intent(context, BkxOperationForegroundService::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, operation.requestId)
            putExtra(EXTRA_OPERATION, operation.operation)
            putExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, operation.startedAtEpochMillis)
            putExtra(EXTRA_PHASE, operation.lastPhase)
            operation.progress?.let { putExtra(EXTRA_PROGRESS, it) }
            operation.cancelFilePath?.let { putExtra(EXTRA_CANCEL_FILE_PATH, it) }
        }

        fun intentFor(
            context: Context,
            requestId: String,
            operation: String,
            startedAtEpochMillis: Long,
        ): Intent = Intent(context, BkxOperationForegroundService::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_OPERATION, operation)
            putExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, startedAtEpochMillis)
            putExtra(EXTRA_PHASE, "starting")
        }


        fun reconnectIntentFor(context: Context, operation: ActiveOperation): Intent = Intent(context, com.mikeyphw.bkx.android.app.MainActivity::class.java).apply {
            action = ACTION_RECONNECT_OPERATION
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REQUEST_ID, operation.requestId)
            putExtra(EXTRA_OPERATION, operation.operation)
            putExtra(EXTRA_PHASE, operation.lastPhase)
            operation.progress?.let { putExtra(EXTRA_PROGRESS, it) }
        }

        fun reconnectRequestId(intent: Intent?): String? = intent
            ?.takeIf { it.action == ACTION_RECONNECT_OPERATION }
            ?.getStringExtra(EXTRA_REQUEST_ID)
            ?.takeIf { it.isNotBlank() }
    }
}
