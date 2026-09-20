package com.minhphan.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo

/**
 * Reserves the right half of Home for the map. The launcher cannot embed another app's UI, so Waze is
 * opened as a separate window placed exactly over this panel (see LauncherViewModel.launchInBounds).
 * While Waze is not showing over it, the panel is a button to open it.
 */
@Composable
fun MapPanel(
    waze: AppInfo?,
    onOpenWaze: (AppInfo) -> Unit,
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
        if (waze != null) {
            Image(bitmap = waze.icon, contentDescription = null, modifier = Modifier.size(96.dp))
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
            Button(
                onClick = { onOpenWaze(waze) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text(stringResource(R.string.open_waze_half), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text(
                text = stringResource(R.string.waze_not_installed),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
