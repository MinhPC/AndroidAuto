package com.minhphan.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo

/**
 * The few apps the user pinned in one card, followed by the "all apps" and "settings" buttons. Tap launches;
 * long-press offers unpin and app info. The icons shrink to fit when there are many, so the row never overflows.
 */
@Composable
fun AppDock(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onUnpin: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        val iconSize = ((maxWidth - 16.dp) / (apps.size + 2) - 24.dp).coerceIn(40.dp, 64.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            apps.forEach { app ->
                AppTile(
                    app = app,
                    isPinned = true,
                    canPin = false,
                    iconSize = iconSize,
                    onLaunch = { onLaunch(app) },
                    onTogglePin = { onUnpin(app) },
                    onAppInfo = { onAppInfo(app) },
                    modifier = Modifier.weight(1f),
                )
            }
            ShortcutTile(stringResource(R.string.all_apps_button), iconSize, onOpenAllApps, Modifier.weight(1f)) { AllAppsIcon(it) }
            ShortcutTile(stringResource(R.string.settings_button), iconSize, onOpenSettings, Modifier.weight(1f)) { GearIcon(it) }
        }
    }
}

/** A dock button that looks like an app: a round tile holding a drawn icon. */
@Composable
private fun ShortcutTile(
    label: String,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable (Color) -> Unit,
) {
    TileLayout(label = label, iconSize = iconSize, onClick = onClick, modifier = modifier) {
        Box(
            Modifier
                .size(iconSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            icon(MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Nine dots in a square: the usual sign for "all apps". */
@Composable
private fun AllAppsIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val step = size.width / 3f
        val radius = step * 0.26f
        for (row in 0..2) for (col in 0..2) {
            drawCircle(color, radius, Offset(step * (col + 0.5f), step * (row + 0.5f)))
        }
    }
}

/** A cog: a ring with eight teeth. */
@Composable
private fun GearIcon(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val r = size.minDimension / 2f
        drawCircle(color, radius = r * 0.5f, style = Stroke(width = r * 0.3f))
        repeat(8) { i ->
            rotate(i * 45f, pivot = center) {
                drawRoundRect(
                    color,
                    topLeft = Offset(center.x - r * 0.14f, center.y - r),
                    size = Size(r * 0.28f, r * 0.38f),
                    cornerRadius = CornerRadius(r * 0.07f),
                )
            }
        }
    }
}
