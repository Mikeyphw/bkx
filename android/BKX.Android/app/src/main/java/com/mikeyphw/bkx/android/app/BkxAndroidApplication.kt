package com.mikeyphw.bkx.android.app

import android.app.Application
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph

class BkxAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        // libsu's privileged service has its own lifecycle. Only the ordinary
        // application process reconciles persisted interrupted operations.
        if (Process.myUid() != ROOT_UID) {
            DefaultBackendGraph.operationCoordinator(this).recoverInterruptedOperations()
        }
    }

    private companion object {
        const val ROOT_UID = 0
    }
}
