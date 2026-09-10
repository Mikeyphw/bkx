package com.mikeyphw.bkx.android.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.mikeyphw.bkx.android.app.ui.theme.BkxTheme

class BkxTaskerEventEditActivity : ComponentActivity(), TaskerPluginConfig<BkxTaskerEventInput> {
    override val context get() = applicationContext
    private var configured = BkxTaskerEventInput()
    private val helper by lazy { BkxTaskerEventHelper(this) }
    override fun assignFromInput(input: TaskerInput<BkxTaskerEventInput>) { configured = input.regular }
    override val inputForTasker get() = TaskerInput(configured)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper.onCreate()
        enableEdgeToEdge()
        setContent {
            BkxTheme {
                EventEditor(configured, onCancel = ::finish, onSave = { configured = it; helper.finishForTasker() })
            }
        }
    }
}

class BkxTaskerStateEditActivity : ComponentActivity(), TaskerPluginConfig<BkxTaskerStateInput> {
    override val context get() = applicationContext
    private var configured = BkxTaskerStateInput()
    private val helper by lazy { BkxTaskerStateHelper(this) }
    override fun assignFromInput(input: TaskerInput<BkxTaskerStateInput>) { configured = input.regular }
    override val inputForTasker get() = TaskerInput(configured)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper.onCreate()
        enableEdgeToEdge()
        setContent {
            BkxTheme {
                StateEditor(configured, onCancel = ::finish, onSave = { configured = it; helper.finishForTasker() })
            }
        }
    }
}

@Composable
private fun EventEditor(initial: BkxTaskerEventInput, onCancel: () -> Unit, onSave: (BkxTaskerEventInput) -> Unit) {
    var eventType by remember { mutableStateOf(initial.eventType) }
    var operation by remember { mutableStateOf(initial.operation) }
    var remoteFilter by remember { mutableStateOf(initial.remoteFilter) }
    var remoteName by remember { mutableStateOf(initial.remoteName) }
    var requestId by remember { mutableStateOf(initial.requestIdPattern) }
    var errorCode by remember { mutableStateOf(initial.errorCode) }
    var jobId by remember { mutableStateOf(initial.jobId) }
    var repositoryId by remember { mutableStateOf(initial.repositoryId) }
    var snapshotId by remember { mutableStateOf(initial.snapshotId) }
    var cadence by remember { mutableStateOf(initial.progressCadence) }
    ConditionScaffold("BKX Event", "Wake a Tasker profile when BKX publishes this event.", onCancel, {
        onSave(BkxTaskerEventInput(
            eventType = eventType,
            operation = operation.trim(),
            remoteFilter = remoteFilter,
            remoteName = remoteName.trim(),
            requestIdPattern = requestId.trim(),
            errorCode = errorCode.trim(),
            jobId = jobId.trim(),
            repositoryId = repositoryId.trim(),
            snapshotId = snapshotId.trim(),
            progressCadence = cadence,
        ))
    }) {
        Picker("Event", BkxTaskerEventType.fromId(eventType).label, BkxTaskerEventType.entries.map { it.id to it.label }) { eventType = it }
        FilterFields(
            operation, { operation = it }, remoteFilter, { remoteFilter = it }, remoteName, { remoteName = it },
            requestId, { requestId = it }, errorCode, { errorCode = it },
            jobId, { jobId = it }, repositoryId, { repositoryId = it }, snapshotId, { snapshotId = it },
        )
        if (eventType == BkxTaskerEventType.ProgressMilestone.id) {
            Picker("Progress cadence", cadenceLabel(cadence), listOf("recommended" to "Recommended milestones", "every_5" to "Every 5%", "every_10" to "Every 10%", "every_25" to "Every 25%", "every_50" to "Every 50%")) { cadence = it }
        }
        VariablesCard()
    }
}

@Composable
private fun StateEditor(initial: BkxTaskerStateInput, onCancel: () -> Unit, onSave: (BkxTaskerStateInput) -> Unit) {
    var stateType by remember { mutableStateOf(initial.stateType) }
    var operation by remember { mutableStateOf(initial.operation) }
    var remoteFilter by remember { mutableStateOf(initial.remoteFilter) }
    var remoteName by remember { mutableStateOf(initial.remoteName) }
    var requestId by remember { mutableStateOf(initial.requestIdPattern) }
    var errorCode by remember { mutableStateOf(initial.errorCode) }
    var jobId by remember { mutableStateOf(initial.jobId) }
    var repositoryId by remember { mutableStateOf(initial.repositoryId) }
    var snapshotId by remember { mutableStateOf(initial.snapshotId) }
    ConditionScaffold("BKX State", "Keep a Tasker profile active while this BKX condition is true.", onCancel, {
        onSave(BkxTaskerStateInput(
            stateType = stateType,
            operation = operation.trim(),
            remoteFilter = remoteFilter,
            remoteName = remoteName.trim(),
            requestIdPattern = requestId.trim(),
            errorCode = errorCode.trim(),
            jobId = jobId.trim(),
            repositoryId = repositoryId.trim(),
            snapshotId = snapshotId.trim(),
        ))
    }) {
        Picker("State", BkxTaskerStateType.fromId(stateType).label, BkxTaskerStateType.entries.map { it.id to it.label }) { stateType = it }
        FilterFields(
            operation, { operation = it }, remoteFilter, { remoteFilter = it }, remoteName, { remoteName = it },
            requestId, { requestId = it }, errorCode, { errorCode = it },
            jobId, { jobId = it }, repositoryId, { repositoryId = it }, snapshotId, { snapshotId = it },
        )
        VariablesCard()
    }
}

@Composable
private fun ConditionScaffold(title: String, subtitle: String, onCancel: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding().imePadding(), tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = onSave, modifier = Modifier.weight(1.35f)) { Text("Save to Tasker") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun FilterFields(
    operation: String, onOperation: (String) -> Unit,
    remoteFilter: String, onRemoteFilter: (String) -> Unit,
    remoteName: String, onRemoteName: (String) -> Unit,
    requestId: String, onRequestId: (String) -> Unit,
    errorCode: String, onErrorCode: (String) -> Unit,
    jobId: String, onJobId: (String) -> Unit,
    repositoryId: String, onRepositoryId: (String) -> Unit,
    snapshotId: String, onSnapshotId: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleMedium)
            Text("Leave a filter empty to match everything. * and ? wildcards are supported.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(operation, onOperation, Modifier.fillMaxWidth(), label = { Text("Operation") }, placeholder = { Text("backup.*") }, singleLine = true)
            Picker("Storage", remoteLabel(remoteFilter), listOf("any" to "Any storage", "local" to "Local only", "remote" to "Any remote", "google_drive" to "Google Drive", "rclone" to "Specific rclone remote"), onRemoteFilter)
            if (remoteFilter == "rclone") OutlinedTextField(remoteName, onRemoteName, Modifier.fillMaxWidth(), label = { Text("rclone remote name") }, singleLine = true)
            OutlinedTextField(requestId, onRequestId, Modifier.fillMaxWidth(), label = { Text("Request ID pattern") }, placeholder = { Text("nightly-*") }, singleLine = true)
            OutlinedTextField(errorCode, onErrorCode, Modifier.fillMaxWidth(), label = { Text("Error code") }, placeholder = { Text("BKX_*AUTH*") }, singleLine = true)
            OutlinedTextField(jobId, onJobId, Modifier.fillMaxWidth(), label = { Text("Saved job ID") }, placeholder = { Text("phone-backup") }, singleLine = true)
            OutlinedTextField(repositoryId, onRepositoryId, Modifier.fillMaxWidth(), label = { Text("Repository ID") }, placeholder = { Text("phone-drive") }, singleLine = true)
            OutlinedTextField(snapshotId, onSnapshotId, Modifier.fillMaxWidth(), label = { Text("Snapshot ID") }, placeholder = { Text("latest") }, singleLine = true)
        }
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelected(id) }) }
        }
    }
}

@Composable
private fun VariablesCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tasker variables", style = MaterialTheme.typography.titleMedium)
            Text("%bkx_event • %bkx_state • %bkx_operation • %bkx_request_id • %bkx_status • %bkx_progress", style = MaterialTheme.typography.bodySmall)
            Text("%bkx_files_done/total • %bkx_bytes_done/total • %bkx_archive • %bkx_remote • %bkx_error_code • %bkx_error • %bkx_phase • %bkx_timestamp", style = MaterialTheme.typography.bodySmall)
            Text("%bkx_job_id • %bkx_run_id • %bkx_repository_id • %bkx_snapshot_id", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun remoteLabel(value: String) = when (value) { "local" -> "Local only"; "remote" -> "Any remote"; "google_drive" -> "Google Drive"; "rclone" -> "Specific rclone remote"; else -> "Any storage" }
private fun cadenceLabel(value: String) = when (value) { "every_5" -> "Every 5%"; "every_10" -> "Every 10%"; "every_25" -> "Every 25%"; "every_50" -> "Every 50%"; else -> "Recommended milestones" }
