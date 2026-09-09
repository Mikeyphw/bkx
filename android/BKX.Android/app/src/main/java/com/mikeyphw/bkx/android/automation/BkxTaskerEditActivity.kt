package com.mikeyphw.bkx.android.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.ui.theme.BkxTheme

class BkxTaskerEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != LocalePluginContract.ACTION_EDIT_SETTING || callingPackage !in TRUSTED_TASKER_HOSTS) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        enableEdgeToEdge()
        val existing = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        val initial = existing?.let(AutomationIntentCodec::fromTaskerBundle) ?: AutomationRequest(AutomationAction.Backup)
        val initialWait = existing?.getBoolean(BkxAutomationContract.EXTRA_WAIT_FOR_RESULT, true) ?: true
        setContent {
            BkxTheme {
                TaskerConfigurationScreen(
                    initial = initial,
                    initialWait = initialWait,
                    onCancel = { finish() },
                    onSave = { request, wait -> finishWithConfiguration(request, wait) },
                )
            }
        }
    }

    private fun finishWithConfiguration(request: AutomationRequest, wait: Boolean) {
        val access = AutomationAccessStore(this).ensureEnabled()
        val bundle = AutomationIntentCodec.toPluginBundle(request, access.taskerToken, wait)
        val result = Intent().apply {
            putExtra(LocalePluginContract.EXTRA_BUNDLE, bundle)
            putExtra(LocalePluginContract.EXTRA_BLURB, request.blurb())
            if (hostSupports(TaskerPluginContract.CAPABILITY_RELEVANT_VARIABLES)) {
                putExtra(TaskerPluginContract.EXTRA_RELEVANT_VARIABLES, AutomationResult.relevantVariables)
            }
            if (wait && hostSupports(TaskerPluginContract.CAPABILITY_SYNCHRONOUS_EXECUTION)) {
                putExtra(TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT, TaskerPluginContract.REQUEST_TIMEOUT_MILLIS.toInt())
            }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
    private fun hostSupports(capability: Int): Boolean =
        (intent.getIntExtra(TaskerPluginContract.EXTRA_HOST_CAPABILITIES, 0) and capability) != 0

    private companion object {
        val TRUSTED_TASKER_HOSTS = setOf("net.dinglisch.android.taskerm", "com.twofortyfouram.locale")
    }

}

@Composable
private fun TaskerConfigurationScreen(
    initial: AutomationRequest,
    initialWait: Boolean,
    onCancel: () -> Unit,
    onSave: (AutomationRequest, Boolean) -> Unit,
) {
    var action by remember { mutableStateOf(initial.action) }
    var requestId by remember { mutableStateOf(initial.requestId) }
    var targetRequestId by remember { mutableStateOf(initial.targetRequestId) }
    var source by remember { mutableStateOf(initial.source) }
    var destination by remember { mutableStateOf(initial.destination) }
    var archive by remember { mutableStateOf(initial.archive) }
    var archiveName by remember { mutableStateOf(initial.archiveName) }
    var include by remember { mutableStateOf(initial.includePatterns.joinToString("\n")) }
    var exclude by remember { mutableStateOf(initial.excludePatterns.joinToString("\n")) }
    var compression by remember { mutableStateOf(initial.compression) }
    var splitBytes by remember { mutableStateOf(initial.splitChunkSizeBytes?.toString().orEmpty()) }
    var restoreSubset by remember { mutableStateOf(initial.restoreSubset.joinToString("\n")) }
    var restoreMode by remember { mutableStateOf(initial.restoreMode) }
    var restoreConfirmation by remember { mutableStateOf(initial.restoreConfirmation) }
    var transferMode by remember { mutableStateOf(initial.transferMode) }
    var checksum by remember { mutableStateOf(initial.checksum) }
    var roots by remember { mutableStateOf(initial.roots.joinToString("\n")) }
    var maxDepth by remember { mutableStateOf(initial.maxDepth.toString()) }
    var limit by remember { mutableStateOf(initial.limit.toString()) }
    var pageToken by remember { mutableStateOf(initial.pageToken) }
    var pageSize by remember { mutableStateOf(initial.pageSize.toString()) }
    var includeHidden by remember { mutableStateOf(initial.includeHidden) }
    var verifyAfter by remember { mutableStateOf(initial.verifyAfter) }
    var overwrite by remember { mutableStateOf(initial.overwrite) }
    var deleteExtraneous by remember { mutableStateOf(initial.deleteExtraneous) }
    var waitForResult by remember { mutableStateOf(initialWait) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BKX Automation", style = MaterialTheme.typography.headlineSmall)
        Text("Tasker runs the same BKX safety previews and operation coordinator as the app UI.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AutomationAction.entries.chunked(3).forEach { actions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { item ->
                    FilterChip(selected = action == item, onClick = { action = item }, label = { Text(item.label) })
                }
            }
        }
        ConfigField("Request ID (optional; supports Tasker variables)", requestId) { requestId = it }
        when (action) {
            AutomationAction.Backup -> {
                ConfigField("Source path", source) { source = it }
                ConfigField("Destination path or rclone:// remote", destination) { destination = it }
                ConfigField("Archive name (optional)", archiveName) { archiveName = it }
                ConfigField("Include patterns (one per line)", include) { include = it }
                ConfigField("Exclude patterns (one per line)", exclude) { exclude = it }
                ConfigField("Compression: gzip, zstd, none", compression) { compression = it }
                ConfigField("Split chunk bytes (optional)", splitBytes) { splitBytes = it }
                ToggleRow("Include hidden files", includeHidden) { includeHidden = it }
                ToggleRow("Verify after backup", verifyAfter) { verifyAfter = it }
            }
            AutomationAction.Restore -> {
                ConfigField("Archive path or rclone:// archive", archive) { archive = it }
                ConfigField("Destination absolute local path", destination) { destination = it }
                ConfigField("Subset paths (one per line; blank = all)", restoreSubset) { restoreSubset = it }
                ConfigField("Mode: fresh_directory or existing_directory", restoreMode) { restoreMode = it }
                ConfigField("Confirmation when requested: RESTORE or RESTORE ROOT", restoreConfirmation) { restoreConfirmation = it }
                ToggleRow("Verify before restore", verifyAfter) { verifyAfter = it }
            }
            AutomationAction.Verify, AutomationAction.ArchiveInfo -> ConfigField("Archive path or rclone:// archive", archive) { archive = it }
            AutomationAction.ArchiveBrowse -> {
                ConfigField("Archive path or rclone:// archive", archive) { archive = it }
                ConfigField("Page token (optional)", pageToken) { pageToken = it }
                ConfigField("Page size", pageSize) { pageSize = it }
            }
            AutomationAction.ArchiveList -> {
                ConfigField("Roots (one per line; local or rclone://)", roots) { roots = it }
                ConfigField("Max depth", maxDepth) { maxDepth = it }
                ConfigField("Limit", limit) { limit = it }
            }
            AutomationAction.Transfer -> {
                ConfigField("Source path(s), one per line", source) { source = it }
                ConfigField("Destination path or rclone:// remote", destination) { destination = it }
                ConfigField("Mode: push, pull, sync", transferMode) { transferMode = it }
                ConfigField("Checksum (optional)", checksum) { checksum = it }
                ToggleRow("Verify after transfer", verifyAfter) { verifyAfter = it }
                ToggleRow("Allow overwrite", overwrite) { overwrite = it }
                ToggleRow("Allow delete destination-only files for sync", deleteExtraneous) { deleteExtraneous = it }
            }
            AutomationAction.Cancel, AutomationAction.Status -> ConfigField("Target BKX automation/request ID", targetRequestId) { targetRequestId = it }
        }
        ToggleRow("Wait for final result and return Tasker variables", waitForResult) { waitForResult = it }
        Text(
            "Returned variables: ${AutomationResult.relevantVariables.joinToString()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    onSave(
                        AutomationRequest(
                            action = action,
                            requestId = requestId.trim(),
                            targetRequestId = targetRequestId.trim(),
                            source = source.trim(),
                            destination = destination.trim(),
                            archive = archive.trim(),
                            archiveName = archiveName.trim(),
                            includePatterns = splitAutomationList(include),
                            excludePatterns = splitAutomationList(exclude),
                            includeHidden = includeHidden,
                            compression = compression.trim().lowercase().ifBlank { "gzip" },
                            splitChunkSizeBytes = splitBytes.trim().toLongOrNull(),
                            verifyAfter = verifyAfter,
                            restoreSubset = splitAutomationList(restoreSubset),
                            restoreMode = restoreMode.trim().lowercase().ifBlank { "fresh_directory" },
                            restoreConfirmation = restoreConfirmation.trim(),
                            transferMode = transferMode.trim().lowercase().ifBlank { "push" },
                            overwrite = overwrite,
                            deleteExtraneous = deleteExtraneous,
                            checksum = checksum.trim(),
                            roots = splitAutomationList(roots),
                            maxDepth = maxDepth.trim().toIntOrNull() ?: 4,
                            limit = limit.trim().toIntOrNull() ?: 500,
                            pageToken = pageToken.trim(),
                            pageSize = pageSize.trim().toIntOrNull() ?: 100,
                        ),
                        waitForResult,
                    )
                },
            ) { Text("Save") }
        }
    }
}


@Composable
private fun ConfigField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun AutomationRequest.blurb(): String = when (action) {
    AutomationAction.Backup -> "Backup ${source.ifBlank { "source" }} → ${destination.ifBlank { "destination" }}"
    AutomationAction.Restore -> "Restore ${archive.ifBlank { "archive" }} → ${destination.ifBlank { "destination" }}"
    AutomationAction.Verify -> "Verify ${archive.ifBlank { "archive" }}"
    AutomationAction.ArchiveList -> "List BKX archives"
    AutomationAction.ArchiveInfo -> "Archive info ${archive.ifBlank { "archive" }}"
    AutomationAction.ArchiveBrowse -> "Browse ${archive.ifBlank { "archive" }}"
    AutomationAction.Transfer -> "${transferMode.ifBlank { "push" }} ${source.ifBlank { "source" }} → ${destination.ifBlank { "destination" }}"
    AutomationAction.Cancel -> "Cancel ${targetRequestId.ifBlank { "BKX operation" }}"
    AutomationAction.Status -> "Status ${targetRequestId.ifBlank { "BKX operation" }}"
}
