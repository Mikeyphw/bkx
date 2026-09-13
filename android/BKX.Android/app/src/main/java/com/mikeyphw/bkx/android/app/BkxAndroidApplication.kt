package com.mikeyphw.bkx.android.app

import android.app.Application
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph

class BkxAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Side-effect-light startup: install the process runtime owner only.
        // Recovery, pending automation and schedule reconciliation run after an
        // authorized ingress surface asks the runtime barrier to initialize.
        DefaultBackendGraph.applicationRuntime(this).markApplicationCreated()
    }
}
