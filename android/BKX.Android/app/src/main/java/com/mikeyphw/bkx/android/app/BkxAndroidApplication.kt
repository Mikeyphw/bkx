package com.mikeyphw.bkx.android.app

import android.app.Application
import android.os.Process
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph

class BkxAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // GO13 is Go-first on ARM64. Chaquopy remains a lazy rollback/non-ARM64 lane.
        if (Process.myUid() != ROOT_UID) {
            DefaultBackendGraph.operationCoordinator(this).recoverInterruptedOperations()
        }
    }

    private companion object {
        const val ROOT_UID = 0
    }
}
