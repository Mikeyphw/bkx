package com.mikeyphw.bkx.android.feature.home

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.core.files.FilesystemBrowserEntry
import com.mikeyphw.bkx.android.core.files.FilesystemBrowserUiState
import com.mikeyphw.bkx.android.core.files.FilesystemEntryKind

@Composable
fun FilesystemBrowserDialog(
    state: FilesystemBrowserUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    if (!state.open) return
    val target = state.target ?: return
    var newFolderName by remember(state.currentPath) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onEvent(BkxHomeEvent.CloseFilesystemBrowser) },
        title = { Text(if (state.isRemote) "Choose a remote path" else "Choose a filesystem path") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.isRemote) "Current remote folder" else "Current folder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.currentPath, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onEvent(BkxHomeEvent.NavigateFilesystem(parentOf(state.currentPath))) },
                        enabled = canNavigateUp(state.currentPath),
                    ) { Text("Up") }
                    OutlinedButton(onClick = { onEvent(BkxHomeEvent.ToggleFilesystemHidden) }) {
                        Text(if (state.showHidden) "Hide dotfiles" else "Show dotfiles")
                    }
                }
                if (state.isRemote && target.folderOnly) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("New folder name") },
                            singleLine = true,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { onEvent(BkxHomeEvent.CreateFilesystemFolder(newFolderName)) },
                            enabled = newFolderName.trim().isNotEmpty() && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Create folder here") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.loading) Text("Loading folders…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.loading && state.error == null && state.entries.isEmpty()) {
                    Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.entries, key = { it.path }) { entry ->
                        FilesystemEntryRow(entry, state.folderOnly, onEvent)
                    }
                }
            }
        },
        confirmButton = {
            if (state.canUseCurrentFolder) {
                Button(
                    onClick = { onEvent(BkxHomeEvent.SelectFilesystemPath(state.currentPath, true)) },
                    enabled = !state.loading,
                ) {
                    Text("Use this folder")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseFilesystemBrowser) }) { Text("Cancel") }
        },
    )
}

@Composable
private fun FilesystemEntryRow(
    entry: FilesystemBrowserEntry,
    folderOnly: Boolean,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    val isDirectory = entry.kind == FilesystemEntryKind.Directory
    val canSelect = entry.selectable && (!folderOnly || isDirectory)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${entry.kind.name} ${entry.path}" }
            .clickable(enabled = isDirectory || canSelect) {
                if (isDirectory) onEvent(BkxHomeEvent.NavigateFilesystem(entry.path))
                else if (canSelect) onEvent(BkxHomeEvent.SelectFilesystemPath(entry.path, false))
            }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(entry.kind.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canSelect && !isDirectory) {
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.SelectFilesystemPath(entry.path, false)) }) { Text("Choose") }
        }
    }
}

private fun canNavigateUp(path: String): Boolean {
    if (path.startsWith("rclone://", ignoreCase = true)) {
        val uri = path.toUri()
        return !uri.path.isNullOrBlank() && uri.path != "/"
    }
    return path != "/"
}

private fun parentOf(path: String): String {
    if (path.startsWith("rclone://", ignoreCase = true)) {
        val uri = path.toUri()
        val host = uri.host ?: return path
        val current = uri.path.orEmpty().trimEnd('/')
        val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
        return Uri.Builder().scheme("rclone").authority(host).path(parent).build().toString()
    }
    val normalized = path.trimEnd('/')
    if (normalized.isBlank() || normalized == "/") return "/"
    return normalized.substringBeforeLast('/', "").ifBlank { "/" }
}
