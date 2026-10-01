package com.minhphan.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.DockEntry
import com.minhphan.launcher.data.DockShortcut
import com.minhphan.launcher.data.MAX_DOCK_APPS
import com.minhphan.launcher.data.resolveDock

/**
 * The dock's apps, for the settings card: each on a row of its own with buttons to move it towards the start or the
 * end of the dock (up and down here) and to take it off, then the way to add one ([onAdd]) while there is room.
 */
@Composable
internal fun ColumnScope.DockSettings(dockApps: List<String>, apps: List<AppInfo>, viewModel: LauncherViewModel, onAdd: () -> Unit) {
    val entries = remember(dockApps, apps) { resolveDock(dockApps, apps) }
    Hint(stringResource(R.string.settings_dock_hint, MAX_DOCK_APPS))
    if (entries.isEmpty()) Hint(stringResource(R.string.settings_dock_empty))
    entries.forEachIndexed { index, entry ->
        DockEntryRow(
            entry,
            onUp = if (index > 0) ({ viewModel.moveDockApp(entry.id, -1) }) else null,
            onDown = if (index < entries.lastIndex) ({ viewModel.moveDockApp(entry.id, 1) }) else null,
            onRemove = { viewModel.setDockApp(entry.id, false) },
        )
    }
    if (entries.size >= MAX_DOCK_APPS) {
        Hint(stringResource(R.string.settings_dock_full, MAX_DOCK_APPS))
    } else {
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.settings_dock_add), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** One of the dock's apps: its picture and name, and its buttons; a move it cannot make has no button ([onUp], [onDown]). */
@Composable
private fun DockEntryRow(entry: DockEntry, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DockEntryIcon(entry, size = 40.dp)
        Text(
            text = dockEntryLabel(entry),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        RowButton("↑", stringResource(R.string.settings_dock_move_up), onUp)
        RowButton("↓", stringResource(R.string.settings_dock_move_down), onDown)
        RowButton("✕", stringResource(R.string.settings_dock_remove), onRemove)
    }
}

/** A square button with a sign on it, its meaning read out; greyed out without [onClick]. */
@Composable
private fun RowButton(sign: String, description: String, onClick: (() -> Unit)?) {
    FilledTonalButton(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(56.dp).semantics { contentDescription = description },
    ) {
        Text(sign, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Picks what to put on the dock: the shortcuts, then every installed app by name, leaving out what is already on it.
 * A tap adds it at the end of the dock and comes back to Settings.
 */
@Composable
internal fun DockAppPicker(dockApps: List<String>, apps: List<AppInfo>, viewModel: LauncherViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val shortcuts = remember(dockApps) { DockShortcut.entries.filter { it.id !in dockApps }.map { DockEntry.Shortcut(it) } }
    val others = remember(dockApps, apps) { apps.filter { it.key !in dockApps }.map { DockEntry.App(it) } }
    fun pick(entry: DockEntry) {
        if (!viewModel.setDockApp(entry.id, true)) sayDockFull(context)
        onClose()
    }
    OverlayScreen(title = stringResource(R.string.settings_dock_pick_title), onClose = onClose) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shortcuts.isNotEmpty()) {
                item(key = "title-shortcuts", span = { GridItemSpan(maxLineSpan) }) { PickerTitle(stringResource(R.string.settings_dock_pick_shortcuts)) }
                items(shortcuts, key = { it.id }) { PickerCard(it) { pick(it) } }
            }
            item(key = "title-apps", span = { GridItemSpan(maxLineSpan) }) { PickerTitle(stringResource(R.string.settings_dock_pick_apps)) }
            items(others, key = { it.id }) { PickerCard(it) { pick(it) } }
        }
    }
}

@Composable
private fun PickerTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** Something to put on the dock: its picture over its name, on a card. */
@Composable
private fun PickerCard(entry: DockEntry, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DockEntryIcon(entry, size = 56.dp)
        Text(
            text = dockEntryLabel(entry),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
