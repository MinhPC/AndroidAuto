package com.minhphan.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo

/**
 * Every launchable app in one grid without headings, opened from the dock: first the apps on the dock, then the ones
 * launched most, then the rest by name (see [com.minhphan.launcher.data.smartOrder]). Tap launches (and returns Home);
 * long-press pins to / unpins from the dock or opens the app info. Apps already on the dock carry a blue dot.
 */
@Composable
fun AllAppsScreen(
    apps: List<AppInfo>,
    pinnedKeys: Set<String>,
    onLaunch: (AppInfo) -> Unit,
    onTogglePin: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onClose: () -> Unit,
) {
    val canPinMore = pinnedKeys.size < LauncherViewModel.MAX_FAVORITES

    OverlayScreen(title = stringResource(R.string.all_apps_title), onClose = onClose) {
        AppGrid(apps, pinnedKeys, canPinMore, onLaunch, onTogglePin, onAppInfo)
    }
}

@Composable
private fun ColumnScope.AppGrid(
    apps: List<AppInfo>,
    pinnedKeys: Set<String>,
    canPinMore: Boolean,
    onLaunch: (AppInfo) -> Unit,
    onTogglePin: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(apps, key = { it.key }) { app ->
            AppTile(
                app = app,
                isPinned = app.key in pinnedKeys,
                canPin = canPinMore,
                iconSize = 64.dp,
                onLaunch = { onLaunch(app) },
                onTogglePin = { onTogglePin(app) },
                onAppInfo = { onAppInfo(app) },
                container = MaterialTheme.colorScheme.surface,
                showPinnedBadge = true,
            )
        }
    }
}
