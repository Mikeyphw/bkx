package com.mikeyphw.bkx.android.feature.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.remotes.RcloneConfigManager
import com.mikeyphw.bkx.android.r1.SkybindR1ReadClient
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RcloneRemotesScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { RcloneConfigManager(context) }
    val skybindScope = rememberCoroutineScope()
    var skybindRemote by remember { mutableStateOf("") }
    var skybindPath by remember { mutableStateOf("") }
    var skybindResult by remember { mutableStateOf("Not connected. An operator must approve this running BKX process.") }
    var skybindBusy by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(manager.snapshot()) }
    var message by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { manager.importConfig(uri) }
                .onSuccess {
                    snapshot = it
                    val importedCount = it.profiles.count { profile -> !profile.managedByBkx }
                    message = "Imported $importedCount rclone remote${if (importedCount == 1) "" else "s"}."
                    onEvent(BkxHomeEvent.RefreshBackend)
                }
                .onFailure { message = it.message ?: "Could not import rclone.conf." }
        }
    }
    val importedProfiles = snapshot.profiles.filterNot { it.managedByBkx }
    val runtimeReady = "remote.rclone" in state.backendToolState.capabilities
    val configuredInEngine = "remote.rclone.configured" in state.backendToolState.capabilities

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseMoreRoute) }) { Text("Back to More") }
        BkxPageHeader("rclone remotes", "Import additional provider remotes without exposing credentials in BKX history or diagnostics. Google Drive managed by BKX has its own screen.")

        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("rclone runtime", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !runtimeReady -> "Local rclone is unavailable. BKX Lite does not bundle it; use Embedded for native remote transfers. Skybind remains a separate, approved read-only provider."
                            snapshot.configured && !configuredInEngine -> "Configuration changed; refresh the engine so the current session sees it."
                            else -> "The packaged Android ARM64 runtime is ready for remote transfer and browsing."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BkxStatusPill(if (runtimeReady) "READY" else "UNAVAILABLE", if (runtimeReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }) { Text("Refresh runtime") }
        }

        BkxSurfaceCard {
            Text("Configuration", style = MaterialTheme.typography.titleLarge)
            Text(
                if (snapshot.configured) {
                    "BKX stores the imported configuration in app-private storage. Tokens and passwords are never rendered on this screen."
                } else {
                    "Import an existing rclone.conf to enable remote locations."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                Text(if (snapshot.configured) "Replace rclone.conf" else "Import rclone.conf")
            }
            if (snapshot.configured) {
                OutlinedButton(
                    onClick = {
                        runCatching { manager.deleteConfig() }
                            .onSuccess {
                                snapshot = manager.snapshot()
                                message = "Removed BKX rclone configuration."
                                onEvent(BkxHomeEvent.RefreshBackend)
                            }
                            .onFailure { message = it.message ?: "Could not remove rclone.conf." }
                    },
                ) { Text("Remove configuration") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        BkxSurfaceCard {
            Text("Skybind · live read-only provider", style = MaterialTheme.typography.titleLarge)
            Text("Uses Skybind's authenticated broker, not BKX rclone. Start the Skybind authority foreground session and operator gateway; then approve this BKX PID. No automatic fallback.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = skybindRemote, onValueChange = { skybindRemote = it },
                label = { Text("Approved Skybind remote") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = skybindPath, onValueChange = { skybindPath = it },
                label = { Text("Relative path (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            listOf("rclone.lsjson" to "List", "rclone.size" to "Size",
                "rclone.listremotes" to "Remotes", "rclone.version" to "Version",
                "system.capabilities" to "Capabilities").forEach { (op, label) ->
                OutlinedButton(
                    enabled = !skybindBusy,
                    onClick = {
                        val remote = skybindRemote.trim()
                        val path = skybindPath.trim()
                        skybindBusy = true
                        skybindResult = "Waiting for an operator-approved grant for this BKX process…"
                        skybindScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    SkybindR1ReadClient.read(op, remote, path)
                                }
                                skybindResult = result.toString().take(16384)
                            } catch (e: Exception) {
                                // No token, exception stack or credentials are shown.
                                skybindResult = "Read unavailable or denied. Check gateway, approved PID/scope and SELinux."
                            } finally {
                                skybindBusy = false
                            }
                        }
                    }
                ) { Text("Skybind $label") }
            }
            Text(skybindResult, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (importedProfiles.isNotEmpty()) {
            BkxSectionHeader("Remotes")
            importedProfiles.forEach { profile ->
                BkxSurfaceCard {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(profile.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Available in Transfer as a browsable remote.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (snapshot.ignoredRemoteNames.isNotEmpty()) {
            BkxSurfaceCard {
                Text("Some remotes need renaming", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    "BKX remote names may contain letters, numbers, dot, underscore, and dash. Rename: ${snapshot.ignoredRemoteNames.joinToString()}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}
