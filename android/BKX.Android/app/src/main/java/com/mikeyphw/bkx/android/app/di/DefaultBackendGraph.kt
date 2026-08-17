package com.mikeyphw.bkx.android.app.di

import android.content.Context
import com.mikeyphw.bkx.android.app.store.SharedPreferencesOperationStateStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesRunSummaryStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSettingsStore
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.SettingsStore
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

    fun runSummaryStore(context: Context): RunSummaryStore = SharedPreferencesRunSummaryStore(context.applicationContext)

    fun operationStateStore(context: Context): OperationStateStore = SharedPreferencesOperationStateStore(context.applicationContext)

    fun settingsStore(context: Context): SettingsStore = SharedPreferencesSettingsStore(context.applicationContext)
}
