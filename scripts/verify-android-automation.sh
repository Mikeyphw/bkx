#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
manifest="$root/android/BKX.Android/app/src/main/AndroidManifest.xml"
source_root="$root/android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android"
test_root="$root/android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/automation"
docs="$root/android/BKX.Android/docs/AUTOMATION_TASKER.md"

required=(
  "$manifest"
  "$source_root/automation/AutomationContract.kt"
  "$source_root/automation/AutomationStores.kt"
  "$source_root/automation/AutomationIntentCodec.kt"
  "$source_root/automation/BkxAutomationEngine.kt"
  "$source_root/automation/AutomationDispatch.kt"
  "$source_root/automation/BkxAutomationReceiver.kt"
  "$source_root/automation/BkxAutomationService.kt"
  "$source_root/automation/BkxTaskerEditActivity.kt"
  "$source_root/feature/more/AutomationScreen.kt"
  "$test_root/BkxAutomationEngineTest.kt"
  "$test_root/AutomationProductContractTest.kt"
  "$docs"
)
for f in "${required[@]}"; do
  [[ -f "$f" ]] || { echo "missing automation contract file: $f" >&2; exit 1; }
done

for action in BACKUP RESTORE VERIFY ARCHIVE_LIST ARCHIVE_INFO ARCHIVE_BROWSE TRANSFER CANCEL STATUS; do
  grep -Fq "ACTION_${action}" "$source_root/automation/AutomationContract.kt" || { echo "automation contract missing action $action" >&2; exit 1; }
done
if grep -Fq 'com.mikeyphw.bkx.android.automation.BACKUP' "$manifest"; then
  echo 'direct automation actions must not be advertised through manifest intent filters; callers must target BkxAutomationReceiver explicitly' >&2
  exit 1
fi

grep -Fq 'com.twofortyfouram.locale.intent.action.EDIT_SETTING' "$manifest"
grep -Fq 'com.twofortyfouram.locale.intent.action.FIRE_SETTING' "$manifest"
grep -Fq 'android:name=".automation.BkxAutomationService"' "$manifest"
grep -Fq 'android:permission="android.permission.BIND_JOB_SERVICE"' "$manifest"
grep -Fq 'android:foregroundServiceType="dataSync"' "$manifest"

grep -Fq 'isTaskerAuthorized' "$source_root/automation/BkxAutomationReceiver.kt"
grep -Fq 'isAuthorized' "$source_root/automation/BkxAutomationReceiver.kt"
grep -Fq 'callingPackage !in TRUSTED_TASKER_HOSTS' "$source_root/automation/BkxTaskerEditActivity.kt"
grep -Fq 'taskerToken' "$source_root/automation/BkxTaskerEditActivity.kt"
grep -Fq 'tasker_capability_token' "$source_root/automation/AutomationStores.kt"

grep -Fq 'BackupRequestBuilder' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'RestoreRequestBuilder' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'ArchiveRequestBuilder' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'TransferRequestBuilder' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'RestoreExtractionConfirmation.requiredPhrase' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'automation_previous_dispatch_incomplete' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'GoogleManagedDriveRefreshPort' "$source_root/automation/BkxAutomationEngine.kt"

grep -Fq 'manageForeground = false' "$source_root/automation/BkxAutomationEngine.kt"
grep -Fq 'START_REDELIVER_INTENT' "$source_root/automation/BkxAutomationService.kt"
grep -Fq 'AutomationDispatchStore' "$source_root/automation/BkxAutomationService.kt"
grep -Fq 'EXTRA_COMPLETION_INTENT' "$source_root/automation/AutomationContract.kt"
grep -Fq 'TaskerPluginContract.EXTRA_VARIABLE_REPLACE_KEYS' "$source_root/automation/AutomationIntentCodec.kt"
grep -Fq 'TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT' "$source_root/automation/BkxTaskerEditActivity.kt"
if grep -Fq 'TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT' "$source_root/automation/AutomationIntentCodec.kt"; then
  echo 'Tasker requested timeout must be returned as an EditActivity result extra, not persisted inside the Locale plugin bundle' >&2
  exit 1
fi
grep -Fq '%bkx_request_id' "$source_root/automation/AutomationContract.kt"

if grep -R -E '(access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|password)[[:space:]]*=' \
  "$source_root/automation" --include='*.kt' | grep -v -E '(EXTRA_ACCESS_TOKEN|capability_token|tasker_capability_token|storePassword|keyPassword)' >/dev/null; then
  echo 'automation source appears to define a credential-bearing input; only capability-token plumbing is allowed' >&2
  exit 1
fi

for token in 'RESTORE ROOT' 'rclone://bkx-google-drive/BKX' '%bkx_request_id' 'CALLBACK_PACKAGE' 'automation_previous_dispatch_incomplete'; do
  grep -Fq "$token" "$docs" || { echo "automation docs missing $token" >&2; exit 1; }
done


# Local JVM unit tests must not depend on android.os.Bundle behavior or kotlinx.coroutines.test's global uncaught-exception collector.
engine_test="$test_root/BkxAutomationEngineTest.kt"
grep -Fq 'taskerVariableValues()' "$engine_test"
grep -Fq 'import kotlinx.coroutines.runBlocking' "$engine_test"
if grep -Fq 'runTest' "$engine_test"; then
  echo 'BkxAutomationEngineTest must use runBlocking; no virtual-time/test-scheduler behavior is required.' >&2
  exit 1
fi
if grep -Fq 'taskerVariables()' "$engine_test" || grep -Fq 'getString("%bkx_' "$engine_test"; then
  echo 'Local JVM automation tests must validate the pure Tasker variable map, not android.os.Bundle stubs.' >&2
  exit 1
fi
grep -Fq 'fun taskerVariableValues(): Map<String, String>' "$source_root/automation/AutomationContract.kt"


# minSdk 24 lint contract: notification channels exist only on API 26+, and SharedPreferences mutations use KTX.
service="$source_root/automation/BkxAutomationService.kt"
dispatch="$source_root/automation/AutomationDispatch.kt"
stores="$source_root/automation/AutomationStores.kt"
grep -Fq 'Build.VERSION.SDK_INT < Build.VERSION_CODES.O' "$service"
grep -Fq 'import androidx.core.content.edit' "$dispatch"
grep -Fq 'import androidx.core.content.edit' "$stores"
if grep -E 'preferences\.edit\(\)' "$dispatch" "$stores" >/dev/null; then
  echo 'Automation SharedPreferences writes must use androidx.core.content.edit to keep lint clean.' >&2
  exit 1
fi

echo 'BKX Android Tasker/external automation static contract: PASS'
# Unit tests must use the app module's existing JUnit4 lane, not kotlin.test.
for test_file in "$test_root/AutomationProductContractTest.kt" "$test_root/BkxAutomationEngineTest.kt"; do
  if grep -Fq 'kotlin.test' "$test_file"; then
    echo "Automation unit test must not depend on kotlin.test: $test_file" >&2
    exit 1
  fi
  grep -Fq 'import org.junit.Test' "$test_file"
done


# Tasker editor UX contract: compact picker, sticky save, progressive disclosure, inline guidance, and variable escape hatch.
editor="$source_root/automation/BkxTaskerEditActivity.kt"
for token in \
  'Scaffold(' \
  'bottomBar =' \
  'OperationPicker' \
  'DropdownMenu(' \
  'AdvancedSection' \
  'taskerEditorErrors' \
  'ChoiceField' \
  'Use Tasker variable' \
  'Save to Tasker' \
  'Tasker summary' \
  'navigationBarsPadding().imePadding()' \
  'statusBarsPadding()' \
  'ReturnedVariablesList' \
  'Show request identity' \
  'clickable(role = Role.Switch)' \
  'AutomationAction.entries.groupBy { it.editorCategory() }' \
  'Text(blurb'; do
  grep -Fq "$token" "$editor" || { echo "Tasker editor UX contract missing: $token" >&2; exit 1; }
done
if grep -Fq 'AutomationAction.entries.chunked(3)' "$editor"; then
  echo 'Tasker editor must not regress to the dense multi-row action chip wall.' >&2
  exit 1
fi
if grep -Fq 'AutomationResult.relevantVariables.joinToString' "$editor"; then
  echo 'Tasker returned variables must not regress to an undifferentiated raw text block.' >&2
  exit 1
fi
if grep -Fq 'SectionCard("Request identity"' "$editor"; then
  echo 'Tasker request identity must remain progressively disclosed instead of consuming a permanent full card.' >&2
  exit 1
fi
for token in 'Tasker editor UX' 'Advanced options' 'Use Tasker variable' 'Save to Tasker'; do
  grep -Fq "$token" "$docs" || { echo "Tasker docs missing editor UX guidance: $token" >&2; exit 1; }
done

# Tasker Event/State bidirectional condition contract.
condition_contract="$source_root/automation/TaskerConditionContract.kt"
condition_store="$source_root/automation/TaskerConditionStore.kt"
condition_publisher="$source_root/automation/TaskerConditionPublisher.kt"
condition_plugin="$source_root/automation/TaskerConditionPlugin.kt"
condition_activities="$source_root/automation/BkxTaskerConditionActivities.kt"
condition_test="$test_root/TaskerConditionContractTest.kt"
drive_manager="$source_root/app/drive/GoogleDriveAuthorizationManager.kt"
graph="$source_root/app/di/DefaultBackendGraph.kt"
coordinator="$source_root/app/runtime/BkxOperationCoordinator.kt"
versions="$root/android/BKX.Android/gradle/libs.versions.toml"
app_build="$root/android/BKX.Android/app/build.gradle.kts"

for f in "$condition_contract" "$condition_store" "$condition_publisher" "$condition_plugin" "$condition_activities" "$condition_test" "$drive_manager" "$graph" "$coordinator" "$versions" "$app_build"; do
  [[ -f "$f" ]] || { echo "missing Tasker Event/State contract file: $f" >&2; exit 1; }
done

grep -Fq 'net.dinglisch.android.tasker.ACTION_EDIT_EVENT' "$manifest"
grep -Fq 'com.twofortyfouram.locale.intent.action.EDIT_CONDITION' "$manifest"
grep -Fq '.automation.BkxTaskerEventEditActivity' "$manifest"
grep -Fq '.automation.BkxTaskerStateEditActivity' "$manifest"
grep -Fq 'com.joaomgcd.taskerpluginlibrary.action.BroadcastReceiverAction' "$manifest"
grep -Fq 'com.joaomgcd.taskerpluginlibrary.action.IntentServiceAction' "$manifest"
grep -Fq 'tools:node="remove"' "$manifest"
grep -Fq 'taskerPlugin = "0.4.10"' "$versions"
grep -Fq 'implementation(libs.tasker.plugin.library)' "$app_build"

for token in operation_started progress_milestone operation_completed backup_completed restore_completed verify_completed transfer_completed archive_created operation_failed operation_cancelled google_drive_authorization_required remote_unavailable remote_recovered; do
  grep -Fq "$token" "$condition_contract" || { echo "Tasker Event contract missing $token" >&2; exit 1; }
done
for token in any_operation_running backup_running restore_running verify_running transfer_running google_drive_connected google_drive_needs_authorization remote_available last_operation_succeeded last_operation_failed last_operation_cancelled; do
  grep -Fq "$token" "$condition_contract" || { echo "Tasker State contract missing $token" >&2; exit 1; }
done
for token in bkx_event bkx_state bkx_operation bkx_request_id bkx_status bkx_progress bkx_files_done bkx_files_total bkx_bytes_done bkx_bytes_total bkx_archive bkx_remote bkx_error_code bkx_error bkx_phase bkx_timestamp; do
  grep -Fq "TaskerOutputVariable(\"$token\")" "$condition_contract" || { echo "Tasker condition output missing %$token" >&2; exit 1; }
done

grep -Fq 'TaskerPluginRunnerConditionEvent' "$condition_plugin"
grep -Fq 'TaskerPluginRunnerConditionState' "$condition_plugin"
grep -Fq 'TaskerPluginRunnerCondition.requestQuery' "$condition_publisher"
grep -Fq 'canonicalProgressBucket' "$condition_publisher"
grep -Fq 'activeSignals' "$condition_store"
grep -Fq 'terminalHistory' "$condition_store"
grep -Fq 'remoteAvailability' "$condition_store"
grep -Fq 'driveState' "$condition_store"

grep -Fq 'taskerConditionPublisher?.operationStarted' "$coordinator"
grep -Fq 'taskerConditionPublisher?.operationProgress' "$coordinator"
grep -Fq 'taskerConditionPublisher?.operationTerminal' "$coordinator"
grep -Fq 'taskerConditionPublisher?.operationInterrupted' "$coordinator"
grep -Fq 'taskerConditionPublisher?.driveNeedsAuthorization' "$drive_manager"
grep -Fq 'taskerConditionPublisher?.driveConnected' "$drive_manager"
grep -Fq 'SharedPreferencesBkxTaskerConditionStore' "$graph"

# Signals must remain metadata-only: never add credential fields to the condition DTO/store.
if grep -Eiq '(val|var)[[:space:]]+(accessToken|refreshToken|clientSecret|capabilityToken|taskerToken|password)' "$condition_contract" "$condition_store" "$condition_publisher"; then
  echo 'Tasker Event/State signal contract must not contain credential/capability fields.' >&2
  exit 1
fi
# Canonical milestones are required so a 24% -> 26% jump still triggers a 25% profile.
grep -Fq '((it.coerceIn(0.0, 1.0) * 100.0).toInt() / 5) * 5' "$condition_store"
grep -Fq 'assertEquals(25, BkxTaskerConditionMatcher.canonicalProgressBucket(0.26))' "$condition_test"
# Existing Action receiver remains present and the library Action components are removed.
grep -Fq 'android:name=".automation.BkxAutomationReceiver"' "$manifest"
grep -Fq 'com.twofortyfouram.locale.intent.action.FIRE_SETTING' "$manifest"

for token in 'BKX Event' 'BKX State' 'Google Drive connected' 'Remote unavailable' '%bkx_event' '%bkx_state'; do
  grep -Fq "$token" "$docs" || { echo "Tasker Event/State docs missing $token" >&2; exit 1; }
done

echo 'BKX Android Tasker Event/State condition contract: PASS'
