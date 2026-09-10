package com.mikeyphw.bkx.android.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BkxAutomationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AutomationDispatcher.recoverPending(context)
                BkxSavedJobScheduler.reconcile(context)
            }
            ACTION_RECONCILE_SCHEDULES -> BkxSavedJobScheduler.reconcile(context)
        }
    }

    companion object {
        const val ACTION_RECONCILE_SCHEDULES = "com.mikeyphw.bkx.android.action.RECONCILE_SCHEDULES"
    }
}
