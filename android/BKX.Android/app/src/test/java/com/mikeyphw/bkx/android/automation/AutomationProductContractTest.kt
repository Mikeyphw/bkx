package com.mikeyphw.bkx.android.automation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationProductContractTest {
    private val projectRoot = File(System.getProperty("user.dir"))
    private val mainRoot = File(projectRoot, "src/main/java/com/mikeyphw/bkx/android")

    @Test
    fun manifestExposesOnlyCapabilityProtectedAutomationEntryPoints() {
        val manifest = File(projectRoot, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".automation.BkxTaskerEditActivity"))
        assertTrue(manifest.contains(LocalePluginContract.ACTION_EDIT_SETTING))
        assertTrue(manifest.contains(LocalePluginContract.ACTION_FIRE_SETTING))
        assertTrue(manifest.contains(".automation.BkxAutomationReceiver"))
        assertTrue(manifest.contains(".automation.BkxAutomationService"))
        assertTrue(manifest.contains(".automation.BkxAutomationJobService"))
        assertTrue(manifest.contains("android.permission.BIND_JOB_SERVICE"))
        BkxAutomationContract.directActions.forEach { assertFalse(manifest.contains(it)) }
    }

    @Test
    fun taskerCapabilityIsSeparateAndEditActivityRejectsUntrustedCallers() {
        val stores = File(mainRoot, "automation/AutomationStores.kt").readText()
        val editor = File(mainRoot, "automation/BkxTaskerEditActivity.kt").readText()
        assertTrue(stores.contains("tasker_capability_token"))
        assertTrue(stores.contains("isTaskerAuthorized"))
        assertTrue(editor.contains("callingPackage !in TRUSTED_TASKER_HOSTS"))
        assertTrue(editor.contains("access.taskerToken"))
        assertFalse(editor.contains("access.token, wait"))
    }

    @Test
    fun taskerAndDirectIntentsShareOneEngineAndExistingSafetyBuilders() {
        val engine = File(mainRoot, "automation/BkxAutomationEngine.kt").readText()
        val receiver = File(mainRoot, "automation/BkxAutomationReceiver.kt").readText()
        assertTrue(receiver.contains("AutomationAccessStore(context).isTaskerAuthorized"))
        assertTrue(receiver.contains("AutomationAccessStore(context).isAuthorized"))
        assertTrue(receiver.contains("AutomationDispatcher.enqueue"))
        assertTrue(engine.contains("BackupRequestBuilder"))
        assertTrue(engine.contains("RestoreRequestBuilder"))
        assertTrue(engine.contains("TransferRequestBuilder"))
        assertTrue(engine.contains("ArchiveRequestBuilder"))
        assertTrue(engine.contains("toRestorePreviewSummary"))
        assertTrue(engine.contains("RestoreExtractionConfirmation.requiredPhrase"))
        assertTrue(engine.contains("GoogleManagedDriveRefreshPort"))
        assertFalse(receiver.contains("backendPort.submit"))
    }

    @Test
    fun automationOwnsForegroundWhileCoordinatorKeepsDefaultUiBehavior() {
        val coordinator = File(mainRoot, "app/runtime/BkxOperationCoordinator.kt").readText()
        val service = File(mainRoot, "automation/BkxAutomationService.kt").readText()
        assertTrue(coordinator.contains("manageForeground: Boolean = true"))
        assertTrue(coordinator.contains("if (manageForeground && request.operation in FOREGROUND_OPERATIONS)"))
        assertTrue(service.contains("startForeground"))
        assertTrue(service.contains("START_REDELIVER_INTENT"))
    }

    @Test
    fun taskerProtocolSupportsAsyncCompletionVariablesAndVariableReplacement() {
        val contract = File(mainRoot, "automation/AutomationContract.kt").readText()
        val delivery = File(mainRoot, "automation/BkxAutomationService.kt").readText()
        assertTrue(contract.contains(TaskerPluginContract.EXTRA_COMPLETION_INTENT))
        assertTrue(contract.contains(TaskerPluginContract.EXTRA_VARIABLE_REPLACE_KEYS))
        assertTrue(contract.contains(TaskerPluginContract.EXTRA_RELEVANT_VARIABLES))
        val editor = File(mainRoot, "automation/BkxTaskerEditActivity.kt").readText()
        val codec = File(mainRoot, "automation/AutomationIntentCodec.kt").readText()
        assertTrue(editor.contains("TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT"))
        assertFalse(codec.contains("TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT"))
        assertTrue(delivery.contains("Intent.parseUri"))
        assertTrue(delivery.contains("TaskerPluginContract.EXTRA_VARIABLES"))
    }
    @Test
    fun taskerEditorUsesGuidedCompactUxWithoutRemovingVariablePower() {
        val editor = File(mainRoot, "automation/BkxTaskerEditActivity.kt").readText()
        assertTrue(editor.contains("Scaffold("))
        assertTrue(editor.contains("bottomBar ="))
        assertTrue(editor.contains("OperationPicker"))
        assertTrue(editor.contains("DropdownMenu("))
        assertTrue(editor.contains("AdvancedSection"))
        assertTrue(editor.contains("taskerEditorErrors"))
        assertTrue(editor.contains("ChoiceField"))
        assertTrue(editor.contains("Use Tasker variable"))
        assertTrue(editor.contains("Save to Tasker"))
        assertTrue(editor.contains("Tasker summary"))
        assertTrue(editor.contains("navigationBarsPadding().imePadding()"))
        assertTrue(editor.contains("statusBarsPadding()"))
        assertTrue(editor.contains("ReturnedVariablesList"))
        assertTrue(editor.contains("Show request identity"))
        assertTrue(editor.contains("clickable(role = Role.Switch)"))
        assertTrue(editor.contains("AutomationAction.entries.groupBy { it.editorCategory() }"))
        assertTrue(editor.contains("Text(blurb"))
        assertFalse(editor.contains("AutomationResult.relevantVariables.joinToString"))
        assertFalse(editor.contains("SectionCard(\"Request identity\""))
        assertFalse(editor.contains("AutomationAction.entries.chunked(3)"))
    }

    @Test
    fun taskerEventAndStateConditionsUseAuthoritativeBkxSignals() {
        val manifest = File(projectRoot, "src/main/AndroidManifest.xml").readText()
        val versions = File(projectRoot, "gradle/libs.versions.toml").readText()
        val build = File(projectRoot, "build.gradle.kts").readText()
        val graph = File(mainRoot, "app/di/DefaultBackendGraph.kt").readText()
        val coordinator = File(mainRoot, "app/runtime/BkxOperationCoordinator.kt").readText()
        val drive = File(mainRoot, "app/drive/GoogleDriveAuthorizationManager.kt").readText()
        val publisher = File(mainRoot, "automation/TaskerConditionPublisher.kt").readText()
        val plugin = File(mainRoot, "automation/TaskerConditionPlugin.kt").readText()

        assertTrue(manifest.contains("net.dinglisch.android.tasker.ACTION_EDIT_EVENT"))
        assertTrue(manifest.contains("com.twofortyfouram.locale.intent.action.EDIT_CONDITION"))
        assertTrue(manifest.contains(".automation.BkxTaskerEventEditActivity"))
        assertTrue(manifest.contains(".automation.BkxTaskerStateEditActivity"))
        assertTrue(manifest.contains("com.joaomgcd.taskerpluginlibrary.action.BroadcastReceiverAction"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
        assertTrue(versions.contains("taskerPlugin = \"0.4.10\""))
        assertTrue(build.contains("implementation(libs.tasker.plugin.library)"))
        assertTrue(coordinator.contains("taskerConditionPublisher?.operationStarted"))
        assertTrue(coordinator.contains("taskerConditionPublisher?.operationProgress"))
        assertTrue(coordinator.contains("taskerConditionPublisher?.operationTerminal"))
        assertTrue(drive.contains("taskerConditionPublisher?.driveNeedsAuthorization"))
        assertTrue(graph.contains("SharedPreferencesBkxTaskerConditionStore"))
        assertTrue(publisher.contains("TaskerPluginRunnerCondition.requestQuery"))
        assertTrue(plugin.contains("TaskerPluginRunnerConditionEvent"))
        assertTrue(plugin.contains("TaskerPluginRunnerConditionState"))
        assertFalse(publisher.contains("accessToken"))
        assertFalse(publisher.contains("refreshToken"))
        assertFalse(publisher.contains("capability_token"))
    }

}
