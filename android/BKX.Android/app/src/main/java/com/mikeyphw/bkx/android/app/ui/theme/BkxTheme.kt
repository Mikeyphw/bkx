package com.mikeyphw.bkx.android.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val BkxLightColorScheme = lightColorScheme()

@Composable
fun BkxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BkxLightColorScheme,
        content = content,
    )
}
