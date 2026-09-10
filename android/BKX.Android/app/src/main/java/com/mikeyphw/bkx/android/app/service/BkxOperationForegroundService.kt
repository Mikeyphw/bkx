package com.mikeyphw.bkx.android.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.mikeyphw.bkx.android.R
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.app.notifications.AndroidNotificationPermissionChecker
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSettingsStore
import com.mikeyphw.bkx.android.core.notifications.BkxNotificationIds
import com.mikeyphw.bkx.android.core.notifications.BkxNotificationPermissionStatus
import com.mikeyphw.bkx.android.core.notifications.BkxOperationNotificationChannels
import com.mikeyphw.bkx.android.core.notifications.BkxOperationNotificationDecision
import com.mikeyphw.bkx.android.core.notifications.BkxOperationNotificationPlanner
import com.mikeyphw.bkx.android.core.notifications.OperationNotificationPolicy
import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.core.settings.LogPrivacyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * User-visible service shell for active BKX operations.
 *
 * BKX executes in the process-scoped coordinator. This service owns only the
 * Android foreground lifecycle and the system-visible progress surface. The
 * same persisted ActiveOperation drives Compose, the notification shade and,
 * on Android 16+, the progress-centric/live-update surfaces.
 */
class BkxOperationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeRequestId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL_OPERATION) {
            serviceScope.launch {
                runCatching {
                    DefaultBackendGraph.operationCoordinator(this@BkxOperationForegroundService).cancel(
                        requestId = requestId,
                        cancelRequestId = "notification-cancel-${System.currentTimeMillis()}",
                    )
                }
            }
            return START_NOT_STICKY
        }

        val operation = intent?.getStringExtra(EXTRA_OPERATION).orEmpty().ifBlank { "operation" }
        activeRequestId = requestId
        val activeOperation = ActiveOperation(
            requestId = requestId,
            operation = operation,
            status = ActiveOperationStatus.Running,
            startedAtEpochMillis = intent?.getLongExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, System.currentTimeMillis())
                ?: System.currentTimeMillis(),
            lastSeenEpochMillis = System.currentTimeMillis(),
            lastPhase = intent?.getStringExtra(EXTRA_PHASE).orEmpty().ifBlank { "running" },
            lastMessage = intent?.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "BKX operation is active." },
            progress = intent?.takeIf { it.hasExtra(EXTRA_PROGRESS) }?.getDoubleExtra(EXTRA_PROGRESS, 0.0),
            filesDone = intent?.takeIf { it.hasExtra(EXTRA_FILES_DONE) }?.getLongExtra(EXTRA_FILES_DONE, 0L),
            filesTotal = intent?.takeIf { it.hasExtra(EXTRA_FILES_TOTAL) }?.getLongExtra(EXTRA_FILES_TOTAL, 0L),
            bytesDone = intent?.takeIf { it.hasExtra(EXTRA_BYTES_DONE) }?.getLongExtra(EXTRA_BYTES_DONE, 0L),
            bytesTotal = intent?.takeIf { it.hasExtra(EXTRA_BYTES_TOTAL) }?.getLongExtra(EXTRA_BYTES_TOTAL, 0L),
            cancelFilePath = intent?.getStringExtra(EXTRA_CANCEL_FILE_PATH),
        )

        val permission = AndroidNotificationPermissionChecker.status(this)
        // Android requires an FGS notification even when POST_NOTIFICATIONS is
        // denied. Keep the foreground lifecycle legal; the UI separately asks
        // for notification permission before the first user-visible operation.
        val fgsPermission = if (
            permission == BkxNotificationPermissionStatus.Denied ||
            permission == BkxNotificationPermissionStatus.Unknown
        ) {
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

        ensureOperationChannel(this)
        val notification = buildNotification(activeOperation, decision.plan.title, decision.plan.text, decision.plan.progressPercent, decision.plan.allowCancelAction)
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

    private fun buildNotification(
        operation: ActiveOperation,
        title: String,
        text: String,
        progressPercent: Int?,
        allowCancelAction: Boolean,
    ): Notification {
        val builder = notificationBuilder(this)
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
            .setWhen(operation.startedAtEpochMillis)
            .setUsesChronometer(true)

        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            applyAndroid16ProgressStyle(builder, progressPercent ?: 0)
            // EXTRA_REQUEST_PROMOTED_ONGOING was named in the 36.1 SDK. Use
            // the stable wire key so compileSdk 36.0 can still request Live
            // Update treatment without depending on a 36.1 symbol.
            builder.addExtras(Bundle().apply { putBoolean(PROMOTED_ONGOING_EXTRA, true) })
        }
        if (allowCancelAction) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher),
                    "Cancel",
                    cancelPendingIntent(this, operation),
                ).build(),
            )
        }
        return builder.build()
    }

    @RequiresApi(36)
    private fun applyAndroid16ProgressStyle(builder: Notification.Builder, progressPercent: Int) {
        builder.setStyle(
            Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(progressPercent.coerceIn(0, 100)),
        )
    }

    companion object {
        const val EXTRA_REQUEST_ID = "com.mikeyphw.bkx.android.extra.REQUEST_ID"
        const val EXTRA_OPERATION = "com.mikeyphw.bkx.android.extra.OPERATION"
        const val EXTRA_STARTED_AT_EPOCH_MILLIS = "com.mikeyphw.bkx.android.extra.STARTED_AT_EPOCH_MILLIS"
        const val EXTRA_PHASE = "com.mikeyphw.bkx.android.extra.PHASE"
        const val EXTRA_MESSAGE = "com.mikeyphw.bkx.android.extra.MESSAGE"
        const val EXTRA_PROGRESS = "com.mikeyphw.bkx.android.extra.PROGRESS"
        const val EXTRA_FILES_DONE = "com.mikeyphw.bkx.android.extra.FILES_DONE"
        const val EXTRA_FILES_TOTAL = "com.mikeyphw.bkx.android.extra.FILES_TOTAL"
        const val EXTRA_BYTES_DONE = "com.mikeyphw.bkx.android.extra.BYTES_DONE"
        const val EXTRA_BYTES_TOTAL = "com.mikeyphw.bkx.android.extra.BYTES_TOTAL"
        const val EXTRA_CANCEL_FILE_PATH = "com.mikeyphw.bkx.android.extra.CANCEL_FILE_PATH"
        const val ACTION_RECONNECT_OPERATION = "com.mikeyphw.bkx.android.action.RECONNECT_OPERATION"
        const val ACTION_CANCEL_OPERATION = "com.mikeyphw.bkx.android.action.CANCEL_OPERATION"
        private const val PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"

        fun intentFor(context: Context, operation: ActiveOperation): Intent = Intent(context, BkxOperationForegroundService::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, operation.requestId)
            putExtra(EXTRA_OPERATION, operation.operation)
            putExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, operation.startedAtEpochMillis)
            putExtra(EXTRA_PHASE, operation.lastPhase)
            putExtra(EXTRA_MESSAGE, operation.lastMessage)
            operation.progress?.let { putExtra(EXTRA_PROGRESS, it) }
            operation.filesDone?.let { putExtra(EXTRA_FILES_DONE, it) }
            operation.filesTotal?.let { putExtra(EXTRA_FILES_TOTAL, it) }
            operation.bytesDone?.let { putExtra(EXTRA_BYTES_DONE, it) }
            operation.bytesTotal?.let { putExtra(EXTRA_BYTES_TOTAL, it) }
            operation.cancelFilePath?.let { putExtra(EXTRA_CANCEL_FILE_PATH, it) }
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

        fun publishTerminal(context: Context, operation: ActiveOperation, result: OperationResultUiState) {
            val appContext = context.applicationContext ?: context
            val permission = AndroidNotificationPermissionChecker.status(appContext)
            if (permission == BkxNotificationPermissionStatus.Denied || permission == BkxNotificationPermissionStatus.Unknown) return
            ensureOperationChannel(appContext)
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val builder = notificationBuilder(appContext)
            val title = when (result) {
                is OperationResultUiState.Success -> "${displayOperation(operation.operation)} complete"
                is OperationResultUiState.Cancelled -> "${displayOperation(operation.operation)} cancelled"
                is OperationResultUiState.Failed -> "${displayOperation(operation.operation)} failed"
                OperationResultUiState.NotStarted -> return
            }
            val privacyMode = SharedPreferencesSettingsStore(appContext).readNow().logPrivacyMode == LogPrivacyMode.Private
            val text = when (result) {
                is OperationResultUiState.Success -> result.summary
                is OperationResultUiState.Cancelled -> result.summary
                is OperationResultUiState.Failed -> if (privacyMode) result.failure.title else result.failure.message
                OperationResultUiState.NotStarted -> return
            }.lineSequence().firstOrNull().orEmpty().let { line ->
                if (privacyMode) redactNotificationText(line) else line
            }.take(180)
            val reconnect = PendingIntent.getActivity(
                appContext,
                operation.requestId.hashCode(),
                reconnectIntentFor(appContext, operation),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(reconnect)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
            manager.notify(BkxNotificationIds.terminalFor(operation.requestId), builder.build())
        }

        private fun cancelPendingIntent(context: Context, operation: ActiveOperation): PendingIntent = PendingIntent.getService(
            context,
            operation.requestId.hashCode() xor 0x43414e43,
            Intent(context, BkxOperationForegroundService::class.java).apply {
                action = ACTION_CANCEL_OPERATION
                putExtra(EXTRA_REQUEST_ID, operation.requestId)
                putExtra(EXTRA_OPERATION, operation.operation)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun notificationBuilder(context: Context): Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, BkxOperationNotificationChannels.OPERATIONS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        private fun ensureOperationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    BkxOperationNotificationChannels.OPERATIONS,
                    "BKX operations",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress and results for user-initiated BKX operations."
                },
            )
        }

        private fun redactNotificationText(text: String): String = text
            .replace(Regex("/data/(?:data|user/\\d+)/[^ ]+"), "private path")
            .replace(Regex("/storage/[^ ]+"), "storage path")

        private fun displayOperation(operation: String): String = when (operation) {
            "backup.create" -> "Backup"
            "backup.snapshot" -> "Snapshot backup"
            "repository.init" -> "Repository setup"
            "archive.verify" -> "Archive verification"
            "restore.extract" -> "Restore"
            "transfer.run" -> "Transfer"
            else -> "BKX operation"
        }
    }
}
