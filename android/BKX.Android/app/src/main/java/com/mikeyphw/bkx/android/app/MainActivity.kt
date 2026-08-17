package com.mikeyphw.bkx.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.feature.home.BkxCompanionApp
import com.mikeyphw.bkx.android.app.ui.theme.BkxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: BkxAppViewModel by viewModels {
        BkxAppViewModelFactory(DefaultBackendGraph.backendPort(this))
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
