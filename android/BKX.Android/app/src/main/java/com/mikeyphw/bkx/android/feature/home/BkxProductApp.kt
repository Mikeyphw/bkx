package com.mikeyphw.bkx.android.feature.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BkxProductApp(
    state: BkxHomeUiState,
    onEvent: (BkxHomeEvent) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = when (state.selectedDestination) {
                                BkxDestination.Home -> "BKX"
                                else -> state.selectedDestination.label
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                )
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        state.destinations.forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav-${destination.name.lowercase()}"),
                                selected = destination == state.selectedDestination,
                                onClick = { onEvent(BkxHomeEvent.SelectDestination(destination)) },
                                icon = { Text(destination.glyph()) },
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
                                onClick = { onEvent(BkxHomeEvent.SelectDestination(destination)) },
                                icon = { Text(destination.glyph()) },
                                label = { Text(destination.shortLabel) },
                            )
                        }
                    }
                    DestinationContent(state, onEvent, Modifier.weight(1f))
                }
            } else {
                DestinationContent(state, onEvent, Modifier.fillMaxSize().padding(innerPadding))
            }
        }
        FilesystemBrowserDialog(state.filesystemBrowser, onEvent)
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

private fun BkxDestination.glyph(): String = when (this) {
    BkxDestination.Home -> "⌂"
    BkxDestination.Backup -> "＋"
    BkxDestination.Archives -> "▣"
    BkxDestination.Activity -> "↻"
    BkxDestination.More -> "•••"
}
