package com.mikeyphw.bkx.android.app.di

import android.content.Context
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.termux.AndroidTermuxReadinessProbe
import com.mikeyphw.bkx.android.termux.TermuxBridgeAdapter
import com.mikeyphw.bkx.android.termux.TermuxRunCommandDispatcher
import com.mikeyphw.bkx.android.termux.TermuxRunCommandInvocationFactory

object DefaultBackendGraph {
    fun backendPort(context: Context): BackendPort {
        val appContext = context.applicationContext
        return TermuxBridgeAdapter(
            readinessProbe = AndroidTermuxReadinessProbe(appContext),
            dispatcher = TermuxRunCommandDispatcher(appContext),
            invocationFactory = TermuxRunCommandInvocationFactory(),
        )
    }
}
