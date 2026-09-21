package com.minhphan.launcher.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.LastLocationStore
import com.minhphan.launcher.data.LauncherSettings
import com.minhphan.launcher.data.NavigatorChoice
import com.minhphan.launcher.data.ThemeMode
import com.minhphan.launcher.data.sunTimes
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Above this width the settings go in two columns: places and look on the left, the dock apps on the right. */
private val TwoColumnWidth = 840.dp

/**
 * Everything the user can change: which apps sit in the dock, the home and work addresses with the navigation
 * app, and the day / night look. Opened from the button on the scene; typing is meant for when the car is parked.
 */
@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    apps: List<AppInfo>,
    pinnedKeys: Set<String>,
    viewModel: LauncherViewModel,
    onClose: () -> Unit,
) {
    val canPinMore = pinnedKeys.size < LauncherViewModel.MAX_FAVORITES
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_close))
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val general: @Composable () -> Unit = { GeneralSettings(settings, viewModel) }
                val picker: LazyListContent = { dockPicker(apps, pinnedKeys, canPinMore, viewModel::toggleFavorite) }
                if (maxWidth >= TwoColumnWidth) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { general() }
                        LazyColumn(Modifier.weight(1f)) { picker() }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { general() }
                        picker()
                    }
                }
            }
        }
    }
}

private typealias LazyListContent = androidx.compose.foundation.lazy.LazyListScope.() -> Unit

private fun androidx.compose.foundation.lazy.LazyListScope.dockPicker(
    apps: List<AppInfo>,
    pinnedKeys: Set<String>,
    canPinMore: Boolean,
    onToggle: (AppInfo) -> Unit,
) {
    item {
        Text(
            text = stringResource(R.string.settings_dock_title, pinnedKeys.size, LauncherViewModel.MAX_FAVORITES),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
    }
    items(apps, key = { it.key }) { app ->
        val selected = app.key in pinnedKeys
        val enabled = selected || canPinMore
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { onToggle(app) }
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Text(app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Checkbox(checked = selected, onCheckedChange = null)
        }
    }
}

@Composable
private fun GeneralSettings(settings: LauncherSettings, viewModel: LauncherViewModel) {
    val context = LocalContext.current
    var home by rememberSaveable { mutableStateOf(settings.homeAddress) }
    var work by rememberSaveable { mutableStateOf(settings.workAddress) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(R.string.settings_places_title)
        OutlinedTextField(
            value = home,
            onValueChange = { home = it; viewModel.setHomeAddress(it) },
            label = { Text(stringResource(R.string.place_home_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = work,
            onValueChange = { work = it; viewModel.setWorkAddress(it) },
            label = { Text(stringResource(R.string.place_work_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(stringResource(R.string.place_hint))

        SectionTitle(R.string.navigator_title)
        RadioRow(settings.navigator == NavigatorChoice.Auto, stringResource(R.string.navigator_auto)) { viewModel.setNavigator(NavigatorChoice.Auto) }
        RadioRow(settings.navigator == NavigatorChoice.Waze, stringResource(R.string.navigator_waze)) { viewModel.setNavigator(NavigatorChoice.Waze) }
        RadioRow(settings.navigator == NavigatorChoice.GoogleMaps, stringResource(R.string.navigator_maps)) { viewModel.setNavigator(NavigatorChoice.GoogleMaps) }

        SectionTitle(R.string.settings_theme_title)
        RadioRow(settings.theme == ThemeMode.Auto, stringResource(R.string.theme_auto)) { viewModel.setTheme(ThemeMode.Auto) }
        if (settings.theme == ThemeMode.Auto) Hint(remember(settings.theme) { sunSummary(context) })
        RadioRow(settings.theme == ThemeMode.System, stringResource(R.string.theme_system)) { viewModel.setTheme(ThemeMode.System) }
        RadioRow(settings.theme == ThemeMode.Light, stringResource(R.string.theme_light)) { viewModel.setTheme(ThemeMode.Light) }
        RadioRow(settings.theme == ThemeMode.Dark, stringResource(R.string.theme_dark)) { viewModel.setTheme(ThemeMode.Dark) }
        Hint(stringResource(R.string.settings_park_hint))
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Today's sunrise and sunset at the car's last known position, so the user can check what "automatic" will do. */
private fun sunSummary(context: Context): String {
    val position = LastLocationStore(context).current() ?: return context.getString(R.string.sun_unknown)
    val zone = ZoneId.systemDefault()
    val times = sunTimes(LocalDate.now(zone), position, zone)
    val format = DateTimeFormatter.ofPattern("HH:mm")
    val rise = times.sunrise?.atZone(zone)?.format(format) ?: "--:--"
    val set = times.sunset?.atZone(zone)?.format(format) ?: "--:--"
    return context.getString(R.string.sun_times, rise, set)
}
