package com.mikeyphw.bkx.android.automation

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AutomationDispatch(
    val dispatchId: String,
    val request: AutomationRequest,
    val taskerCompletionIntentUri: String = "",
    val callbackPackage: String = "",
    val callbackAction: String = "",
)

class AutomationDispatchStore(context: Context) {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("ApplySharedPref")
    fun put(dispatch: AutomationDispatch) = synchronized(dispatchLock) {
        val ids = pendingIdsLocked().toMutableList().apply { remove(dispatch.dispatchId); add(dispatch.dispatchId) }
        preferences.edit(commit = true) {
            putString(dispatch.dispatchId, json.encodeToString(dispatch))
            putString(KEY_INDEX, ids.joinToString("\n"))
        }
    }

    fun read(dispatchId: String): AutomationDispatch? = synchronized(dispatchLock) {
        preferences.getString(dispatchId, null)?.let { encoded ->
            runCatching { json.decodeFromString<AutomationDispatch>(encoded) }.getOrNull()
        }
    }

    fun pendingIds(): List<String> = synchronized(dispatchLock) { pendingIdsLocked() }

    @SuppressLint("ApplySharedPref")
    fun markDeliveryStarted(dispatchId: String): Boolean = synchronized(dispatchLock) {
        val key = "delivery_started:$dispatchId"
        if (preferences.getBoolean(key, false)) return@synchronized false
        preferences.edit(commit = true) { putBoolean(key, true) }
        true
    }

    @SuppressLint("ApplySharedPref")
    fun remove(dispatchId: String) = synchronized(dispatchLock) {
        val ids = pendingIdsLocked().filterNot { it == dispatchId }
        preferences.edit(commit = true) {
            remove(dispatchId)
            remove("delivery_started:$dispatchId")
            putString(KEY_INDEX, ids.joinToString("\n"))
        }
    }

    @SuppressLint("ApplySharedPref")
    fun clear(): Int = synchronized(dispatchLock) {
        val ids = pendingIdsLocked()
        preferences.edit(commit = true) {
            ids.forEach { id ->
                remove(id)
                remove("delivery_started:$id")
            }
            putString(KEY_INDEX, "")
        }
        ids.size
    }

    private fun pendingIdsLocked(): List<String> = preferences.getString(KEY_INDEX, "")
        .orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    private companion object {
        const val PREFERENCES_NAME = "bkx_automation_dispatch"
        const val KEY_INDEX = "dispatch_index"
        val dispatchLock = Any()
    }
}

object AutomationDispatcher {
    private const val FALLBACK_JOB_ID_PREFIX = 0x40000000
    private const val FALLBACK_JOB_ID_MASK = 0x0fffffff

    fun enqueue(context: Context, dispatch: AutomationDispatch): Boolean {
        val appContext = context.applicationContext ?: context
        AutomationDispatchStore(appContext).put(dispatch)
        return runCatching {
            ContextCompat.startForegroundService(appContext, BkxAutomationService.intentFor(appContext, dispatch.dispatchId))
            true
        }.getOrElse {
            scheduleFallback(appContext, dispatch.dispatchId)
        }
    }

    fun recoverPending(context: Context): Int {
        val appContext = context.applicationContext ?: context
        val store = AutomationDispatchStore(appContext)
        if (!AutomationAccessStore(appContext).snapshot().enabled) {
            return store.clear()
        }
        val ids = store.pendingIds()
        val state = SharedPreferencesAutomationStateStore(appContext)
        ids.forEach { id ->
            store.read(id)?.request?.requestId?.takeIf(String::isNotBlank)?.let(state::clearActive)
            scheduleFallback(appContext, id)
        }
        return ids.size
    }

    fun create(
        request: AutomationRequest,
        taskerCompletionIntentUri: String = "",
        callbackPackage: String = "",
        callbackAction: String = "",
    ): AutomationDispatch = AutomationDispatch(
        dispatchId = "dispatch-${UUID.randomUUID()}",
        request = request,
        taskerCompletionIntentUri = taskerCompletionIntentUri,
        callbackPackage = callbackPackage,
        callbackAction = callbackAction,
    )

    private fun scheduleFallback(context: Context, dispatchId: String): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val extras = PersistableBundle().apply { putString(BkxAutomationContract.EXTRA_DISPATCH_ID, dispatchId) }
        val info = JobInfo.Builder(fallbackJobId(dispatchId), ComponentName(context, BkxAutomationJobService::class.java))
            .setExtras(extras)
            .setOverrideDeadline(0L)
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    internal fun fallbackJobId(dispatchId: String): Int = FALLBACK_JOB_ID_PREFIX or (dispatchId.hashCode() and FALLBACK_JOB_ID_MASK)

    fun cancelFallback(context: Context, dispatchId: String) {
        context.getSystemService(JobScheduler::class.java)?.cancel(fallbackJobId(dispatchId))
    }
}

class BkxAutomationJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val dispatchId = params.extras.getString(BkxAutomationContract.EXTRA_DISPATCH_ID).orEmpty()
        val started = dispatchId.isNotBlank() && runCatching {
            ContextCompat.startForegroundService(this, BkxAutomationService.intentFor(this, dispatchId))
            true
        }.getOrDefault(false)
        if (!started && dispatchId.isNotBlank()) {
            AutomationDispatchStore(this).read(dispatchId)?.let { dispatch ->
                AutomationResultDelivery.deliver(
                    this,
                    dispatch,
                    AutomationResult(
                        dispatch.request.requestId.ifBlank { "automation-dispatch-failed" },
                        dispatch.request.action,
                        "failure",
                        summary = "Android blocked BKX automation background execution.",
                        errorCode = "background_start_blocked",
                        errorMessage = "Android blocked BKX automation background execution. Open BKX once and retry the Tasker action.",
                    ),
                )
                AutomationDispatchStore(this).remove(dispatchId)
            }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
