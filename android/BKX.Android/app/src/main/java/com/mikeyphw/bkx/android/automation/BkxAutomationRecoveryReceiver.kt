package com.mikeyphw.bkx.android.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.app.runtime.BkxStartupIngress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BkxAutomationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent?.action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        DefaultBackendGraph.applicationRuntime(context)
                            .awaitReadyForExecution(BkxStartupIngress.RecoveryBroadcast)
                    }
                    ACTION_RECONCILE_SCHEDULES -> {
                        DefaultBackendGraph.applicationRuntime(context)
                            .awaitReadyForExecution(BkxStartupIngress.RecoveryBroadcast)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECONCILE_SCHEDULES = "com.mikeyphw.bkx.android.action.RECONCILE_SCHEDULES"
    }
}
