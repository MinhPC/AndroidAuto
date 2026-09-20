package com.minhphan.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The car flips the system night mode with the headlights / time of day; we simply follow it.
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

@Composable
fun LauncherTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
