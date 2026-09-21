package com.minhphan.launcher.ui

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.minhphan.launcher.R
import com.minhphan.launcher.diagnostics.findActivity

/**
 * The frame of every full-screen page opened from Home (all apps, settings, diagnostics): a big title, a Close
 * button that is easy to hit, and the page's own content underneath.
 */
@Composable
fun OverlayScreen(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // These pages fill the screen with the theme's own colour, so the icons must contrast with that colour.
    StatusBarIcons(dark = !LocalDarkTheme.current)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_close), style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

private class StatusBarRequest(var dark: Boolean)

/** Every [StatusBarIcons] now in the composition, oldest first; the newest one decides. Touched on the main thread only. */
private val statusBarRequests = ArrayList<StatusBarRequest>()

private fun applyStatusBarIcons(view: View) {
    val dark = statusBarRequests.lastOrNull()?.dark ?: return
    val window = view.context.findActivity()?.window ?: return
    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = dark
}

/**
 * Asks for the status bar icons for as long as this is in the composition: [dark] icons for a light background,
 * light icons for a dark one. Android picks by the system's theme, which need not match the launcher's own.
 * Pages can overlap while one fades out and another fades in, so the newest request wins and, when it goes away,
 * the one below it is applied again, rather than each page restoring what it found.
 */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    val request = remember { StatusBarRequest(dark) }
    DisposableEffect(view) {
        statusBarRequests += request
        onDispose {
            statusBarRequests -= request
            applyStatusBarIcons(view)
        }
    }
    DisposableEffect(view, dark) {
        request.dark = dark
        applyStatusBarIcons(view)
        onDispose { }
    }
}
