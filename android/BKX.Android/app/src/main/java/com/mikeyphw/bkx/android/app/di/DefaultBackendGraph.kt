package com.mikeyphw.bkx.android.app.di

import android.content.Context
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
import com.mikeyphw.bkx.android.app.store.SharedPreferencesOperationStateStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesRunSummaryStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSettingsStore
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.files.ProviderStagingPort
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import com.mikeyphw.bkx.android.embedded.ChaquopyPythonGateway
import com.mikeyphw.bkx.android.embedded.EmbeddedBkxBackend
import com.mikeyphw.bkx.android.embedded.RootAwarePythonGateway
import com.mikeyphw.bkx.android.files.AndroidProviderStagingManager
import com.mikeyphw.bkx.android.root.RootBkxServiceClient
import com.mikeyphw.bkx.android.storage.AndroidStorageAccessManager

/** Process-scoped graph: one root client/backend/coordinator per Android process. */
object DefaultBackendGraph {
    @Volatile private var backendInstance: BackendPort? = null
    @Volatile private var runStoreInstance: RunSummaryStore? = null
    @Volatile private var operationStoreInstance: OperationStateStore? = null
    @Volatile private var settingsStoreInstance: SharedPreferencesSettingsStore? = null
    @Volatile private var coordinatorInstance: BkxOperationCoordinator? = null
    @Volatile private var providerStagingInstance: ProviderStagingPort? = null

    fun backendPort(context: Context): BackendPort = backendInstance ?: synchronized(this) {
        backendInstance ?: run {
            val appContext = context.applicationContext
            val settings = sharedSettingsStore(appContext)
            val local = ChaquopyPythonGateway(appContext)
            val root = RootBkxServiceClient(appContext)
            EmbeddedBkxBackend(
                gateway = RootAwarePythonGateway(
                    localGateway = local,
                    rootClient = root,
                    modeProvider = { settings.readNow().rootAccessMode },
                ),
            ).also { backendInstance = it }
        }
    }

    fun runSummaryStore(context: Context): RunSummaryStore = runStoreInstance ?: synchronized(this) {
        runStoreInstance ?: SharedPreferencesRunSummaryStore(context.applicationContext).also { runStoreInstance = it }
    }

    fun operationStateStore(context: Context): OperationStateStore = operationStoreInstance ?: synchronized(this) {
        operationStoreInstance ?: SharedPreferencesOperationStateStore(context.applicationContext).also { operationStoreInstance = it }
    }

    fun settingsStore(context: Context): SettingsStore = sharedSettingsStore(context.applicationContext)

    fun providerStaging(context: Context): ProviderStagingPort = providerStagingInstance ?: synchronized(this) {
        providerStagingInstance ?: AndroidProviderStagingManager(context.applicationContext).also { providerStagingInstance = it }
    }

    fun archiveSearchRoots(context: Context, rootAvailable: Boolean): List<String> =
        AndroidStorageAccessManager(context.applicationContext).archiveSearchRoots(rootAvailable)

    fun operationCoordinator(context: Context): BkxOperationCoordinator = coordinatorInstance ?: synchronized(this) {
        coordinatorInstance ?: BkxOperationCoordinator(
            context = context.applicationContext,
            backendPort = backendPort(context),
            runSummaryStore = runSummaryStore(context),
            operationStateStore = operationStateStore(context),
            requestBaseDirectoryPath = context.applicationContext.filesDir.resolve("bkx/requests").absolutePath,
            progressPreferenceProvider = { sharedSettingsStore(context.applicationContext).readNow().progressPreference },
        ).also { coordinatorInstance = it }
    }

    private fun sharedSettingsStore(context: Context): SharedPreferencesSettingsStore = settingsStoreInstance ?: synchronized(this) {
        settingsStoreInstance ?: SharedPreferencesSettingsStore(context.applicationContext).also { settingsStoreInstance = it }
    }
}
