package com.mikeyphw.bkx.android.app.di

import android.content.Context
import com.mikeyphw.bkx.android.app.runtime.BkxApplicationRuntime
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
import com.mikeyphw.bkx.android.automation.BkxAutomationEngine
import com.mikeyphw.bkx.android.automation.BkxTaskerConditionPublisher
import com.mikeyphw.bkx.android.automation.BkxTaskerConditionStore
import com.mikeyphw.bkx.android.automation.SharedPreferencesBkxTaskerConditionStore
import com.mikeyphw.bkx.android.automation.CoordinatorAutomationOperationPort
import com.mikeyphw.bkx.android.automation.GoogleManagedDriveRefreshPort
import com.mikeyphw.bkx.android.automation.SharedPreferencesAutomationStateStore
import com.mikeyphw.bkx.android.app.drive.GoogleDriveAuthorizationManager
import com.mikeyphw.bkx.android.app.store.SharedPreferencesOperationStateStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesRunSummaryStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSettingsStore
import com.mikeyphw.bkx.android.app.store.SharedPreferencesSavedJobStore
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgePort
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessPort
import com.mikeyphw.bkx.android.core.files.ProviderStagingPort
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.jobs.SavedJobStore
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRoot
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRootResolver
import com.mikeyphw.bkx.android.embedded.EmbeddedBkxBackend
import com.mikeyphw.bkx.android.embedded.GoNativeGateway
import com.mikeyphw.bkx.android.embedded.RootAwareGateway
import com.mikeyphw.bkx.android.files.AndroidProviderStagingManager
import com.mikeyphw.bkx.android.root.RootBkxServiceClient
import com.mikeyphw.bkx.android.root.RootChrootctlBridgePort
import com.mikeyphw.bkx.android.root.ChrootctlOperationLedger
import com.mikeyphw.bkx.android.root.RootChrootctlReadinessPort
import com.mikeyphw.bkx.android.storage.AndroidStorageAccessManager

/** Process-scoped graph: one root client/backend/coordinator per Android process. */
private fun Context.stableApplicationContext(): Context = applicationContext ?: this

object DefaultBackendGraph {
    @Volatile private var backendInstance: BackendPort? = null
    @Volatile private var rootClientInstance: RootBkxServiceClient? = null
    @Volatile private var chrootctlReadinessPortInstance: ChrootctlReadinessPort? = null
    @Volatile private var chrootctlBridgePortInstance: ChrootctlBridgePort? = null
    @Volatile private var runStoreInstance: RunSummaryStore? = null
    @Volatile private var operationStoreInstance: OperationStateStore? = null
    @Volatile private var settingsStoreInstance: SharedPreferencesSettingsStore? = null
    @Volatile private var savedJobStoreInstance: SavedJobStore? = null
    @Volatile private var coordinatorInstance: BkxOperationCoordinator? = null
    @Volatile private var providerStagingInstance: ProviderStagingPort? = null
    @Volatile private var googleDriveManagerInstance: GoogleDriveAuthorizationManager? = null
    @Volatile private var automationEngineInstance: BkxAutomationEngine? = null
    @Volatile private var taskerConditionStoreInstance: BkxTaskerConditionStore? = null
    @Volatile private var taskerConditionPublisherInstance: BkxTaskerConditionPublisher? = null
    @Volatile private var applicationRuntimeInstance: BkxApplicationRuntime? = null


    fun applicationRuntime(context: Context): BkxApplicationRuntime = applicationRuntimeInstance ?: synchronized(this) {
        applicationRuntimeInstance ?: BkxApplicationRuntime(
            context = context.stableApplicationContext(),
            coordinatorProvider = { operationCoordinator(context.stableApplicationContext()) },
        ).also { applicationRuntimeInstance = it }
    }

    fun controlPlaneRoot(context: Context): BkxControlPlaneRoot =
        BkxControlPlaneRootResolver.resolve(context.stableApplicationContext())

    fun backendPort(context: Context): BackendPort = backendInstance ?: synchronized(this) {
        backendInstance ?: run {
            val appContext = context.stableApplicationContext()
            val settings = sharedSettingsStore(appContext)
            val local = GoNativeGateway(appContext)
            val root = rootClient(appContext)
            EmbeddedBkxBackend(
                gateway = RootAwareGateway(
                    localGateway = local,
                    rootClient = root,
                    modeProvider = { settings.readNow().rootAccessMode },
                ),
            ).also { backendInstance = it }
        }
    }


    fun chrootctlReadinessPort(context: Context): ChrootctlReadinessPort = chrootctlReadinessPortInstance ?: synchronized(this) {
        chrootctlReadinessPortInstance ?: run {
            val appContext = context.stableApplicationContext()
            val settings = sharedSettingsStore(appContext)
            RootChrootctlReadinessPort(
                rootClient = rootClient(appContext),
                modeProvider = { settings.readNow().rootAccessMode },
            ).also { chrootctlReadinessPortInstance = it }
        }
    }

    fun chrootctlBridgePort(context: Context): ChrootctlBridgePort = chrootctlBridgePortInstance ?: synchronized(this) {
        chrootctlBridgePortInstance ?: run {
            val appContext = context.stableApplicationContext()
            val settings = sharedSettingsStore(appContext)
            RootChrootctlBridgePort(
                rootClient = rootClient(appContext),
                readinessPort = chrootctlReadinessPort(appContext),
                modeProvider = { settings.readNow().rootAccessMode },
                operationLedger = ChrootctlOperationLedger(appContext),
            ).also { chrootctlBridgePortInstance = it }
        }
    }

    private fun rootClient(context: Context): RootBkxServiceClient = rootClientInstance ?: synchronized(this) {
        rootClientInstance ?: RootBkxServiceClient(context.stableApplicationContext()).also { rootClientInstance = it }
    }

    fun runSummaryStore(context: Context): RunSummaryStore = runStoreInstance ?: synchronized(this) {
        runStoreInstance ?: SharedPreferencesRunSummaryStore(context.stableApplicationContext()).also { runStoreInstance = it }
    }

    fun operationStateStore(context: Context): OperationStateStore = operationStoreInstance ?: synchronized(this) {
        operationStoreInstance ?: SharedPreferencesOperationStateStore(context.stableApplicationContext()).also { operationStoreInstance = it }
    }

    fun settingsStore(context: Context): SettingsStore = sharedSettingsStore(context.stableApplicationContext())

    fun savedJobStore(context: Context): SavedJobStore = savedJobStoreInstance ?: synchronized(this) {
        savedJobStoreInstance ?: SharedPreferencesSavedJobStore(context.stableApplicationContext()).also { savedJobStoreInstance = it }
    }

    fun providerStaging(context: Context): ProviderStagingPort = providerStagingInstance ?: synchronized(this) {
        providerStagingInstance ?: AndroidProviderStagingManager(context.stableApplicationContext()).also { providerStagingInstance = it }
    }

    fun taskerConditionStore(context: Context): BkxTaskerConditionStore = taskerConditionStoreInstance ?: synchronized(this) {
        taskerConditionStoreInstance ?: SharedPreferencesBkxTaskerConditionStore(context.stableApplicationContext()).also { taskerConditionStoreInstance = it }
    }

    fun taskerConditionPublisher(context: Context): BkxTaskerConditionPublisher = taskerConditionPublisherInstance ?: synchronized(this) {
        taskerConditionPublisherInstance ?: BkxTaskerConditionPublisher(
            context.stableApplicationContext(),
            taskerConditionStore(context),
        ).also { taskerConditionPublisherInstance = it }
    }

    fun googleDriveAuthorizationManager(context: Context): GoogleDriveAuthorizationManager = googleDriveManagerInstance ?: synchronized(this) {
        googleDriveManagerInstance ?: GoogleDriveAuthorizationManager(
            context.stableApplicationContext(),
            taskerConditionPublisher = taskerConditionPublisher(context),
        ).also { googleDriveManagerInstance = it }
    }

    fun archiveSearchRoots(context: Context, rootAvailable: Boolean): List<String> =
        AndroidStorageAccessManager(context.stableApplicationContext()).archiveSearchRoots(rootAvailable)

    fun operationCoordinator(context: Context): BkxOperationCoordinator = coordinatorInstance ?: synchronized(this) {
        coordinatorInstance ?: BkxOperationCoordinator(
            context = context.stableApplicationContext(),
            backendPort = backendPort(context),
            runSummaryStore = runSummaryStore(context),
            operationStateStore = operationStateStore(context),
            requestBaseDirectoryPath = context.stableApplicationContext().filesDir.resolve("bkx/requests").absolutePath,
            progressPreferenceProvider = { sharedSettingsStore(context.stableApplicationContext()).readNow().progressPreference },
            taskerConditionPublisher = taskerConditionPublisher(context),
        ).also { coordinatorInstance = it }
    }


    fun automationEngine(context: Context): BkxAutomationEngine = automationEngineInstance ?: synchronized(this) {
        automationEngineInstance ?: BkxAutomationEngine(
            operationPort = CoordinatorAutomationOperationPort(operationCoordinator(context), runSummaryStore(context)),
            stateStore = SharedPreferencesAutomationStateStore(context.stableApplicationContext()),
            driveRefreshPort = GoogleManagedDriveRefreshPort(googleDriveAuthorizationManager(context)),
        ).also { automationEngineInstance = it }
    }


    fun resetForTests() = synchronized(this) {
        applicationRuntimeInstance?.shutdownForTests()
        applicationRuntimeInstance = null
        coordinatorInstance = null
        backendInstance = null
        chrootctlBridgePortInstance = null
        chrootctlReadinessPortInstance = null
        rootClientInstance?.release()
        rootClientInstance = null
        runStoreInstance = null
        operationStoreInstance = null
        settingsStoreInstance = null
        savedJobStoreInstance = null
        providerStagingInstance = null
        googleDriveManagerInstance = null
        automationEngineInstance = null
        taskerConditionStoreInstance = null
        taskerConditionPublisherInstance = null
    }

    private fun sharedSettingsStore(context: Context): SharedPreferencesSettingsStore = settingsStoreInstance ?: synchronized(this) {
        settingsStoreInstance ?: SharedPreferencesSettingsStore(context.stableApplicationContext()).also { settingsStoreInstance = it }
    }
}
