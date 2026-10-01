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

// Three layers of blue-grey: the page (background), the cards on it (surface) and the tiles on the cards
// (surfaceVariant), with one blue accent. By night the slate navy of the mock-up (measured on it), as an electric car's
// screen at dusk: white type, the names and units in a pale blue-grey, a sky-blue accent. Day and night share the hue
// so the switch feels like the same car.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF79C6F7),
    onPrimary = Color(0xFF002238),
    primaryContainer = Color(0xFF2B4765),
    onPrimaryContainer = Color(0xFFDDEEFF),
    secondaryContainer = Color(0xFF203348),
    onSecondaryContainer = Color(0xFFDCE8F5),
    background = Color(0xFF101B27),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF172738),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A2A3C),
    onSurfaceVariant = Color(0xFFCCDCEE),
    outline = Color(0xFF2E4259),
    outlineVariant = Color(0xFF283D54),
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
 * [ThemeMode.Headlights] is [headlightsOn]: the screen dimmed as the head unit dims it with the lights on.
 */
@Composable
fun rememberDarkTheme(mode: ThemeMode, headlightsOn: Boolean): Boolean {
    val system = isSystemInDarkTheme()
    val context = LocalContext.current
    val now by rememberNow()
    val locations = remember(context) { LastLocationStore(context) }
    return when (mode) {
        ThemeMode.Headlights -> headlightsOn
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
