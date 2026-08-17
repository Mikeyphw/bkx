package com.mikeyphw.bkx.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.app.ui.theme.BkxTheme
import com.mikeyphw.bkx.android.feature.home.BkxCompanionApp

class MainActivity : ComponentActivity() {
    private val viewModel: BkxAppViewModel by viewModels {
        BkxAppViewModelFactory(
            backendPort = DefaultBackendGraph.backendPort(this),
            runSummaryStore = DefaultBackendGraph.runSummaryStore(this),
            settingsStore = DefaultBackendGraph.settingsStore(this),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BkxTheme {
                BkxCompanionApp(viewModel = viewModel)
            }
        }
    }
}
