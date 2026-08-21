package com.mikeyphw.bkx.android.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mikeyphw.bkx.android.core.settings.AppearancePreference

private val BkxLightColorScheme = lightColorScheme(
    primary = Color(0xFF245D58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F2EA),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8E3),
    onSecondaryContainer = Color(0xFF06201D),
    tertiary = Color(0xFF456179),
    tertiaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFFF5FBF9),
    surface = Color(0xFFF5FBF9),
    surfaceVariant = Color(0xFFDAE5E2),
    outline = Color(0xFF6F7977),
    error = Color(0xFFBA1A1A),
)

private val BkxDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8CD5CE),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF07504B),
    onPrimaryContainer = Color(0xFFA8F2EA),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B48),
    onSecondaryContainer = Color(0xFFCDE8E3),
    tertiary = Color(0xFFADCBE6),
    tertiaryContainer = Color(0xFF2E4960),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF899390),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BkxTheme(
    appearance: AppearancePreference = AppearancePreference.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (appearance) {
        AppearancePreference.System -> isSystemInDarkTheme()
        AppearancePreference.Light -> false
        AppearancePreference.Dark -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BkxDarkColorScheme
        else -> BkxLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BkxTypography,
        content = content,
    )
}
