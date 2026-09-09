package com.mikeyphw.bkx.android.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mikeyphw.bkx.android.R
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

class BkxAutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingDispatches = ConcurrentLinkedQueue<Pair<String, Int>>()
    private val processing = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(BkxAutomationContract.EXTRA_DISPATCH_ID).orEmpty()
        val store = AutomationDispatchStore(this)
        val ids = buildList {
            if (requestedId.isNotBlank()) add(requestedId)
            addAll(store.pendingIds())
        }.distinct()
        if (ids.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val first = ids.firstNotNullOfOrNull(store::read)
        if (first == null) {
            ids.forEach(store::remove)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Starting ${first.request.action.label}…"))
        ids.forEach { id ->
            if (store.read(id) != null && pendingDispatches.none { it.first == id }) pendingDispatches.add(id to startId)
        }
        drainQueue()
        return START_REDELIVER_INTENT
    }

    private fun drainQueue() {
        if (!processing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val store = AutomationDispatchStore(this@BkxAutomationService)
                while (true) {
                    val (dispatchId, _) = pendingDispatches.poll() ?: break
                    val dispatch = store.read(dispatchId) ?: continue
                    updateNotification("Starting ${dispatch.request.action.label}…")
                    val result = DefaultBackendGraph.automationEngine(this@BkxAutomationService).execute(
                        dispatch.request,
                        AutomationProgressSink { stage -> updateNotification(stage) },
                    )
                    AutomationResultDelivery.deliver(this@BkxAutomationService, dispatch, result)
                    store.remove(dispatch.dispatchId)
                }
            } finally {
                processing.set(false)
                if (pendingDispatches.isNotEmpty()) {
                    drainQueue()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(stage: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(stage))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("BKX automation")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BKX automation", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "bkx_automation"
        private const val NOTIFICATION_ID = 0xB6A1

        fun intentFor(context: Context, dispatchId: String): Intent = Intent(context, BkxAutomationService::class.java)
            .putExtra(BkxAutomationContract.EXTRA_DISPATCH_ID, dispatchId)
    }
}

object AutomationResultDelivery {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    fun deliver(context: Context, dispatch: AutomationDispatch, result: AutomationResult) {
        if (dispatch.taskerCompletionIntentUri.isNotBlank()) deliverTasker(context, dispatch.taskerCompletionIntentUri, result)
        if (dispatch.callbackPackage.isNotBlank() && dispatch.callbackAction.isNotBlank()) deliverDirect(context, dispatch, result)
    }

    private fun deliverTasker(context: Context, uri: String, result: AutomationResult) {
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()?.let { completion ->
            completion.putExtra(
                TaskerPluginContract.EXTRA_RESULT_CODE,
                if (result.successful) TaskerPluginContract.RESULT_CODE_OK else TaskerPluginContract.RESULT_CODE_FAILED,
            )
            completion.putExtra(TaskerPluginContract.EXTRA_VARIABLES, result.taskerVariables())
            context.sendBroadcast(completion)
        }
    }

    private fun deliverDirect(context: Context, dispatch: AutomationDispatch, result: AutomationResult) {
        context.sendBroadcast(
            Intent(dispatch.callbackAction).apply {
                setPackage(dispatch.callbackPackage)
                putExtra(BkxAutomationContract.EXTRA_RESULT_JSON, json.encodeToString(AutomationResult.serializer(), result))
                putExtra(BkxAutomationContract.EXTRA_REQUEST_ID, result.requestId)
                putExtra("status", result.status)
                putExtra("error_code", result.errorCode)
                putExtra("error_message", result.errorMessage)
                putExtra("summary", result.summary)
            },
        )
    }
}
