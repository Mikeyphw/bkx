package com.mikeyphw.bkx.android.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mikeyphw.bkx.android.R
import com.mikeyphw.bkx.android.app.BkxAppViewModel
import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.ui.theme.BkxTheme
import com.mikeyphw.bkx.android.feature.activity.ActivityScreen
import com.mikeyphw.bkx.android.feature.archives.ArchivesScreen
import com.mikeyphw.bkx.android.feature.backup.BackupScreen
import com.mikeyphw.bkx.android.feature.more.MoreScreen

@Composable
fun BkxAndroidApp(viewModel: BkxAppViewModel) {
    val state by viewModel.state.collectAsState()
    BkxTheme(
        appearance = state.settings.appearance,
        dynamicColor = state.settings.dynamicColor,
    ) {
        BkxProductApp(state = state, onEvent = viewModel::onEvent)
    }
}

@Composable
fun BkxProductApp(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingNotificationEvent by remember { mutableStateOf<BkxHomeEvent?>(null) }
    var notificationPrompted by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        notificationPrompted = true
        pendingNotificationEvent?.let(onEvent)
        pendingNotificationEvent = null
    }
    val googleDriveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onEvent(BkxHomeEvent.CompleteGoogleDriveAuthorization(result.data))
    }

    fun dispatch(event: BkxHomeEvent) {
        val needsNotificationPermission = event.requiresOperationNotificationPermission()
        val canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission && canRequest && !notificationPrompted) {
            pendingNotificationEvent = event
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onEvent(event)
        }
    }

    state.googleDrive.pendingConsent?.let { pendingIntent ->
        LaunchedEffect(pendingIntent) {
            googleDriveConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            onEvent(BkxHomeEvent.GoogleDriveConsentLaunched)
        }
    }

    state.uiMessage?.let { message ->
        LaunchedEffect(message.id) {
            snackbarHostState.showSnackbar(message.text)
            onEvent(BkxHomeEvent.ConsumeUiMessage(message.id))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        state.destinations.forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav-${destination.name.lowercase()}"),
                                selected = destination == state.selectedDestination,
                                onClick = { dispatch(BkxHomeEvent.SelectDestination(destination)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(destination.iconRes()),
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.shortLabel) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            if (wide) {
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    NavigationRail(modifier = Modifier.padding(horizontal = 6.dp)) {
                        state.destinations.forEach { destination ->
                            NavigationRailItem(
                                modifier = Modifier.testTag("nav-${destination.name.lowercase()}"),
                                selected = destination == state.selectedDestination,
                                onClick = { dispatch(BkxHomeEvent.SelectDestination(destination)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(destination.iconRes()),
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.shortLabel) },
                            )
                        }
                    }
                    DestinationContent(state, ::dispatch, Modifier.weight(1f))
                }
            } else {
                DestinationContent(state, ::dispatch, Modifier.fillMaxSize().padding(innerPadding))
            }
        }
        FilesystemBrowserDialog(state.filesystemBrowser, ::dispatch)
    }
}

@Composable
private fun DestinationContent(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
    modifier: Modifier,
) {
    when (state.selectedDestination) {
        BkxDestination.Home -> HomeScreen(state, onEvent, modifier)
        BkxDestination.Backup -> BackupScreen(state, onEvent, modifier)
        BkxDestination.Archives -> ArchivesScreen(state, onEvent, modifier)
        BkxDestination.Activity -> ActivityScreen(state, onEvent, modifier)
        BkxDestination.More -> MoreScreen(state, onEvent, modifier)
    }
}

private fun BkxHomeEvent.requiresOperationNotificationPermission(): Boolean = when (this) {
    BkxHomeEvent.CreateBackup,
    BkxHomeEvent.ExecuteRestore,
    BkxHomeEvent.RunTransfer,
    is BkxHomeEvent.RunSavedJob,
    is BkxHomeEvent.InitializeRepository,
    is BkxHomeEvent.CheckRepository,
    is BkxHomeEvent.PruneRepository,
    is BkxHomeEvent.ApplyRetention,
    BkxHomeEvent.ExecuteSnapshotRestore,
    is BkxHomeEvent.VerifyArchive -> true
    else -> false
}

@DrawableRes
private fun BkxDestination.iconRes(): Int = when (this) {
    BkxDestination.Home -> R.drawable.ic_nav_home
    BkxDestination.Backup -> R.drawable.ic_nav_backup
    BkxDestination.Archives -> R.drawable.ic_nav_archives
    BkxDestination.Activity -> R.drawable.ic_nav_activity
    BkxDestination.More -> R.drawable.ic_nav_more
}
