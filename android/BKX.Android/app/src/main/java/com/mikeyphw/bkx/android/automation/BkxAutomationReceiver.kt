package com.mikeyphw.bkx.android.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

class BkxAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocalePluginContract.ACTION_FIRE_SETTING) {
            handleTasker(context, intent)
        } else {
            handleDirect(context, intent)
        }
    }

    private fun handleTasker(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val sender = sentFromPackage
            if (sender != null && sender !in TRUSTED_TASKER_HOSTS) {
                failOrdered("automation_untrusted_host", "BKX rejected Tasker plugin fire from $sender.")
                return
            }
        }
        val bundle = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        if (bundle == null) {
            failOrdered("automation_missing_bundle", "BKX Tasker configuration bundle is missing.")
            return
        }
        if (!AutomationAccessStore(context).isTaskerAuthorized(bundle.getString(BkxAutomationContract.EXTRA_ACCESS_TOKEN))) {
            failOrdered("automation_unauthorized", "BKX automation is disabled or the Tasker capability token is stale. Re-edit the BKX Tasker action.")
            return
        }
        val request = AutomationIntentCodec.fromTaskerBundle(bundle)
        if (request == null) {
            failOrdered("automation_invalid_action", "BKX Tasker action is invalid.")
            return
        }
        val normalized = AutomationIntentCodec.normalizeRequestId(request)
        normalized.validate().firstOrNull()?.let { message ->
            failOrdered("automation_invalid_request", message)
            return
        }
        val completionUri = intent.getStringExtra(TaskerPluginContract.EXTRA_COMPLETION_INTENT).orEmpty()
        val dispatch = AutomationDispatcher.create(normalized, taskerCompletionIntentUri = completionUri)
        if (!AutomationDispatcher.enqueue(context, dispatch)) {
            failOrdered("automation_dispatch_failed", "Android refused to enqueue BKX automation.")
            return
        }
        val queued = AutomationResult(normalized.requestId, normalized.action, "running", summary = "BKX automation queued.")
        setResultExtras(Bundle().apply { putBundle(TaskerPluginContract.EXTRA_VARIABLES, queued.taskerVariables()) })
        resultCode = if (bundle.getBoolean(BkxAutomationContract.EXTRA_WAIT_FOR_RESULT, true) && completionUri.isNotBlank()) {
            TaskerPluginContract.RESULT_CODE_PENDING
        } else {
            TaskerPluginContract.RESULT_CODE_OK
        }
    }

    private fun handleDirect(context: Context, intent: Intent) {
        if (intent.action !in BkxAutomationContract.directActions) return
        if (!AutomationAccessStore(context).isAuthorized(intent.getStringExtra(BkxAutomationContract.EXTRA_ACCESS_TOKEN))) {
            failOrdered("automation_unauthorized", "BKX automation is disabled or the capability token is invalid.")
            return
        }
        val request = AutomationIntentCodec.fromDirectIntent(intent)
        if (request == null) {
            failOrdered("automation_invalid_action", "Unknown BKX automation action.")
            return
        }
        val normalized = AutomationIntentCodec.normalizeRequestId(request)
        normalized.validate().firstOrNull()?.let { message ->
            failOrdered("automation_invalid_request", message)
            return
        }
        val callbackPackage = intent.getStringExtra(BkxAutomationContract.EXTRA_CALLBACK_PACKAGE).orEmpty()
        val callbackAction = intent.getStringExtra(BkxAutomationContract.EXTRA_CALLBACK_ACTION).orEmpty()
        if (callbackPackage.isBlank() != callbackAction.isBlank()) {
            failOrdered("automation_invalid_callback", "Callback package and callback action must be supplied together.")
            return
        }
        val dispatch = AutomationDispatcher.create(normalized, callbackPackage = callbackPackage, callbackAction = callbackAction)
        if (!AutomationDispatcher.enqueue(context, dispatch)) {
            failOrdered("automation_dispatch_failed", "Android refused to enqueue BKX automation.")
            return
        }
        resultCode = android.app.Activity.RESULT_OK
        setResultExtras(Bundle().apply {
            putString(BkxAutomationContract.EXTRA_REQUEST_ID, normalized.requestId)
            putString("status", "running")
        })
    }

    private fun failOrdered(code: String, message: String) {
        resultCode = TaskerPluginContract.RESULT_CODE_FAILED
        setResultExtras(Bundle().apply {
            putString("error_code", code)
            putString("error_message", message)
        })
    }

    private companion object {
        val TRUSTED_TASKER_HOSTS = setOf("net.dinglisch.android.taskerm", "com.twofortyfouram.locale")
    }
}
