package com.minhphan.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import java.text.Normalizer

/**
 * Every launchable app in a grid under letter headings, opened from the dock. Tap launches (and returns Home);
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
    val sections = remember(apps) { apps.groupBy { sectionOf(it.label) } }

    OverlayScreen(title = stringResource(R.string.all_apps_title), onClose = onClose) {
        AppGrid(sections, pinnedKeys, canPinMore, onLaunch, onTogglePin, onAppInfo)
    }
}

@Composable
private fun ColumnScope.AppGrid(
    sections: Map<String, List<AppInfo>>,
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
        sections.forEach { (letter, group) ->
            item(key = "section_$letter", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                )
            }
            items(group, key = { it.key }) { app ->
                val isPinned = app.key in pinnedKeys
                AppTile(
                    app = app,
                    isPinned = isPinned,
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
}

/** The heading an app is listed under: its first letter, without accents (Đ counts as D), or # for anything else. */
internal fun sectionOf(label: String): String {
    val first = label.trim().firstOrNull() ?: return "#"
    val plain = if (first == 'Đ' || first == 'đ') 'D' else Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    return if (plain.isLetter()) plain.uppercaseChar().toString() else "#"
}
