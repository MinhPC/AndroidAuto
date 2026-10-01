package com.minhphan.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.smartOrder

/** At most this many apps in the frequent row: one row of wide cards, each an easy target. */
private const val FREQUENT_COUNT = 4

private val TileShape = RoundedCornerShape(20.dp)
private val TileGap = 12.dp

/**
 * Every launchable app, opened from the dock: first the few launched most here ([usage]), as wide cards that are quick
 * to hit, then every app by name ([apps] must already be in name order). Tap launches (and returns Home); long-press
 * offers the app info. The back button at the top left, as big as the dock's buttons, closes it.
 */
@Composable
fun AllAppsScreen(
    apps: List<AppInfo>,
    usage: Map<String, Int>,
    onLaunch: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onClose: () -> Unit,
) {
    // The page fills the screen with the theme's own colour, so the status bar icons must contrast with that colour.
    StatusBarIcons(dark = !LocalDarkTheme.current)
    val frequent = remember(apps, usage) {
        smartOrder(apps, { it.key }, usage).takeWhile { (usage[it.key] ?: 0) > 0 }.take(FREQUENT_COUNT)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(count = apps.size, onClose = onClose)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(TileGap),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                if (frequent.isNotEmpty()) {
                    sectionTitle(R.string.all_apps_frequent, first = true)
                    item(key = "frequent", span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                            frequent.forEach { app ->
                                FrequentCard(app, { onLaunch(app) }, { onAppInfo(app) }, Modifier.weight(1f))
                            }
                            // Empty places keep the cards the same width however few there are.
                            repeat(FREQUENT_COUNT - frequent.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                sectionTitle(R.string.all_apps_by_name, first = frequent.isEmpty())
                items(apps, key = { it.key }) { app ->
                    AppCard(app, { onLaunch(app) }, { onAppInfo(app) })
                }
            }
        }
    }
}

/** The back button, the title and how many apps there are. */
@Composable
private fun Header(count: Int, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val close = stringResource(R.string.diagnostics_close)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.outlineVariant, CircleShape)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = close },
            contentAlignment = Alignment.Center,
        ) {
            BackArrow(colors.onSurface)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                text = stringResource(R.string.all_apps_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.onBackground,
            )
            Text(
                text = pluralStringResource(R.plurals.all_apps_count, count, count),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** An arrow pointing left, in lines of [color]. */
@Composable
private fun BackArrow(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2.4f * u
        drawLine(color, Offset(20f * u, 12f * u), Offset(4.5f * u, 12f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(11f * u, 5f * u), Offset(4f * u, 12f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(11f * u, 19f * u), Offset(4f * u, 12f * u), stroke, StrokeCap.Round)
    }
}

/** A section's name across the whole grid, with room above it unless it is the [first]. */
private fun LazyGridScope.sectionTitle(text: Int, first: Boolean) {
    item(key = "title-$text", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = if (first) 4.dp else 12.dp),
        )
    }
}

/** One of the apps launched most: a wide card, its icon beside its name, tinted in the accent. */
@Composable
private fun FrequentCard(app: AppInfo, onLaunch: () -> Unit, onAppInfo: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    PressableCard(
        onClick = onLaunch,
        onAppInfo = onAppInfo,
        background = lerp(colors.surface, colors.primary, 0.10f),
        edge = colors.primary.copy(alpha = 0.35f),
        modifier = modifier.height(96.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

/** One app in the list by name: its icon over its name, on a card. */
@Composable
private fun AppCard(app: AppInfo, onLaunch: () -> Unit, onAppInfo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PressableCard(
        onClick = onLaunch,
        onAppInfo = onAppInfo,
        background = colors.surface,
        edge = colors.outlineVariant,
        modifier = Modifier.fillMaxWidth().height(128.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(60.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * A card that dips slightly while pressed, so a tap is felt even when a slow head unit takes a moment to react.
 * Tap opens the app; long-press offers its app info.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PressableCard(
    onClick: () -> Unit,
    onAppInfo: () -> Unit,
    background: Color,
    edge: Color,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = 900f), label = "press")
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(TileShape)
                .background(background)
                .border(1.dp, edge, TileShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        ) {
            content()
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_info)) },
                onClick = { menuOpen = false; onAppInfo() },
            )
        }
    }
}
