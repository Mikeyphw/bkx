package com.mikeyphw.bkx.android.feature.more

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.automation.AutomationAccessStore
import com.mikeyphw.bkx.android.automation.BkxAutomationContract

@Composable
fun AutomationScreen(onEvent: (BkxHomeEvent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { AutomationAccessStore(context) }
    var snapshot by remember { mutableStateOf(store.snapshot()) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseMoreRoute) }) { Text("Back to More") }
        BkxPageHeader("Automation", "Tasker/Locale plugin actions and a capability-protected external Intent API.")

        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("External automation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (snapshot.enabled) "Tasker and explicit intents can submit BKX operations with the current capability token." else "External automation is disabled.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BkxStatusPill(if (snapshot.enabled) "ENABLED" else "OFF")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { snapshot = store.setEnabled(!snapshot.enabled); message = if (snapshot.enabled) "Automation enabled." else "Automation disabled." }) {
                    Text(if (snapshot.enabled) "Disable" else "Enable")
                }
                OutlinedButton(onClick = { snapshot = store.regenerate(); message = "Capability token regenerated. Re-edit existing Tasker actions." }) {
                    Text("Regenerate token")
                }
            }
            OutlinedButton(
                enabled = snapshot.enabled && snapshot.token.isNotBlank(),
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("BKX automation token", snapshot.token))
                    message = "Capability token copied."
                },
            ) { Text("Copy capability token") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        BkxSectionHeader("Tasker")
        BkxSurfaceCard {
            Text("Add a Plugin action in Tasker and choose BKX Automation.", style = MaterialTheme.typography.titleMedium)
            Text("BKX supports backup, restore, archive verify/list/info/browse, transfer, cancellation, and status. Paths and request IDs may contain Tasker variables.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Long operations use Tasker's asynchronous completion Intent and return %bkx_* variables when Wait for final result is enabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        BkxSectionHeader("Explicit Intent API")
        BkxSurfaceCard {
            BkxKeyValue("Backup action", BkxAutomationContract.ACTION_BACKUP)
            BkxKeyValue("Restore action", BkxAutomationContract.ACTION_RESTORE)
            BkxKeyValue("Verify action", BkxAutomationContract.ACTION_VERIFY)
            BkxKeyValue("Transfer action", BkxAutomationContract.ACTION_TRANSFER)
            BkxKeyValue("Cancel action", BkxAutomationContract.ACTION_CANCEL)
            BkxKeyValue("Status action", BkxAutomationContract.ACTION_STATUS)
            Text("Every direct intent must include the copied capability token. Credentials for Google Drive or imported rclone remotes are never accepted through automation extras.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        BkxSectionHeader("Safety")
        BkxSurfaceCard {
            Text("Automation reuses BKX planning and safety gates", style = MaterialTheme.typography.titleMedium)
            Text("Backup and transfer always run their preview first. Restore always runs restore.preview and still requires exact RESTORE or RESTORE ROOT confirmation when the existing BKX safety engine asks for it. Google Drive tokens refresh privately before managed-Drive operations.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
