package com.minhphan.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.minhphan.launcher.data.AppInfo

/**
 * The few apps the user pinned, in one row. Tap launches; long-press offers unpin and app info. The apps are
 * chosen in Settings, so Home itself never shows the full app list.
 */
@Composable
fun AppDock(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onUnpin: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        apps.forEach { app ->
            AppTile(
                app = app,
                isPinned = true,
                canPin = false,
                horizontal = false,
                onLaunch = { onLaunch(app) },
                onTogglePin = { onUnpin(app) },
                onAppInfo = { onAppInfo(app) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
