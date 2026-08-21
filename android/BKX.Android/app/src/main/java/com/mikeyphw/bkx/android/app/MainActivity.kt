package com.mikeyphw.bkx.android.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.app.service.BkxOperationForegroundService
import com.mikeyphw.bkx.android.feature.home.BkxAndroidApp

class MainActivity : ComponentActivity() {
    private val viewModel: BkxAppViewModel by viewModels {
        BkxAppViewModelFactory(
            backendPort = DefaultBackendGraph.backendPort(this),
            operationCoordinator = DefaultBackendGraph.operationCoordinator(this),
            runSummaryStore = DefaultBackendGraph.runSummaryStore(this),
            operationStateStore = DefaultBackendGraph.operationStateStore(this),
            settingsStore = DefaultBackendGraph.settingsStore(this),
            providerStagingPort = DefaultBackendGraph.providerStaging(this),
            archiveRootsProvider = { rooted -> DefaultBackendGraph.archiveSearchRoots(this, rooted) },
            requestBaseDirectoryPath = filesDir.resolve("bkx/requests").absolutePath,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleReconnectIntent(intent)
        setContent {
            BkxAndroidApp(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReconnectIntent(intent)
    }

    private fun handleReconnectIntent(intent: Intent?) {
        BkxOperationForegroundService.reconnectRequestId(intent)?.let { requestId ->
            viewModel.onEvent(BkxHomeEvent.FocusActiveOperation(requestId))
        }
    }
}
