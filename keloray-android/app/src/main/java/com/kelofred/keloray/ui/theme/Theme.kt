package com.kelofred.keloray.ui.theme

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

// Fallback palette (pre-Android 12): deep reef blue with a warm sunrise accent.
private val Light = lightColorScheme(
    primary = Color(0xFF1F5FA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF00696E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF0F5),
    onSecondaryContainer = Color(0xFF002022),
    tertiary = Color(0xFF8C5000),
    tertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF00468A),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF80D4D9),
    onSecondary = Color(0xFF00373A),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = Color(0xFF9CF0F5),
    tertiary = Color(0xFFFFB870),
    tertiaryContainer = Color(0xFF6A3B00),
    background = Color(0xFF0F1318),
    surface = Color(0xFF0F1318),
    surfaceContainer = Color(0xFF1B2026),
    surfaceContainerHigh = Color(0xFF252A31),
)

@Composable
fun KeloTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
