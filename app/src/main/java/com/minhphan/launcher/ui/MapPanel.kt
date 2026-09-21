package com.minhphan.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.Place

/**
 * The right half of Home: one-tap routes home and to work, and the map. The launcher cannot embed another
 * app's UI, so a map app is opened as a separate window placed exactly over this panel (see
 * LauncherViewModel.launchInBounds); while none covers it, the panel is where you open one.
 */
@Composable
fun MapPanel(
    mapApps: List<AppInfo>,
    onOpenMap: (AppInfo) -> Unit,
    onNavigate: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Centred when it fits; scrolls when something else (the default-Home banner) leaves too little height.
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                mapApps.forEach { Image(bitmap = it.icon, contentDescription = it.label, modifier = Modifier.size(44.dp)) }
                Text(
                    text = stringResource(R.string.map_panel_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (mapApps.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    NavButton(R.string.nav_home) { onNavigate(Place.Home) }
                    NavButton(R.string.nav_work) { onNavigate(Place.Work) }
                }
                Text(
                    text = stringResource(R.string.map_panel_hint),
                    style = MaterialTheme.typography.bodyMedium,
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
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavButton(label: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = 88.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
    }
}
