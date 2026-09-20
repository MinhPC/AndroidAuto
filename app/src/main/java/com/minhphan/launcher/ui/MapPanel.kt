package com.minhphan.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo

/**
 * Reserves the right half of Home for the map. The launcher cannot embed another app's UI, so a map app
 * is opened as a separate window placed exactly over this panel (see LauncherViewModel.launchInBounds).
 * While no map window covers it, the panel is where you open one.
 */
@Composable
fun MapPanel(
    mapApps: List<AppInfo>,
    onOpenMap: (AppInfo) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mapApps.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                mapApps.forEach { Image(bitmap = it.icon, contentDescription = it.label, modifier = Modifier.size(88.dp)) }
            }
            Text(
                text = stringResource(R.string.map_panel_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.map_panel_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            mapApps.forEach { app ->
                Button(
                    onClick = { onOpenMap(app) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Text(stringResource(R.string.open_map_half, app.label), style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.map_not_installed),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.diagnostics_button))
        }
    }
}
