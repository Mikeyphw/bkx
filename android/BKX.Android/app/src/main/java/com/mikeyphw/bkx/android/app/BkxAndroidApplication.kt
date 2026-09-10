package com.mikeyphw.bkx.android.app

import android.app.Application
import android.os.Process
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.automation.AutomationDispatcher
import com.mikeyphw.bkx.android.automation.BkxSavedJobScheduler

class BkxAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // GO14 Android is Go-only; there is no embedded Python runtime to initialize.
        if (Process.myUid() != ROOT_UID) {
            DefaultBackendGraph.operationCoordinator(this).recoverInterruptedOperations()
            AutomationDispatcher.recoverPending(this)
            BkxSavedJobScheduler.reconcile(this)
        }
    }

    private companion object {
        const val ROOT_UID = 0
    }
}
