package com.minhphan.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.minhphan.launcher.data.LastLocationStore
import com.minhphan.launcher.data.ThemeMode
import com.minhphan.launcher.data.isDaytime
import java.time.ZoneId

private val DarkColors = darkColorScheme(
    background = Color(0xFF101418),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1C2228),
    onSurface = Color(0xFFE8EAED),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF14181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181C),
)

/** Whether the launcher is in its night look right now; the scene and the colours both follow it. */
val LocalDarkTheme = compositionLocalOf { false }

/**
 * Day or night for [mode]. [ThemeMode.Auto] is the sun at the car's last GPS position, re-evaluated every
 * minute; many head units never flip Android's own night mode, so following it alone would stay in daylight.
 */
@Composable
fun rememberDarkTheme(mode: ThemeMode): Boolean {
    val system = isSystemInDarkTheme()
    val context = LocalContext.current
    val now by rememberNow()
    val locations = remember(context) { LastLocationStore(context) }
    return when (mode) {
        ThemeMode.System -> system
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.Auto -> remember(now) { !isDaytime(now.atZone(ZoneId.systemDefault()), locations.current()) }
    }
}

@Composable
fun LauncherTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
