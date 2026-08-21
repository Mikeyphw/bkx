package com.mikeyphw.bkx.android.app.di

import android.content.Context
import com.mikeyphw.bkx.android.app.store.SharedPreferencesOperationStateStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesRunSummaryStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSettingsStore
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import com.mikeyphw.bkx.android.embedded.ChaquopyPythonGateway
import com.mikeyphw.bkx.android.embedded.EmbeddedBkxBackend
import com.mikeyphw.bkx.android.embedded.RootAwarePythonGateway
import com.mikeyphw.bkx.android.root.RootBkxServiceClient

object DefaultBackendGraph {
    fun backendPort(context: Context): BackendPort {
        val appContext = context.applicationContext
        val local = ChaquopyPythonGateway(appContext)
        val root = RootBkxServiceClient(appContext)
        val rootMode = SharedPreferencesSettingsStore(appContext).readNow().rootAccessMode
        return EmbeddedBkxBackend(
            gateway = RootAwarePythonGateway(
                localGateway = local,
                rootClient = root,
                mode = rootMode,
            ),
        )
    }

    fun runSummaryStore(context: Context): RunSummaryStore = SharedPreferencesRunSummaryStore(context.applicationContext)

    fun operationStateStore(context: Context): OperationStateStore = SharedPreferencesOperationStateStore(context.applicationContext)

    fun settingsStore(context: Context): SettingsStore = SharedPreferencesSettingsStore(context.applicationContext)
}
