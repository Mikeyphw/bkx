package com.mikeyphw.bkx.android.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
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
    var jobId by remember { mutableStateOf(initial.jobId) }
    var jobOverrides by remember { mutableStateOf(initial.jobOverrides.joinToString("\n")) }
    var repositoryId by remember { mutableStateOf(initial.repositoryId) }
    var snapshotId by remember { mutableStateOf(initial.snapshotId) }
    var snapshotOverwrite by remember { mutableStateOf(initial.snapshotOverwrite) }
    var dryRun by remember { mutableStateOf(initial.dryRun) }
    var confirmDestructive by remember { mutableStateOf(initial.confirmDestructive) }
    val catalog = remember { BkxTaskerSharedCatalog.read() }
    var includeHidden by remember { mutableStateOf(initial.includeHidden) }
    var verifyAfter by remember { mutableStateOf(initial.verifyAfter) }
    var overwrite by remember { mutableStateOf(initial.overwrite) }
    var deleteExtraneous by remember { mutableStateOf(initial.deleteExtraneous) }
    var waitForResult by remember { mutableStateOf(initialWait) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showVariables by remember { mutableStateOf(false) }
    var showTaskerAdvanced by remember { mutableStateOf(requestId.isNotBlank()) }

    val request = AutomationRequest(
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
        compression = normalizeEditorChoice(compression, "gzip"),
        splitChunkSizeBytes = splitBytes.trim().toLongOrNull(),
        verifyAfter = verifyAfter,
        restoreSubset = splitAutomationList(restoreSubset),
        restoreMode = normalizeEditorChoice(restoreMode, "fresh_directory"),
        restoreConfirmation = restoreConfirmation.trim(),
        transferMode = normalizeEditorChoice(transferMode, "push"),
        overwrite = overwrite,
        deleteExtraneous = deleteExtraneous,
        checksum = checksum.trim(),
        roots = splitAutomationList(roots),
        maxDepth = maxDepth.trim().toIntOrNull() ?: 4,
        limit = limit.trim().toIntOrNull() ?: 500,
        pageToken = pageToken.trim(),
        pageSize = pageSize.trim().toIntOrNull() ?: 100,
        jobId = jobId.trim(),
        jobOverrides = splitAutomationList(jobOverrides),
        repositoryId = repositoryId.trim(),
        snapshotId = snapshotId.trim(),
        dryRun = dryRun,
        confirmDestructive = confirmDestructive,
        snapshotOverwrite = normalizeEditorChoice(snapshotOverwrite, "never"),
    )
    val editorErrors = taskerEditorErrors(
        request = request,
        splitBytesRaw = splitBytes,
        maxDepthRaw = maxDepth,
        limitRaw = limit,
        pageSizeRaw = pageSize,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding().imePadding(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { onSave(request, waitForResult) },
                        enabled = editorErrors.isEmpty(),
                        modifier = Modifier.weight(1.35f),
                    ) {
                        Text(if (editorErrors.isEmpty()) "Save to Tasker" else "Fix ${editorErrors.size} issue${if (editorErrors.size == 1) "" else "s"}")
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("BKX Automation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Configure one BKX operation for Tasker. Text fields can use Tasker variables such as %path or %archive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(
                title = "Operation",
                subtitle = action.editorDescription(),
            ) {
                OperationPicker(action = action, onActionChange = { action = it; showAdvanced = false })
                PreviewStrip(request.blurb(), editorErrors)
            }

            when (action) {
                AutomationAction.Backup -> {
                    SectionCard("Backup", "Create an archive through BKX's normal backup plan and safety checks.") {
                        ConfigField(
                            label = "Source",
                            value = source,
                            onValueChange = { source = it },
                            required = true,
                            helper = "Absolute local path or a Tasker variable, e.g. /storage/emulated/0/Documents or %source.",
                            error = editorErrorFor(editorErrors, "Backup source"),
                        )
                        ConfigField(
                            label = "Destination",
                            value = destination,
                            onValueChange = { destination = it },
                            required = true,
                            helper = "Absolute local path, rclone:// remote, or Tasker variable.",
                            error = editorErrorFor(editorErrors, "Backup destination"),
                        )
                        ChoiceField(
                            label = "Compression",
                            value = compression,
                            choices = listOf("gzip" to "Gzip", "zstd" to "Zstd", "none" to "None"),
                            onValueChange = { compression = it },
                            helper = "Choose a fixed value or use a Tasker variable.",
                            error = editorErrorFor(editorErrors, "Compression"),
                        )
                        ToggleRow("Verify after backup", "Run BKX verification after creating the archive.", verifyAfter) { verifyAfter = it }
                        ToggleRow("Include hidden files", "Include dotfiles and hidden entries.", includeHidden) { includeHidden = it }
                    }
                    AdvancedSection(showAdvanced = showAdvanced, onToggle = { showAdvanced = !showAdvanced }) {
                        ConfigField("Archive name", archiveName, { archiveName = it }, helper = "Optional. Leave blank for BKX naming.")
                        ConfigField(
                            "Split chunk bytes",
                            splitBytes,
                            { splitBytes = it },
                            helper = "Optional; minimum 1 MiB.",
                            error = editorErrorFor(editorErrors, "Split size"),
                            keyboardType = KeyboardType.Number,
                        )
                        ConfigField("Include patterns", include, { include = it }, helper = "One pattern per line.", minLines = 3)
                        ConfigField("Exclude patterns", exclude, { exclude = it }, helper = "One pattern per line.", minLines = 3)
                    }
                }

                AutomationAction.Restore -> {
                    SectionCard("Restore", "Preview first, then extract through BKX's existing restore safety barrier.") {
                        ConfigField(
                            "Archive",
                            archive,
                            { archive = it },
                            required = true,
                            helper = "Absolute local path, rclone:// archive, or Tasker variable.",
                            error = editorErrorFor(editorErrors, "Restore archive"),
                        )
                        ConfigField(
                            "Destination",
                            destination,
                            { destination = it },
                            required = true,
                            helper = "Absolute local destination or Tasker variable.",
                            error = editorErrorFor(editorErrors, "Restore destination"),
                        )
                        ChoiceField(
                            label = "Restore mode",
                            value = restoreMode,
                            choices = listOf("fresh_directory" to "New folder", "existing_directory" to "Existing"),
                            onValueChange = { restoreMode = it },
                            helper = "Use a fixed mode or a Tasker variable.",
                            error = editorErrorFor(editorErrors, "Restore mode"),
                        )
                        ToggleRow("Verify before restore", "Verify the archive before extraction.", verifyAfter) { verifyAfter = it }
                    }
                    SafetyCard(
                        title = "Restore confirmation",
                        text = "BKX still performs its preview. If that preview requires confirmation, supply exactly RESTORE or RESTORE ROOT. Leaving this blank is safe: BKX will fail instead of bypassing the barrier.",
                    ) {
                        ConfigField(
                            "Confirmation phrase",
                            restoreConfirmation,
                            { restoreConfirmation = it },
                            helper = "Optional: RESTORE, RESTORE ROOT, or a Tasker variable.",
                        )
                    }
                    AdvancedSection(showAdvanced = showAdvanced, onToggle = { showAdvanced = !showAdvanced }) {
                        ConfigField("Restore subset", restoreSubset, { restoreSubset = it }, helper = "One archive path per line. Blank restores all.", minLines = 3)
                    }
                }

                AutomationAction.Verify, AutomationAction.ArchiveInfo -> {
                    SectionCard(action.label, action.editorDescription()) {
                        ConfigField(
                            "Archive",
                            archive,
                            { archive = it },
                            required = true,
                            helper = "Absolute local path, rclone:// archive, or Tasker variable.",
                            error = editorErrorFor(editorErrors, "Archive"),
                        )
                    }
                }

                AutomationAction.ArchiveBrowse -> {
                    SectionCard("Browse archive", "Read an archive page without extracting it.") {
                        ConfigField(
                            "Archive",
                            archive,
                            { archive = it },
                            required = true,
                            helper = "Absolute local path, rclone:// archive, or Tasker variable.",
                            error = editorErrorFor(editorErrors, "Archive"),
                        )
                        ConfigField(
                            "Page size",
                            pageSize,
                            { pageSize = it },
                            helper = "Positive number of entries per page.",
                            error = editorErrorFor(editorErrors, "Page size"),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    AdvancedSection(showAdvanced = showAdvanced, onToggle = { showAdvanced = !showAdvanced }) {
                        ConfigField("Page token", pageToken, { pageToken = it }, helper = "Optional continuation token from a previous result.")
                    }
                }

                AutomationAction.ArchiveList -> {
                    SectionCard("List archives", "Search one or more local/rclone roots for BKX archives.") {
                        ConfigField(
                            "Roots",
                            roots,
                            { roots = it },
                            helper = "One root per line. Blank uses BKX defaults; Tasker variables are supported.",
                            minLines = 3,
                            error = editorErrorFor(editorErrors, "Archive roots"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ConfigField(
                                "Max depth",
                                maxDepth,
                                { maxDepth = it },
                                modifier = Modifier.weight(1f),
                                helper = "0–8",
                                error = editorErrorFor(editorErrors, "Max depth"),
                                keyboardType = KeyboardType.Number,
                            )
                            ConfigField(
                                "Limit",
                                limit,
                                { limit = it },
                                modifier = Modifier.weight(1f),
                                helper = "1–2000",
                                error = editorErrorFor(editorErrors, "List limit"),
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }
                }

                AutomationAction.Transfer -> {
                    SectionCard("Transfer", "Push, pull, or synchronize through BKX's existing transfer planner.") {
                        ConfigField(
                            "Source path(s)",
                            source,
                            { source = it },
                            required = true,
                            helper = "One local/rclone source per line; Tasker variables are supported.",
                            minLines = 2,
                            error = editorErrorFor(editorErrors, "Transfer sources"),
                        )
                        ConfigField(
                            "Destination",
                            destination,
                            { destination = it },
                            required = true,
                            helper = "Absolute local path, rclone:// remote, or Tasker variable.",
                            error = editorErrorFor(editorErrors, "Transfer destination"),
                        )
                        ChoiceField(
                            label = "Transfer mode",
                            value = transferMode,
                            choices = listOf("push" to "Push", "pull" to "Pull", "sync" to "Sync"),
                            onValueChange = { transferMode = it },
                            helper = "Use a fixed mode or a Tasker variable.",
                            error = editorErrorFor(editorErrors, "Transfer mode"),
                        )
                        ToggleRow("Verify after transfer", "Verify the completed transfer.", verifyAfter) { verifyAfter = it }
                        ToggleRow("Allow overwrite", "Permit replacing destination files when BKX's plan allows it.", overwrite) { overwrite = it }
                        if (transferMode == "sync") {
                            SafetyCard(
                                title = "Sync deletion approval",
                                text = "Sync can remove destination-only files. BKX requires explicit approval before it will run.",
                            ) {
                                ToggleRow(
                                    "Allow destination-only deletes",
                                    "Required for sync; BKX still applies its transfer plan safeguards.",
                                    deleteExtraneous,
                                ) { deleteExtraneous = it }
                            }
                        } else {
                            ToggleRow(
                                "Allow destination-only deletes",
                                "Only used by sync. Keep this off unless a fixed or variable-driven sync should delete destination-only files.",
                                deleteExtraneous,
                            ) { deleteExtraneous = it }
                        }
                    }
                    AdvancedSection(showAdvanced = showAdvanced, onToggle = { showAdvanced = !showAdvanced }) {
                        ConfigField("Checksum", checksum, { checksum = it }, helper = "Optional expected checksum or Tasker variable.")
                    }
                }

                AutomationAction.RunSavedJob -> {
                    SectionCard("Run saved job", "Run the same revisioned job definition used by the BKX app and CLI.") {
                        ConfigField(
                            "Saved job ID",
                            jobId,
                            { jobId = it },
                            required = true,
                            helper = catalog.jobs.take(4).joinToString(prefix = "Shared jobs: ", separator = ", ") { it.jobId }
                                .takeIf { catalog.jobs.isNotEmpty() } ?: "Use the job ID shown under Backup → Jobs. Tasker variables are supported.",
                            error = editorErrorFor(editorErrors, "Saved job"),
                        )
                        if (catalog.jobs.isNotEmpty()) {
                            Text("Available now: ${catalog.jobs.joinToString { "${it.name} (${it.jobId})" }}", style = MaterialTheme.typography.bodySmall)
                        }
                        ConfigField(
                            "Repository override",
                            repositoryId,
                            { repositoryId = it },
                            helper = "Optional. Leave blank to use the saved job repository, or select another shared repository for this run only. ${repositoryCatalogHint(catalog)}",
                            error = editorErrorFor(editorErrors, "Saved-job repository override"),
                        )
                        ConfigField(
                            "Execution overrides",
                            jobOverrides,
                            { jobOverrides = it },
                            helper = "Optional execution-only KEY=VALUE entries, one per line. source=/path, repository=repo-id, tags=[...] and other validated operation fields never modify the saved job revision.",
                            minLines = 3,
                            error = editorErrorFor(editorErrors, "override"),
                        )
                    }
                }

                AutomationAction.CreateSnapshot -> {
                    SectionCard("Create snapshot", "Create an incremental Restic snapshot in a named BKX repository.") {
                        ConfigField("Repository ID", repositoryId, { repositoryId = it }, required = true,
                            helper = repositoryCatalogHint(catalog), error = editorErrorFor(editorErrors, "Repository"))
                        ConfigField("Source path(s)", source, { source = it }, required = true,
                            helper = "One absolute local path per line; Tasker variables are supported.", minLines = 2,
                            error = editorErrorFor(editorErrors, "Snapshot sources"))
                    }
                }

                AutomationAction.RepositoryCheck -> {
                    SectionCard("Check repository", "Run Restic repository integrity checks through BKX.") {
                        ConfigField("Repository ID", repositoryId, { repositoryId = it }, required = true,
                            helper = repositoryCatalogHint(catalog), error = editorErrorFor(editorErrors, "Repository"))
                    }
                }

                AutomationAction.RetentionApply, AutomationAction.RepositoryPrune -> {
                    SectionCard(action.label, action.editorDescription()) {
                        ConfigField("Repository ID", repositoryId, { repositoryId = it }, required = true,
                            helper = repositoryCatalogHint(catalog), error = editorErrorFor(editorErrors, "Repository"))
                        ToggleRow(
                            "Preview only",
                            if (dryRun) "Safe dry-run; no repository data is changed." else "Real repository changes will be attempted.",
                            dryRun,
                        ) { dryRun = it; if (it) confirmDestructive = false }
                    }
                    if (!dryRun) {
                        SafetyCard(
                            title = "Explicit destructive approval",
                            text = if (action == AutomationAction.RepositoryPrune)
                                "Prune can permanently remove unreferenced repository data. BKX requires this switch in the saved Tasker configuration."
                            else
                                "Retention can forget snapshots according to the repository policy. BKX requires this switch in the saved Tasker configuration.",
                        ) {
                            ToggleRow("I approve this real maintenance run", "Required before Tasker can save a non-preview maintenance action.", confirmDestructive) {
                                confirmDestructive = it
                            }
                        }
                    }
                }

                AutomationAction.SnapshotRestore -> {
                    SectionCard("Restore snapshot", "Restore a Restic snapshot through BKX with safe overwrite defaults.") {
                        ConfigField("Repository ID", repositoryId, { repositoryId = it }, required = true,
                            helper = repositoryCatalogHint(catalog), error = editorErrorFor(editorErrors, "Repository"))
                        ConfigField("Snapshot ID", snapshotId, { snapshotId = it }, required = true,
                            helper = "Snapshot ID, latest, or a Tasker variable.", error = editorErrorFor(editorErrors, "Snapshot id"))
                        ConfigField("Destination", destination, { destination = it }, required = true,
                            helper = "Absolute local destination path.", error = editorErrorFor(editorErrors, "Snapshot restore destination"))
                        ConfigField("Restore subset", restoreSubset, { restoreSubset = it },
                            helper = "Optional. One snapshot path per line; blank restores the whole snapshot.", minLines = 3)
                        ChoiceField(
                            "Overwrite policy",
                            snapshotOverwrite,
                            listOf("never" to "Never", "if-changed" to "If changed", "if-newer" to "If newer", "always" to "Always"),
                            { snapshotOverwrite = it },
                            "Never is the safe default.",
                            editorErrorFor(editorErrors, "Snapshot overwrite"),
                        )
                        ToggleRow("Preview only", "Dry-run the restore without writing files.", dryRun) {
                            dryRun = it
                            if (it) confirmDestructive = false
                        }
                    }
                    if (!dryRun) {
                        SafetyCard("Restore approval", "A real automated restore requires explicit approval in this Tasker action.") {
                            ToggleRow("I approve this real restore", "Required even when overwrite is Never.", confirmDestructive) {
                                confirmDestructive = it
                            }
                        }
                    }
                }

                AutomationAction.Cancel, AutomationAction.Status -> {
                    SectionCard(action.label, action.editorDescription()) {
                        ConfigField(
                            "Target request ID",
                            targetRequestId,
                            { targetRequestId = it },
                            required = true,
                            helper = "The BKX automation/request ID returned by an earlier action; Tasker variables are supported.",
                            error = editorErrorFor(editorErrors, "Target request id"),
                        )
                    }
                }
            }

            SectionCard("Tasker result", "Control how this action returns status to Tasker.") {
                ToggleRow(
                    "Wait for final result",
                    if (waitForResult) "Tasker receives the final BKX result variables." else "Tasker returns after BKX queues the operation.",
                    waitForResult,
                ) { waitForResult = it }

                TextButton(onClick = { showVariables = !showVariables }) {
                    Text(if (showVariables) "Hide returned variables" else "Show returned variables")
                }
                if (showVariables) {
                    ReturnedVariablesList()
                }

                TextButton(onClick = { showTaskerAdvanced = !showTaskerAdvanced }) {
                    Text(if (showTaskerAdvanced) "Hide request identity" else "Show request identity")
                }
                if (showTaskerAdvanced) {
                    ConfigField(
                        "Request ID",
                        requestId,
                        { requestId = it },
                        helper = "Optional. Leave blank for BKX to generate one, or use a stable Tasker variable for deliberate idempotent retries.",
                    )
                } else if (requestId.isNotBlank()) {
                    Text(
                        "A custom request ID is configured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (editorErrors.isNotEmpty()) {
                SafetyCard(
                    title = "Before saving",
                    text = editorErrors.joinToString("\n") { "• $it" },
                ) {}
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun OperationPicker(action: AutomationAction, onActionChange: (AutomationAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(action.label, fontWeight = FontWeight.SemiBold)
                Text(action.editorCategory(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Change", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AutomationAction.entries.groupBy { it.editorCategory() }.forEach { (category, items) ->
                Text(
                    category,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                items.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.label,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = if (item == action) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (item == action) {
                                        Text("Selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(item.editorDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            onActionChange(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun SafetyCard(title: String, text: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            content()
        }
    }
}

@Composable
private fun AdvancedSection(showAdvanced: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Advanced options", fontWeight = FontWeight.SemiBold)
                Text(
                    if (showAdvanced) "Less common settings are shown below." else "Expand only when you need uncommon controls.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (showAdvanced) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
        }
        if (showAdvanced) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PreviewStrip(blurb: String, errors: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (errors.isEmpty()) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tasker summary", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(blurb, style = MaterialTheme.typography.bodySmall)
            if (errors.isNotEmpty()) {
                Text(
                    "${errors.size} issue${if (errors.size == 1) "" else "s"} to fix before saving.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ChoiceField(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
    helper: String,
    error: String? = null,
) {
    val fixedValues = choices.map { it.first }
    var custom by remember(value in fixedValues) { mutableStateOf(value !in fixedValues) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (!custom) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (wire, display) ->
                    FilterChip(selected = value == wire, onClick = { onValueChange(wire) }, label = { Text(display) })
                }
            }
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        } else {
            ConfigField(
                label = "$label Tasker variable",
                value = value,
                onValueChange = onValueChange,
                helper = "Example: %bkx_mode. Tasker substitutes it before BKX validates the request.",
                error = error,
            )
        }
        TextButton(
            onClick = {
                custom = !custom
                if (!custom && value !in fixedValues) onValueChange(fixedValues.first())
            },
        ) {
            Text(if (custom) "Use fixed choice" else "Use Tasker variable")
        }
        Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    required: Boolean = false,
    helper: String? = null,
    error: String? = null,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val supporting = error ?: helper
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (required) "$label *" else label) },
        modifier = modifier,
        singleLine = minLines == 1,
        minLines = minLines,
        isError = error != null,
        supportingText = if (supporting != null) ({ Text(supporting) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ReturnedVariablesList() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AutomationResult.relevantVariables.forEach { name ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(taskerVariableDescription(name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun taskerVariableDescription(name: String): String = when (name) {
    "%bkx_request_id" -> "Stable BKX automation request ID."
    "%bkx_action" -> "Operation wire name."
    "%bkx_status" -> "accepted, running, success, failed, or cancelled."
    "%bkx_operation_request_id" -> "Current internal BKX operation request ID when available."
    "%bkx_summary" -> "Human-readable result summary."
    "%bkx_error_code" -> "Stable machine-readable failure code."
    "%bkx_error_message" -> "Human-readable failure detail."
    "%bkx_artifact_paths" -> "Newline-separated artifact paths."
    "%bkx_artifact_sha256" -> "Newline-separated SHA-256 values matching artifact paths."
    "%bkx_files_total" -> "Total files reported by the operation when available."
    "%bkx_bytes_total" -> "Total bytes reported by the operation when available."
    "%bkx_job_id" -> "Shared saved-job ID when this action/event belongs to a job."
    "%bkx_run_id" -> "Concrete BKX run/bridge request ID for correlation and cancellation."
    "%bkx_repository_id" -> "Named repository ID when available."
    "%bkx_snapshot_id" -> "Restic snapshot ID when available."
    "%bkx_files_processed" -> "Files processed by the completed operation when available."
    "%bkx_bytes_processed" -> "Logical bytes processed by the completed operation when available."
    "%bkx_bytes_added" -> "New Restic repository data added by an incremental snapshot when available."
    "%bkx_bytes_reused" -> "Logical bytes reused from existing Restic data when available."
    "%bkx_duration_ms" -> "Completed operation duration in milliseconds."
    else -> "BKX automation result variable."
}

private fun AutomationAction.editorCategory(): String = when (this) {
    AutomationAction.Backup, AutomationAction.Restore, AutomationAction.Verify -> "Backup & recovery"
    AutomationAction.ArchiveList, AutomationAction.ArchiveInfo, AutomationAction.ArchiveBrowse -> "Archive tools"
    AutomationAction.Transfer -> "Files & remotes"
    AutomationAction.RunSavedJob -> "Saved jobs"
    AutomationAction.CreateSnapshot, AutomationAction.SnapshotRestore -> "Snapshots"
    AutomationAction.RepositoryCheck, AutomationAction.RetentionApply, AutomationAction.RepositoryPrune -> "Repository maintenance"
    AutomationAction.Cancel, AutomationAction.Status -> "Operation control"
}

private fun AutomationAction.editorDescription(): String = when (this) {
    AutomationAction.Backup -> "Create a BKX backup archive from a local source."
    AutomationAction.Restore -> "Preview and restore an archive with BKX confirmation barriers intact."
    AutomationAction.Verify -> "Verify archive integrity without restoring it."
    AutomationAction.ArchiveList -> "Find BKX archives under local or rclone roots."
    AutomationAction.ArchiveInfo -> "Read metadata and summary information for one archive."
    AutomationAction.ArchiveBrowse -> "Browse archive contents page by page without extracting."
    AutomationAction.Transfer -> "Push, pull, or sync between local paths and rclone remotes."
    AutomationAction.RunSavedJob -> "Run one shared BKX Saved Job by ID."
    AutomationAction.CreateSnapshot -> "Create an incremental Restic snapshot in a named repository."
    AutomationAction.RepositoryCheck -> "Check repository integrity without changing snapshot retention."
    AutomationAction.RetentionApply -> "Preview or explicitly apply the repository's saved retention policy."
    AutomationAction.RepositoryPrune -> "Preview or explicitly prune unreferenced Restic repository data."
    AutomationAction.SnapshotRestore -> "Preview or explicitly restore a Restic snapshot with safe overwrite controls."
    AutomationAction.Cancel -> "Cancel a previously queued/running BKX automation request."
    AutomationAction.Status -> "Read the current or terminal status of a BKX automation request."
}

private fun repositoryCatalogHint(catalog: BkxTaskerCatalog): String =
    if (catalog.repositories.isEmpty()) "Use the repository ID shown under Backup → Repositories. Tasker variables are supported."
    else "Shared repositories: " + catalog.repositories.take(5).joinToString { it.repositoryId }

private fun taskerEditorErrors(
    request: AutomationRequest,
    splitBytesRaw: String,
    maxDepthRaw: String,
    limitRaw: String,
    pageSizeRaw: String,
): List<String> = buildList {
    fun taskerVariable(value: String): Boolean = TASKER_VARIABLE.containsMatchIn(value)
    fun validLocal(value: String): Boolean = value.startsWith("/") || taskerVariable(value)
    fun validLocation(value: String): Boolean = validLocal(value) || value.startsWith("rclone://", ignoreCase = true)
    fun requiredLocation(label: String, value: String, localOnly: Boolean = false) {
        if (value.isBlank()) add("$label is required.")
        else if (localOnly && !validLocal(value)) add("$label must be an absolute local path or Tasker variable.")
        else if (!localOnly && !validLocation(value)) add("$label must be an absolute local path, rclone:// location, or Tasker variable.")
    }

    when (request.action) {
        AutomationAction.Backup -> {
            requiredLocation("Backup source", request.source, localOnly = true)
            requiredLocation("Backup destination", request.destination)
            if (!taskerVariable(request.compression) && request.compression !in setOf("gzip", "zstd", "none")) {
                add("Compression must be gzip, zstd, none, or a Tasker variable.")
            }
            if (splitBytesRaw.isNotBlank()) {
                val size = splitBytesRaw.trim().toLongOrNull()
                if (size == null || size < 1024L * 1024L) add("Split size must be at least 1 MiB.")
            }
        }
        AutomationAction.Restore -> {
            requiredLocation("Restore archive", request.archive)
            requiredLocation("Restore destination", request.destination, localOnly = true)
            if (!taskerVariable(request.restoreMode) && request.restoreMode !in setOf("fresh_directory", "existing_directory")) {
                add("Restore mode must be New folder, Existing, or a Tasker variable.")
            }
        }
        AutomationAction.Verify, AutomationAction.ArchiveInfo, AutomationAction.ArchiveBrowse -> requiredLocation("Archive", request.archive)
        AutomationAction.ArchiveList -> {
            if (request.roots.any { !validLocation(it) }) add("Archive roots must be local/rclone locations or Tasker variables.")
            val depth = maxDepthRaw.trim().toIntOrNull()
            if (depth == null || depth !in 0..8) add("Max depth must be 0–8.")
            val parsedLimit = limitRaw.trim().toIntOrNull()
            if (parsedLimit == null || parsedLimit !in 1..2000) add("List limit must be 1–2000.")
        }
        AutomationAction.Transfer -> {
            val sources = splitAutomationList(request.source)
            if (sources.isEmpty()) add("Transfer sources are required.")
            else if (sources.any { !validLocation(it) }) add("Transfer sources must be local/rclone locations or Tasker variables.")
            requiredLocation("Transfer destination", request.destination)
            if (!taskerVariable(request.transferMode) && request.transferMode !in setOf("push", "pull", "sync")) {
                add("Transfer mode must be Push, Pull, Sync, or a Tasker variable.")
            }
            if (request.transferMode == "sync" && !request.deleteExtraneous) add("Sync requires explicit destination-only delete approval.")
        }
        AutomationAction.RunSavedJob -> if (request.jobId.isBlank()) add("Saved job id is required.")
        AutomationAction.CreateSnapshot -> {
            if (request.repositoryId.isBlank()) add("Repository id is required.")
            val sources = splitAutomationList(request.source)
            if (sources.isEmpty()) add("Snapshot sources are required.")
            else if (sources.any { !validLocal(it) }) add("Snapshot sources must be absolute local paths or Tasker variables.")
        }
        AutomationAction.RepositoryCheck -> if (request.repositoryId.isBlank()) add("Repository id is required.")
        AutomationAction.RetentionApply, AutomationAction.RepositoryPrune -> {
            if (request.repositoryId.isBlank()) add("Repository id is required.")
            if (!request.dryRun && !request.confirmDestructive) add("Real maintenance requires explicit destructive approval.")
        }
        AutomationAction.SnapshotRestore -> {
            if (request.repositoryId.isBlank()) add("Repository id is required.")
            if (request.snapshotId.isBlank()) add("Snapshot id is required.")
            requiredLocation("Snapshot restore destination", request.destination, localOnly = true)
            if (!taskerVariable(request.snapshotOverwrite) && request.snapshotOverwrite !in setOf("never", "if-changed", "if-newer", "always")) {
                add("Snapshot overwrite mode must be Never, If changed, If newer, Always, or a Tasker variable.")
            }
            if (!request.dryRun && !request.confirmDestructive) add("Real snapshot restore requires explicit approval.")
        }
        AutomationAction.Cancel, AutomationAction.Status -> if (request.targetRequestId.isBlank()) add("Target request id is required.")
    }
    if (request.action == AutomationAction.ArchiveBrowse) {
        val size = pageSizeRaw.trim().toIntOrNull()
        if (size == null || size < 1) add("Page size must be a positive number.")
    }
}

private fun editorErrorFor(errors: List<String>, prefix: String): String? =
    errors.firstOrNull { it.startsWith(prefix, ignoreCase = true) }

private val TASKER_VARIABLE = Regex("%[A-Za-z][A-Za-z0-9_]*")

private fun normalizeEditorChoice(value: String, fallback: String): String {
    val trimmed = value.trim()
    return if (TASKER_VARIABLE.containsMatchIn(trimmed)) trimmed else trimmed.lowercase().ifBlank { fallback }
}

private fun AutomationRequest.blurb(): String = when (action) {
    AutomationAction.Backup -> "Backup ${source.ifBlank { "source" }} → ${destination.ifBlank { "destination" }}"
    AutomationAction.Restore -> "Restore ${archive.ifBlank { "archive" }} → ${destination.ifBlank { "destination" }}"
    AutomationAction.Verify -> "Verify ${archive.ifBlank { "archive" }}"
    AutomationAction.ArchiveList -> "List BKX archives"
    AutomationAction.ArchiveInfo -> "Archive info ${archive.ifBlank { "archive" }}"
    AutomationAction.ArchiveBrowse -> "Browse ${archive.ifBlank { "archive" }}"
    AutomationAction.Transfer -> "${transferMode.ifBlank { "push" }} ${source.ifBlank { "source" }} → ${destination.ifBlank { "destination" }}"
    AutomationAction.RunSavedJob -> "Run saved job ${jobId.ifBlank { "job" }}"
    AutomationAction.CreateSnapshot -> "Snapshot → ${repositoryId.ifBlank { "repository" }}"
    AutomationAction.RepositoryCheck -> "Check repository ${repositoryId.ifBlank { "repository" }}"
    AutomationAction.RetentionApply -> "${if (dryRun) "Preview" else "Apply"} retention ${repositoryId.ifBlank { "repository" }}"
    AutomationAction.RepositoryPrune -> "${if (dryRun) "Preview" else "Prune"} ${repositoryId.ifBlank { "repository" }}"
    AutomationAction.SnapshotRestore -> "${if (dryRun) "Preview" else "Restore"} snapshot ${snapshotId.ifBlank { "snapshot" }}"
    AutomationAction.Cancel -> "Cancel ${targetRequestId.ifBlank { "BKX operation" }}"
    AutomationAction.Status -> "Status ${targetRequestId.ifBlank { "BKX operation" }}"
}
