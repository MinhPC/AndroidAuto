package com.minhphan.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo

/** Minimum touch target recommended for in-car UI. */
private val MinTouchTarget = 96.dp

/**
 * One app. [horizontal] = icon beside the label on a filled card (pinned list);
 * otherwise icon above the label (main grid). Tap launches; long-press opens pin / app-info.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    app: AppInfo,
    isPinned: Boolean,
    canPin: Boolean,
    horizontal: Boolean,
    onLaunch: () -> Unit,
    onTogglePin: () -> Unit,
    onAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)

    Box(modifier) {
        val clickable = Modifier
            .clip(shape)
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )

        if (horizontal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .then(clickable)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(20.dp))
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .then(clickable)
                    .padding(12.dp),
            ) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(72.dp))
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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
