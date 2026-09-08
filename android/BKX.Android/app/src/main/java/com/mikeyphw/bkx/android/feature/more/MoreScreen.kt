package com.mikeyphw.bkx.android.feature.more

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.MoreRoute
import com.mikeyphw.bkx.android.app.ui.components.BkxDivider
import com.mikeyphw.bkx.android.app.ui.components.BkxKeyValue
import com.mikeyphw.bkx.android.app.ui.components.BkxOutlinedSurface
import com.mikeyphw.bkx.android.app.ui.components.BkxPageHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSectionHeader
import com.mikeyphw.bkx.android.app.ui.components.BkxSettingSwitch
import com.mikeyphw.bkx.android.app.ui.components.BkxStatusPill
import com.mikeyphw.bkx.android.app.ui.components.BkxSurfaceCard
import com.mikeyphw.bkx.android.app.ui.theme.BkxTokens
import com.mikeyphw.bkx.android.core.settings.AppearancePreference
import com.mikeyphw.bkx.android.core.settings.LogPrivacyMode
import com.mikeyphw.bkx.android.core.settings.ProgressPreference
import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import com.mikeyphw.bkx.android.feature.transfer.TransferScreen
import com.mikeyphw.bkx.android.storage.AndroidStorageAccessManager

@Composable
fun MoreScreen(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.moreRoute != MoreRoute.Overview) { onEvent(BkxHomeEvent.CloseMoreRoute) }
    when (state.moreRoute) {
        MoreRoute.Remotes -> RcloneRemotesScreen(state, onEvent, modifier)
        MoreRoute.Transfer -> TransferScreen(state, onEvent, modifier)
        MoreRoute.Diagnostics -> DiagnosticsScreen(state, onEvent, modifier)
        MoreRoute.Overview -> MoreOverview(state, onEvent, modifier)
    }
}

@Composable
private fun MoreOverview(state: BkxHomeUiState, onEvent: (BkxHomeEvent) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val storageAccess = AndroidStorageAccessManager(context)
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        BkxPageHeader("More", "Storage, transfers, access, appearance, and troubleshooting.")

        BkxSectionHeader("Storage & transfers")
        BkxOutlinedSurface(onClick = { onEvent(BkxHomeEvent.OpenMoreRoute(MoreRoute.Remotes)) }) {
            Text("Cloud & rclone remotes", style = MaterialTheme.typography.titleMedium)
            Text("Import an existing rclone.conf and use its remotes for transfer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BkxOutlinedSurface(onClick = { onEvent(BkxHomeEvent.OpenMoreRoute(MoreRoute.Transfer)) }) {
            Text("Transfer files", style = MaterialTheme.typography.titleMedium)
            Text("Copy or synchronize local files with configured rclone remotes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Shared-storage access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (storageAccess.hasAllFilesAccess()) "BKX can access normal device storage directly." else "Grant access for internal, SD card, and USB backups.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BkxStatusPill(if (storageAccess.hasAllFilesAccess()) "GRANTED" else "NEEDED")
            }
            if (!storageAccess.hasAllFilesAccess()) Button(onClick = storageAccess::openGrantSettings) { Text("Grant file access") }
        }

        BkxSectionHeader("Access")
        BkxSurfaceCard {
            Text(if (state.rootAvailable) "Root is active" else "Running without root", style = MaterialTheme.typography.titleMedium)
            Text("Root unlocks Termux and app-private filesystem locations; normal shared-storage backups do not require it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RootAccessMode.entries.chunked(2).forEach { modes ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = state.settings.rootAccessMode == mode,
                                onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(rootAccessMode = mode))) },
                                label = { Text(mode.displayLabel()) },
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }) { Text("Refresh access status") }
        }
        BkxSurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Operation notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notificationsEnabled) "Backup, restore, and transfer progress can stay visible outside BKX." else "Enable notifications to see operation progress outside BKX.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BkxStatusPill(if (notificationsEnabled) "ON" else "OFF", if (notificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            }
            Text("Android 16 live progress is requested when the system supports promoted ongoing updates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Notification settings") }
        }

        BkxSectionHeader("Appearance")
        BkxSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppearancePreference.entries.chunked(2).forEach { appearances ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        appearances.forEach { appearance ->
                            FilterChip(
                                selected = state.settings.appearance == appearance,
                                onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(appearance = appearance))) },
                                label = { Text(appearance.name) },
                            )
                        }
                    }
                }
            }
            BkxSettingSwitch(
                title = "Use Android colors",
                summary = "Use wallpaper-derived colors on Android 12 and newer.",
                checked = state.settings.dynamicColor,
                onCheckedChange = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(dynamicColor = it))) },
            )
        }

        BkxSectionHeader("Privacy & progress")
        BkxSurfaceCard {
            Text("Activity details", style = MaterialTheme.typography.titleMedium)
            Text("Private hides paths and technical request details; Diagnostic includes troubleshooting information.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogPrivacyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.settings.logPrivacyMode == mode,
                        onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(logPrivacyMode = mode))) },
                        label = { Text(mode.displayLabel()) },
                    )
                }
            }
            BkxDivider()
            Text("Progress", style = MaterialTheme.typography.titleMedium)
            Text("Automatic uses the richest supported operation progress and falls back safely when needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProgressPreference.entries.chunked(2).forEach { preferences ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        preferences.forEach { preference ->
                            FilterChip(
                                selected = state.settings.progressPreference == preference,
                                onClick = { onEvent(BkxHomeEvent.UpdateSettings(state.settings.copy(progressPreference = preference))) },
                                label = { Text(preference.displayLabel()) },
                            )
                        }
                    }
                }
            }
        }

        BkxSectionHeader("Advanced")
        BkxOutlinedSurface(onClick = { onEvent(BkxHomeEvent.OpenMoreRoute(MoreRoute.Diagnostics)) }) {
            Text("Engine diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Runtime, protocol, capabilities, and setup remediation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
private fun DiagnosticsScreen(state: BkxHomeUiState, onEvent: (BkxHomeEvent) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BkxTokens.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BkxTokens.SectionGap),
    ) {
        OutlinedButton(onClick = { onEvent(BkxHomeEvent.CloseMoreRoute) }) { Text("Back to More") }
        BkxPageHeader("Engine diagnostics", "Technical runtime information for troubleshooting BKX.")
        BkxSurfaceCard {
            BkxKeyValue("Runtime", state.backendToolState.runtimeLabel)
            BkxKeyValue("BKX version", state.backendToolState.bkxVersion ?: "Not verified")
            BkxKeyValue("Protocol", state.backendToolState.protocolVersion.toString())
            BkxKeyValue("Root", if (state.rootAvailable) "Active" else "Inactive")
            BkxKeyValue("Capabilities", state.backendToolState.capabilities.size.toString())
            state.backendToolState.remediationActions.forEach { action ->
                BkxDivider()
                Text(action.title, style = MaterialTheme.typography.titleMedium)
                action.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = { onEvent(BkxHomeEvent.RefreshBackend) }, modifier = Modifier.fillMaxWidth()) { Text("Refresh diagnostics") }
        }
    }
}

private fun RootAccessMode.displayLabel(): String = when (this) {
    RootAccessMode.Preferred -> "Prefer root"
    RootAccessMode.Required -> "Require root"
    RootAccessMode.Disabled -> "No root"
}

private fun ProgressPreference.displayLabel(): String = when (this) {
    ProgressPreference.EventDriven -> "Automatic"
    ProgressPreference.Polling -> "Compatible"
    ProgressPreference.Compact -> "Reduced"
}

private fun LogPrivacyMode.displayLabel(): String = when (this) {
    LogPrivacyMode.Private -> "Private"
    LogPrivacyMode.Diagnostic -> "Diagnostic"
}
