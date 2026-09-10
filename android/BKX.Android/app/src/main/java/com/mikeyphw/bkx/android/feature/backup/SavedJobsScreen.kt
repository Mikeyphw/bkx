package com.mikeyphw.bkx.android.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.state.BkxSharedStateContract
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.jobs.OperationSpecV2
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.jobs.SavedJobDraft
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleDefinition
import com.mikeyphw.bkx.android.automation.BkxSavedJobScheduler
import com.mikeyphw.bkx.android.core.jobs.SecretReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private enum class JobPreset(val label: String, val operation: String, val template: String) {
    Snapshot(
        "Incremental",
        "backup.snapshot",
        """{"engine":"restic","sources":["/storage/emulated/0/Documents"],"excludes":[],"includes":[],"tags":[],"verify":true,"dry_run":false}""",
    ),
    Archive(
        "Portable archive",
        "backup.create",
        """{"archive_name":"backup.bakery","compression":{"algorithm":"gzip","level":3},"destination":"/storage/emulated/0/BKX","dry_run":false,"excludes":[],"includes":[],"sources":["/storage/emulated/0/Documents"],"split_size":null,"skip_symlinks":true,"verify":true}""",
    ),
    Transfer(
        "Transfer",
        "transfer.run",
        """{"checksum":null,"destination":"/storage/emulated/0/BKX","direction":"push","dry_run":false,"overwrite":false,"sources":["/storage/emulated/0/Documents"],"verify":true}""",
    ),
}

@Composable
fun SavedJobsScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<SavedJob?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SavedJob?>(null) }
    var cloneTarget by remember { mutableStateOf<SavedJob?>(null) }
    var scheduleCreating by rememberSaveable { mutableStateOf(false) }
    var scheduleEditing by remember { mutableStateOf<SavedJobScheduleDefinition?>(null) }
    var scheduleDeleteTarget by remember { mutableStateOf<SavedJobScheduleDefinition?>(null) }
    val context = LocalContext.current

    LaunchedEffect(state.savedJobs.schedules) {
        BkxSavedJobScheduler.reconcile(context)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader("Saved jobs", "Create reusable operations once, then run the exact same job from Android, the BKX CLI, and later Tasker.")
        BkxSurfaceCard {
            Text("Shared with on-device CLI", style = MaterialTheme.typography.titleMedium)
            BkxKeyValue("State", BkxSharedStateContract.ROOT)
            Text("Only job and repository definitions are shared here. Restic passwords, OAuth tokens, and rclone credentials remain outside the shared control-plane store.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true; editing = null }, enabled = state.savedJobs.actionInProgress == null, modifier = Modifier.weight(1f)) { Text("New job") }
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshSavedJobs) }, enabled = state.savedJobs.actionInProgress == null, modifier = Modifier.weight(1f)) { Text("Refresh") }
        }

        when (val status = state.savedJobs.status) {
            ScreenStatus.Loading -> BkxSurfaceCard { Text("Loading saved jobs…") }
            is ScreenStatus.Empty -> BkxSurfaceCard { Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            is ScreenStatus.Error -> BkxSurfaceCard {
                Text(status.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshSavedJobs) }) { Text("Try again") }
            }
            ScreenStatus.Content -> state.savedJobs.items.forEach { job ->
                SavedJobCard(
                    job = job,
                    repositoryName = job.operation.repositoryRef?.let { id -> state.repositories.items.firstOrNull { it.repositoryId == id }?.name },
                    busy = state.savedJobs.actionInProgress != null,
                    onRun = { onEvent(BkxHomeEvent.RunSavedJob(job.jobId)) },
                    onPlan = { onEvent(BkxHomeEvent.PlanSavedJob(job.jobId)) },
                    onEdit = { editing = job; creating = false },
                    onClone = { cloneTarget = job },
                    onDelete = { deleteTarget = job },
                )
            }
        }
        state.savedJobs.lastActionMessage?.takeIf { it.isNotBlank() }?.let { message ->
            BkxSurfaceCard { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        BkxDivider()
        BkxSectionHeader(title = "Schedules", action = "Refresh", onAction = { onEvent(BkxHomeEvent.RefreshSavedJobSchedules) })
        Text(
            "Recurring schedules are shared with the BKX CLI and reconciled with Android JobScheduler. Android may batch periodic work to protect battery.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { scheduleCreating = true; scheduleEditing = null },
            enabled = state.savedJobs.items.isNotEmpty() && state.savedJobs.scheduleActionInProgress == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("New schedule") }
        when (val scheduleStatus = state.savedJobs.scheduleStatus) {
            ScreenStatus.Loading -> BkxSurfaceCard { Text("Loading schedules…") }
            is ScreenStatus.Empty -> BkxSurfaceCard { Text(scheduleStatus.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            is ScreenStatus.Error -> BkxSurfaceCard {
                Text(scheduleStatus.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(scheduleStatus.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScreenStatus.Content -> state.savedJobs.schedules.forEach { schedule ->
                SavedJobScheduleCard(
                    schedule = schedule,
                    jobName = state.savedJobs.items.firstOrNull { it.jobId == schedule.jobId }?.name ?: schedule.jobId,
                    busy = state.savedJobs.scheduleActionInProgress != null,
                    onToggle = { enabled -> onEvent(BkxHomeEvent.SetSavedJobScheduleEnabled(schedule.scheduleId, enabled)) },
                    onEdit = { scheduleEditing = schedule; scheduleCreating = false },
                    onDelete = { scheduleDeleteTarget = schedule },
                )
            }
        }
        state.savedJobs.scheduleMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (creating || editing != null) {
        SavedJobEditorDialog(
            existing = editing,
            repositories = state.repositories.items.map { it.repositoryId to it.name },
            onDismiss = { creating = false; editing = null },
            onSave = { draft ->
                val current = editing
                if (current == null) onEvent(BkxHomeEvent.CreateSavedJob(draft))
                else onEvent(BkxHomeEvent.UpdateSavedJob(current.jobId, current.revision, draft))
                creating = false
                editing = null
            },
        )
    }

    if (scheduleCreating || scheduleEditing != null) {
        SavedJobScheduleEditorDialog(
            existing = scheduleEditing,
            jobs = state.savedJobs.items,
            onDismiss = { scheduleCreating = false; scheduleEditing = null },
            onSave = { schedule ->
                onEvent(BkxHomeEvent.PutSavedJobSchedule(schedule))
                scheduleCreating = false
                scheduleEditing = null
            },
        )
    }

    scheduleDeleteTarget?.let { schedule ->
        AlertDialog(
            onDismissRequest = { scheduleDeleteTarget = null },
            title = { Text("Delete schedule?") },
            text = { Text("${schedule.scheduleId} will stop launching ${schedule.jobId}. The saved job itself is not deleted.") },
            confirmButton = { Button(onClick = { onEvent(BkxHomeEvent.DeleteSavedJobSchedule(schedule.scheduleId)); scheduleDeleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { scheduleDeleteTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete saved job?") },
            text = { Text("${job.name} will be removed from the shared BKX job store. Existing run history is kept.") },
            confirmButton = { Button(onClick = { onEvent(BkxHomeEvent.DeleteSavedJob(job.jobId)); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    cloneTarget?.let { job ->
        CloneJobDialog(job = job, onDismiss = { cloneTarget = null }) { id, name ->
            onEvent(BkxHomeEvent.CloneSavedJob(job.jobId, id, name))
            cloneTarget = null
        }
    }
}

@Composable
private fun SavedJobCard(
    job: SavedJob,
    repositoryName: String?,
    busy: Boolean,
    onRun: () -> Unit,
    onPlan: () -> Unit,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    BkxSurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(job.name, style = MaterialTheme.typography.titleLarge)
                Text(job.jobId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BkxStatusPill(if (job.enabled) "ENABLED" else "DISABLED")
        }
        job.description.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        BkxKeyValue("Operation", job.operation.operation)
        BkxKeyValue("Revision", job.revision.toString())
        job.operation.repositoryRef?.let { BkxKeyValue("Repository", repositoryName ?: it) }
        if (job.tags.isNotEmpty()) BkxKeyValue("Tags", job.tags.joinToString(", "))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = job.enabled && !busy, modifier = Modifier.weight(1f)) { Text("Run now") }
            OutlinedButton(onClick = onPlan, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Plan") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Edit") }
            TextButton(onClick = onClone, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Clone") }
            TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Delete") }
        }
    }
}

@Composable
private fun SavedJobScheduleCard(
    schedule: SavedJobScheduleDefinition,
    jobName: String,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    BkxSurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(schedule.scheduleId, style = MaterialTheme.typography.titleMedium)
                Text(jobName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BkxStatusPill(if (schedule.enabled) "ENABLED" else "PAUSED")
        }
        BkxKeyValue("Every", "${schedule.intervalMinutes} min")
        BkxKeyValue("Network", when (schedule.network) { "unmetered" -> "Unmetered"; "any" -> "Any network"; else -> "No network required" })
        BkxKeyValue("Charging", if (schedule.requireCharging) "Required" else "Not required")
        BkxSettingSwitch(
            title = "Enabled",
            summary = "Pause this schedule without deleting it.",
            checked = schedule.enabled,
            onCheckedChange = onToggle,
            enabled = !busy,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Edit") }
            TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Delete") }
        }
    }
}

@Composable
private fun SavedJobScheduleEditorDialog(
    existing: SavedJobScheduleDefinition?,
    jobs: List<SavedJob>,
    onDismiss: () -> Unit,
    onSave: (SavedJobScheduleDefinition) -> Unit,
) {
    var scheduleId by remember(existing?.scheduleId) { mutableStateOf(existing?.scheduleId.orEmpty()) }
    var jobId by remember(existing?.scheduleId) { mutableStateOf(existing?.jobId ?: jobs.firstOrNull()?.jobId.orEmpty()) }
    var interval by remember(existing?.scheduleId) { mutableStateOf((existing?.intervalMinutes ?: 60L).toString()) }
    var network by remember(existing?.scheduleId) { mutableStateOf(existing?.network ?: "none") }
    var charging by remember(existing?.scheduleId) { mutableStateOf(existing?.requireCharging ?: false) }
    var enabled by remember(existing?.scheduleId) { mutableStateOf(existing?.enabled ?: true) }
    val intervalMinutes = interval.toLongOrNull()
    val valid = scheduleId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$")) && jobId.isNotBlank() && intervalMinutes != null && intervalMinutes >= 15L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New schedule" else "Edit schedule") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = scheduleId,
                    onValueChange = { if (existing == null) scheduleId = normalizeIdInput(it) },
                    label = { Text("Schedule ID") },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Saved job", style = MaterialTheme.typography.labelLarge)
                jobs.forEach { job ->
                    FilterChip(selected = jobId == job.jobId, onClick = { jobId = job.jobId }, label = { Text(job.name) })
                }
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter(Char::isDigit) },
                    label = { Text("Repeat every (minutes)") },
                    supportingText = { Text("Minimum 15 minutes. Android may batch periodic jobs.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Network requirement", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = network == "none", onClick = { network = "none" }, label = { Text("None") })
                    FilterChip(selected = network == "any", onClick = { network = "any" }, label = { Text("Any") })
                    FilterChip(selected = network == "unmetered", onClick = { network = "unmetered" }, label = { Text("Unmetered") })
                }
                BkxSettingSwitch("Require charging", "Run only while the device is charging.", charging, { charging = it })
                BkxSettingSwitch("Enabled", "Disabled schedules remain saved but do not run.", enabled, { enabled = it })
                if (!valid) Text("Use a valid ID, select a job, and choose an interval of at least 15 minutes.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        SavedJobScheduleDefinition(
                            scheduleId = scheduleId,
                            jobId = jobId,
                            enabled = enabled,
                            intervalMinutes = intervalMinutes ?: 15L,
                            requireCharging = charging,
                            network = network,
                            createdAt = existing?.createdAt.orEmpty(),
                            updatedAt = existing?.updatedAt.orEmpty(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SavedJobEditorDialog(
    existing: SavedJob?,
    repositories: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (SavedJobDraft) -> Unit,
) {
    val initialPreset = JobPreset.entries.firstOrNull { it.operation == existing?.operation?.operation } ?: JobPreset.Snapshot
    var preset by remember(existing?.jobId) { mutableStateOf(initialPreset) }
    var jobId by remember(existing?.jobId) { mutableStateOf(existing?.jobId.orEmpty()) }
    var name by remember(existing?.jobId) { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember(existing?.jobId) { mutableStateOf(existing?.description.orEmpty()) }
    var operation by remember(existing?.jobId) { mutableStateOf(existing?.operation?.operation ?: preset.operation) }
    var parametersJson by remember(existing?.jobId) { mutableStateOf(existing?.operation?.parameters?.toString() ?: preset.template) }
    var repositoryRef by remember(existing?.jobId) { mutableStateOf(existing?.operation?.repositoryRef.orEmpty()) }
    var credentialRefs by remember(existing?.jobId) { mutableStateOf(existing?.operation?.credentialRefs?.joinToString(",") { it.id }.orEmpty()) }
    var tags by remember(existing?.jobId) { mutableStateOf(existing?.tags?.joinToString(",").orEmpty()) }
    var enabled by remember(existing?.jobId) { mutableStateOf(existing?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New saved job" else "Edit saved job") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preset", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JobPreset.entries.forEach { option ->
                        FilterChip(
                            selected = preset == option,
                            onClick = {
                                preset = option
                                operation = option.operation
                                parametersJson = option.template
                                if (option != JobPreset.Snapshot) repositoryRef = ""
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(value = jobId, onValueChange = { if (existing == null) jobId = normalizeIdInput(it) }, label = { Text("Job ID") }, enabled = existing == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = operation, onValueChange = { operation = it.trim() }, label = { Text("Operation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (repositories.isNotEmpty()) {
                    Text("Repository reference", style = MaterialTheme.typography.labelLarge)
                    repositories.forEach { (id, repoName) ->
                        FilterChip(selected = repositoryRef == id, onClick = { repositoryRef = if (repositoryRef == id) "" else id }, label = { Text(repoName) })
                    }
                }
                OutlinedTextField(value = repositoryRef, onValueChange = { repositoryRef = it.trim() }, label = { Text("Repository ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = credentialRefs, onValueChange = { credentialRefs = it }, label = { Text("Credential reference IDs") }, supportingText = { Text("IDs only; never paste passwords or tokens here.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags") }, supportingText = { Text("Comma-separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = parametersJson, onValueChange = { parametersJson = it }, label = { Text("Operation parameters (JSON)") }, minLines = 6, modifier = Modifier.fillMaxWidth())
                BkxSettingSwitch(title = "Enabled", summary = "Disabled jobs can be planned but cannot run.", checked = enabled, onCheckedChange = { enabled = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = buildDraft(jobId, name, description, tags, enabled, operation, parametersJson, repositoryRef, credentialRefs)
                result.onSuccess(onSave).onFailure { error = it.message ?: "Invalid job" }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloneJobDialog(job: SavedJob, onDismiss: () -> Unit, onClone: (String, String) -> Unit) {
    var id by remember { mutableStateOf("${job.jobId}-copy") }
    var name by remember { mutableStateOf("${job.name} copy") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone saved job") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = normalizeIdInput(it) }, label = { Text("New job ID") }, singleLine = true)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onClone(id, name.trim()) }, enabled = id.isNotBlank() && name.isNotBlank()) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun buildDraft(
    jobId: String,
    name: String,
    description: String,
    tags: String,
    enabled: Boolean,
    operation: String,
    parametersJson: String,
    repositoryRef: String,
    credentialRefs: String,
): Result<SavedJobDraft> = runCatching {
    val parameters = BridgeJson.json.parseToJsonElement(parametersJson).jsonObject
    SavedJobDraft(
        jobId = jobId.trim(),
        name = name.trim(),
        description = description.trim(),
        tags = csv(tags),
        enabled = enabled,
        operation = OperationSpecV2(
            operation = operation.trim(),
            parameters = parameters,
            repositoryRef = repositoryRef.trim().takeIf { it.isNotBlank() },
            credentialRefs = csv(credentialRefs).map { SecretReference(it) },
        ),
    )
}

private fun csv(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
private fun normalizeIdInput(value: String): String = value.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')
