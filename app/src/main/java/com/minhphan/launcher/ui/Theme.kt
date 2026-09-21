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

// Three layers of grey-blue: the page (background), the cards on it (surface) and the tiles on the cards
// (surfaceVariant), with one blue accent. Day and night share the hue so the switch feels like the same car.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5AA9FF),
    onPrimary = Color(0xFF002B55),
    primaryContainer = Color(0xFF163A66),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondaryContainer = Color(0xFF243447),
    onSecondaryContainer = Color(0xFFDCE6F2),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF161C24),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF212A35),
    onSurfaceVariant = Color(0xFFA9B4C2),
    outline = Color(0xFF3A4656),
    outlineVariant = Color(0xFF2A3441),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1663D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondaryContainer = Color(0xFFE1E8F2),
    onSecondaryContainer = Color(0xFF14181C),
    background = Color(0xFFEEF2F7),
    onBackground = Color(0xFF14181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFF515C6B),
    outline = Color(0xFFB7C1CE),
    outlineVariant = Color(0xFFDCE3EC),
    error = Color(0xFFD93025),
)

/** Status colours that read on both the light and the dark cards. */
val StatusGood = Color(0xFF2FBF71)
val StatusWarn = Color(0xFFF5A300)

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
