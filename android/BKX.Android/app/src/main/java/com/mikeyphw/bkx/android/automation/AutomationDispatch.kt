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
    fun put(dispatch: AutomationDispatch) {
        val ids = pendingIds().toMutableList().apply { remove(dispatch.dispatchId); add(dispatch.dispatchId) }
        preferences.edit(commit = true) {
            putString(dispatch.dispatchId, json.encodeToString(dispatch))
            putString(KEY_INDEX, ids.joinToString("\n"))
        }
    }

    fun read(dispatchId: String): AutomationDispatch? = preferences.getString(dispatchId, null)?.let { encoded ->
        runCatching { json.decodeFromString<AutomationDispatch>(encoded) }.getOrNull()
    }

    fun pendingIds(): List<String> = preferences.getString(KEY_INDEX, "").orEmpty().lineSequence().filter(String::isNotBlank).distinct().toList()

    @SuppressLint("ApplySharedPref")
    fun remove(dispatchId: String) {
        val ids = pendingIds().filterNot { it == dispatchId }
        preferences.edit(commit = true) {
            remove(dispatchId)
            putString(KEY_INDEX, ids.joinToString("\n"))
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "bkx_automation_dispatch"
        const val KEY_INDEX = "dispatch_index"
    }
}

object AutomationDispatcher {
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
        val info = JobInfo.Builder(dispatchId.hashCode() and 0x7fffffff, ComponentName(context, BkxAutomationJobService::class.java))
            .setExtras(extras)
            .setOverrideDeadline(0L)
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
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
