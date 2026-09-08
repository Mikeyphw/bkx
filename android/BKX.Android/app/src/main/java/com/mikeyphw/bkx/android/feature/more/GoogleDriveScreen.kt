package com.mikeyphw.bkx.android.feature.more

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.drive.GoogleDriveConnectionStatus
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig

@Composable
fun GoogleDriveScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drive = state.googleDrive
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseMoreRoute) }) { Text("Back to More") }
        BkxPageHeader("Google Drive", "First-class BKX cloud backup and restore using a narrowly scoped Google authorization.")

        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleLarge)
                    Text(
                        drive.message ?: when (drive.status) {
                            GoogleDriveConnectionStatus.Disconnected -> "Google Drive is not connected."
                            GoogleDriveConnectionStatus.Connecting -> "Checking Google Drive authorization…"
                            GoogleDriveConnectionStatus.NeedsConsent -> "Google Drive needs your approval."
                            GoogleDriveConnectionStatus.Connected -> "Google Drive is ready for BKX."
                            GoogleDriveConnectionStatus.Disconnecting -> "Revoking Google Drive access…"
                            GoogleDriveConnectionStatus.Error -> "Google Drive authorization needs attention."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BkxStatusPill(
                    when (drive.status) {
                        GoogleDriveConnectionStatus.Connected -> "CONNECTED"
                        GoogleDriveConnectionStatus.Connecting, GoogleDriveConnectionStatus.Disconnecting -> "WORKING"
                        GoogleDriveConnectionStatus.NeedsConsent -> "APPROVAL"
                        GoogleDriveConnectionStatus.Error -> "ERROR"
                        GoogleDriveConnectionStatus.Disconnected -> "OFF"
                    },
                    when (drive.status) {
                        GoogleDriveConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
                        GoogleDriveConnectionStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            if (!drive.connected) {
                Button(onClick = { onEvent(BkxHomeEvent.ConnectGoogleDrive) }, enabled = !drive.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (drive.status == GoogleDriveConnectionStatus.NeedsConsent) "Continue Google authorization" else "Connect Google Drive")
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshGoogleDrive) }, enabled = !drive.busy, modifier = Modifier.weight(1f)) { Text("Refresh access") }
                    OutlinedButton(onClick = { onEvent(BkxHomeEvent.DisconnectGoogleDrive) }, enabled = !drive.busy, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                }
            }
        }

        BkxSectionHeader("Privacy boundary")
        BkxSurfaceCard {
            Text("Drive file scope", style = MaterialTheme.typography.titleMedium)
            Text(
                "BKX requests only Google Drive's drive.file scope. This limits BKX to files the app creates or that the user explicitly authorizes; BKX does not request full Drive access.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BkxKeyValue("Cloud folder", "BKX")
            BkxKeyValue("Remote", GoogleDriveRcloneConfig.rootUri)
            Text("The access token is stored only inside BKX's app-private rclone configuration and is never shown in activity history or diagnostics.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (drive.connected) {
            BkxSectionHeader("Use Google Drive")
            BkxSurfaceCard {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text("Create verified .bakery archives directly in the BKX folder on Drive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        val draft = state.backupPlanning.draft
                        onEvent(
                            BkxHomeEvent.UpdateBackupDraft(
                                draft.copy(
                                    destination = BackupDestinationSelection(
                                        destinationId = "google-drive-bkx",
                                        label = "Google Drive · BKX",
                                        stagedDirectoryPath = GoogleDriveRcloneConfig.rootUri,
                                    ),
                                ),
                            ),
                        )
                        onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Backup))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back up to Google Drive") }
            }
            BkxSurfaceCard {
                Text("Archives & restore", style = MaterialTheme.typography.titleMedium)
                Text("Drive archives join the normal BKX archive library. Open, inspect, verify, and restore them through the same safety workflow as local archives.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        onEvent(BkxHomeEvent.SelectDestination(BkxDestination.Archives))
                        onEvent(BkxHomeEvent.RefreshArchives)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Drive archives") }
            }
        }
    }
}
