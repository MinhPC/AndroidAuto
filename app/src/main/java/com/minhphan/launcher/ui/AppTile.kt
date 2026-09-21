package com.minhphan.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo

/** Minimum touch target recommended for in-car UI. */
private val MinTouchTarget = 88.dp

/** Below this icon size the label switches to a smaller style so it still fits under the icon. */
private val CompactIcon = 56.dp

/**
 * An icon with its label under it, the shape shared by apps and the dock's shortcut buttons. It dips slightly while
 * pressed, so a tap is felt even when a slow head unit takes a moment to react. [container] is an optional card
 * colour behind the whole tile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TileLayout(
    label: String,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    container: Color = Color.Transparent,
    icon: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = 900f), label = "press")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center, content = icon)
        Text(
            text = label,
            style = if (iconSize >= CompactIcon) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * One app. Tap launches; long-press opens pin / app-info. [showPinnedBadge] marks apps that are on the dock,
 * for the full list.
 */
@Composable
fun AppTile(
    app: AppInfo,
    isPinned: Boolean,
    canPin: Boolean,
    iconSize: Dp,
    onLaunch: () -> Unit,
    onTogglePin: () -> Unit,
    onAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Transparent,
    showPinnedBadge: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(modifier) {
        TileLayout(
            label = app.label,
            iconSize = iconSize,
            onClick = onLaunch,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menuOpen = true
            },
            container = container,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(iconSize))
            if (showPinnedBadge && isPinned) PinBadge(Modifier.align(Alignment.TopEnd))
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (isPinned || canPin) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.unpin_app else R.string.pin_app)) },
                    onClick = { menuOpen = false; onTogglePin() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_info)) },
                onClick = { menuOpen = false; onAppInfo() },
            )
        }
    }
}

/** A small accent dot on the corner of an icon: this app is on the dock. */
@Composable
private fun PinBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}
