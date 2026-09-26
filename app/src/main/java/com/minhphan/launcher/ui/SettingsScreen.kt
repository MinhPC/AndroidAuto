package com.minhphan.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.BuildConfig
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.LastLocationStore
import com.minhphan.launcher.data.LauncherSettings
import com.minhphan.launcher.data.ThemeMode
import com.minhphan.launcher.data.sunTimes
import com.minhphan.launcher.diagnostics.findActivity
import com.minhphan.launcher.obd.AUTO_ADDRESS
import com.minhphan.launcher.obd.MAX_OBD_FIELDS
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.ObdGroup
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.SIMULATED_ADDRESS
import com.minhphan.launcher.obd.VoltageCalibration
import com.minhphan.launcher.obd.availableFields
import com.minhphan.launcher.obd.bondedDevices
import com.minhphan.cloud.AccountState
import com.minhphan.cloud.SignInException
import com.minhphan.launcher.sync.SyncState
import com.minhphan.launcher.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Above this width the settings go in two columns: the groups on the left, the dock apps on the right. */
private val TwoColumnWidth = 840.dp

/**
 * Everything the user can change, in cards: the day / night look, the paired OBD adapter, the app version with the
 * update check and window diagnostics, and which apps sit in the dock. Opened from the dock's settings button.
 */
@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    apps: List<AppInfo>,
    pinnedKeys: Set<String>,
    viewModel: LauncherViewModel,
    updateState: UpdateState,
    bluetooth: BluetoothPermission,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,
) {
    val canPinMore = pinnedKeys.size < LauncherViewModel.MAX_FAVORITES
    OverlayScreen(title = stringResource(R.string.settings_title), onClose = onClose) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val general: @Composable () -> Unit = { GeneralSettings(settings, viewModel, updateState, bluetooth, onOpenDiagnostics) }
            if (maxWidth >= TwoColumnWidth) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { general() }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dockPicker(apps, pinnedKeys, canPinMore, viewModel::toggleFavorite)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { general() }
                    dockPicker(apps, pinnedKeys, canPinMore, viewModel::toggleFavorite)
                }
            }
        }
    }
}

private fun LazyListScope.dockPicker(
    apps: List<AppInfo>,
    pinnedKeys: Set<String>,
    canPinMore: Boolean,
    onToggle: (AppInfo) -> Unit,
) {
    item {
        Text(
            text = stringResource(R.string.settings_dock_title, pinnedKeys.size, LauncherViewModel.MAX_FAVORITES),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp, start = 4.dp),
        )
    }
    items(apps, key = { it.key }) { app ->
        val selected = app.key in pinnedKeys
        val enabled = selected || canPinMore
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(enabled = enabled) { onToggle(app) }
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Text(app.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Checkbox(checked = selected, onCheckedChange = null)
        }
    }
}

@Composable
private fun GeneralSettings(
    settings: LauncherSettings,
    viewModel: LauncherViewModel,
    updateState: UpdateState,
    bluetooth: BluetoothPermission,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard(R.string.settings_theme_title) {
            RadioRow(settings.theme == ThemeMode.Auto, stringResource(R.string.theme_auto)) { viewModel.setTheme(ThemeMode.Auto) }
            if (settings.theme == ThemeMode.Auto) Hint(remember(settings.theme) { sunSummary(context) })
            RadioRow(settings.theme == ThemeMode.System, stringResource(R.string.theme_system)) { viewModel.setTheme(ThemeMode.System) }
            RadioRow(settings.theme == ThemeMode.Light, stringResource(R.string.theme_light)) { viewModel.setTheme(ThemeMode.Light) }
            RadioRow(settings.theme == ThemeMode.Dark, stringResource(R.string.theme_dark)) { viewModel.setTheme(ThemeMode.Dark) }
            Hint(stringResource(R.string.settings_park_hint))
        }

        SettingsCard(R.string.settings_obd_title) {
            ObdDeviceSettings(settings.obdAddress, bluetooth, viewModel::setObdAddress)
        }

        SettingsCard(R.string.settings_obd_fields_title) {
            ObdFieldSettings(settings.shownObdFields, settings.carPids, viewModel)
        }

        SettingsCard(R.string.settings_voltage_title) {
            VoltageCalibrationSettings(settings.voltageCalibration, viewModel)
        }

        SettingsCard(R.string.sync_title) {
            TripSyncSettings(settings, viewModel)
        }

        SettingsCard(R.string.settings_fuel_title) {
            TankSize(settings.tankLiters, viewModel::setTankLiters)
        }

        SettingsCard(R.string.settings_about_title) {
            UpdateBar(
                versionName = viewModel.versionName,
                state = updateState,
                onCheck = viewModel::checkForUpdate,
                onInstall = viewModel::installUpdate,
            )
            ActionButton(stringResource(R.string.diagnostics_button), onOpenDiagnostics)
        }
    }
}

/** A titled group of settings on a rounded card. */
@Composable
private fun SettingsCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** A wide button for an action inside a card. */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** A choice; the picked one is shaded so the current setting reads at a glance. */
@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The size of the fuel tank, which the estimate of the fuel left counts down from. */
@Composable
private fun TankSize(liters: Int, onChange: (Int) -> Unit) {
    StepperRow(stringResource(R.string.settings_tank_label, liters), { onChange(liters - 1) }, { onChange(liters + 1) })
    Hint(stringResource(R.string.settings_tank_hint))
}

/** A value with a minus and a plus button. */
@Composable
private fun StepperRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onMinus, modifier = Modifier.heightIn(min = 56.dp)) {
            Text("-", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onPlus, modifier = Modifier.heightIn(min = 56.dp)) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * The two readings that correct the adapter's battery voltage: for engine off and running, what the adapter said
 * and what a multimeter read at the battery at the same time. The adapter's side can be taken from what it says now.
 */
@Composable
private fun VoltageCalibrationSettings(calibration: VoltageCalibration, viewModel: LauncherViewModel) {
    val state by viewModel.obd.collectAsStateWithLifecycle()
    val adapterNow = (state as? ObdState.Connected)?.values?.adapterVoltage
    fun set(value: VoltageCalibration) = viewModel.setVoltageCalibration(value)
    fun step(value: Float, by: Float) = ((value + by) * 100).roundToInt() / 100f

    Hint(stringResource(R.string.settings_voltage_hint))
    Hint(
        if (adapterNow == null) stringResource(R.string.settings_voltage_now_none)
        else stringResource(R.string.settings_voltage_now, "%.1f".format(adapterNow), "%.2f".format(calibration.apply(adapterNow))),
    )
    SwitchRow(calibration.enabled, stringResource(R.string.settings_voltage_switch), "") { set(calibration.copy(enabled = it)) }
    if (!calibration.enabled) return

    Hint(stringResource(R.string.settings_voltage_off))
    StepperRow(
        stringResource(R.string.settings_voltage_adapter, "%.1f".format(calibration.offAdapter)),
        { set(calibration.copy(offAdapter = step(calibration.offAdapter, -0.1f))) },
        { set(calibration.copy(offAdapter = step(calibration.offAdapter, 0.1f))) },
    )
    StepperRow(
        stringResource(R.string.settings_voltage_meter, "%.2f".format(calibration.offReal)),
        { set(calibration.copy(offReal = step(calibration.offReal, -0.01f))) },
        { set(calibration.copy(offReal = step(calibration.offReal, 0.01f))) },
    )
    if (adapterNow != null) {
        ActionButton(stringResource(R.string.settings_voltage_take, "%.1f".format(adapterNow))) { set(calibration.copy(offAdapter = adapterNow)) }
    }

    Hint(stringResource(R.string.settings_voltage_running))
    StepperRow(
        stringResource(R.string.settings_voltage_adapter, "%.1f".format(calibration.runningAdapter)),
        { set(calibration.copy(runningAdapter = step(calibration.runningAdapter, -0.1f))) },
        { set(calibration.copy(runningAdapter = step(calibration.runningAdapter, 0.1f))) },
    )
    StepperRow(
        stringResource(R.string.settings_voltage_meter, "%.2f".format(calibration.runningReal)),
        { set(calibration.copy(runningReal = step(calibration.runningReal, -0.01f))) },
        { set(calibration.copy(runningReal = step(calibration.runningReal, 0.01f))) },
    )
    if (adapterNow != null) {
        ActionButton(stringResource(R.string.settings_voltage_take, "%.1f".format(adapterNow))) { set(calibration.copy(runningAdapter = adapterNow)) }
    }
}

/** Signing in to the account the trips go to, and whether they are recorded. */
@Composable
private fun TripSyncSettings(settings: LauncherSettings, viewModel: LauncherViewModel) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signingIn by remember { mutableStateOf(false) }

    when (val state = account) {
        AccountState.NotConfigured -> Hint(stringResource(R.string.sync_not_configured))
        AccountState.SignedOut -> {
            Hint(stringResource(R.string.sync_signed_out_hint))
            ActionButton(stringResource(if (signingIn) R.string.sync_signing_in else R.string.sync_sign_in)) {
                val activity = context.findActivity()
                if (signingIn || activity == null) return@ActionButton
                signingIn = true
                scope.launch {
                    val result = viewModel.signInWithGoogle(activity)
                    signingIn = false
                    result.exceptionOrNull()?.let { error ->
                        if ((error as? SignInException)?.cancelled != true) {
                            Toast.makeText(context, context.getString(R.string.sync_sign_in_failed, error.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        is AccountState.SignedIn -> {
            Hint(stringResource(R.string.sync_signed_in_as, state.email ?: state.uid))
            SwitchRow(settings.syncTrips, stringResource(R.string.sync_switch), stringResource(R.string.sync_switch_hint), viewModel::setSyncTrips)
            if (settings.syncTrips && !hasLocationPermission(context)) Hint(stringResource(R.string.sync_needs_location))
            if (settings.syncTrips) SyncStatusLines(viewModel.syncState.collectAsStateWithLifecycle().value)
            ActionButton(stringResource(R.string.sync_sign_out), viewModel::signOut)
        }
    }
}

/** Where the recording stands: running, hearing the GPS, and whether Firebase has confirmed what was sent. */
@Composable
private fun SyncStatusLines(state: SyncState) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    fun ago(then: Long): String {
        val seconds = ((now - then) / 1_000).coerceAtLeast(0)
        return when {
            seconds < 60 -> context.getString(R.string.sync_ago_seconds, seconds)
            seconds < 3_600 -> context.getString(R.string.sync_ago_minutes, seconds / 60)
            else -> context.getString(R.string.sync_ago_hours, seconds / 3_600)
        }
    }

    Hint(stringResource(if (state.recording) R.string.sync_status_recording else R.string.sync_status_stopped))
    Hint(
        if (state.lastFixAt == 0L) stringResource(R.string.sync_status_gps_none)
        else stringResource(R.string.sync_status_gps, ago(state.lastFixAt), state.fixes),
    )
    Hint(
        if (state.lastConfirmedAt == 0L) stringResource(R.string.sync_status_sent_none, state.writes, state.pending)
        else stringResource(R.string.sync_status_sent, ago(state.lastConfirmedAt), state.pending),
    )
    state.lastError?.let { Hint(stringResource(R.string.sync_status_error, it)) }
}

/** A switch with what it does written under it. */
@Composable
private fun SwitchRow(checked: Boolean, label: String, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Which values the tiles on Home show. Only the ones the car can fill are listed: what it answers is read when the
 * adapter connects and remembered, so until the first connection everything is listed.
 */
@Composable
private fun ObdFieldSettings(chosen: List<ObdField>, carPids: Set<Int>?, viewModel: LauncherViewModel) {
    val available = availableFields(carPids)
    Hint(stringResource(R.string.settings_obd_fields_hint, MAX_OBD_FIELDS))
    Hint(
        if (carPids == null) stringResource(R.string.settings_obd_fields_unknown)
        else stringResource(R.string.settings_obd_fields_hidden, ObdField.entries.size - available.size),
    )
    if (chosen.size >= MAX_OBD_FIELDS) Hint(stringResource(R.string.settings_obd_fields_full, MAX_OBD_FIELDS))
    // Folded groups keep the long list short; each says how many of its values are on, so nothing chosen is hidden.
    var open by rememberSaveable { mutableStateOf<ObdGroup?>(null) }
    ObdGroup.entries.forEach { group ->
        val fields = available.filter { it.group == group }
        if (fields.isEmpty()) return@forEach
        GroupRow(stringResource(group.label), fields.count { it in chosen }, expanded = open == group) {
            open = if (open == group) null else group
        }
        if (open == group) fields.forEach { field ->
            SwitchRow(field in chosen, stringResource(field.label), "") { on -> viewModel.setObdField(field, on) }
        }
    }
}

/** The heading of a folded group: its name, how many of its values are on, and an arrow that turns when it opens. */
@Composable
private fun GroupRow(label: String, onCount: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (onCount > 0) {
            Text(
                stringResource(R.string.settings_obd_group_count, onCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Text("▾", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(if (expanded) 180f else 0f))
    }
}

/** Which paired Bluetooth device is the OBD adapter, with the way to pair one and to allow Bluetooth. */
@Composable
private fun ObdDeviceSettings(address: String, bluetooth: BluetoothPermission, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    // Devices paired while Settings is open show up when the user comes back from Android's Bluetooth screen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val devices = remember(refresh, bluetooth.granted) { bondedDevices(context) }

    Hint(stringResource(R.string.obd_pair_hint))
    if (!bluetooth.granted) {
        ActionButton(stringResource(R.string.obd_grant), bluetooth.request)
    } else {
        RadioRow(address == AUTO_ADDRESS, stringResource(R.string.obd_device_auto)) { onSelect(AUTO_ADDRESS) }
        devices.forEach { device ->
            RadioRow(address == device.address, "${device.name}  (${device.address})") { onSelect(device.address) }
        }
        if (devices.isEmpty()) Hint(stringResource(R.string.obd_no_paired))
        if (BuildConfig.DEBUG) {
            RadioRow(address == SIMULATED_ADDRESS, stringResource(R.string.obd_device_simulated)) { onSelect(SIMULATED_ADDRESS) }
        }
    }
    ActionButton(stringResource(R.string.obd_open_bluetooth)) { openBluetoothSettings(context) }
}

private fun openBluetoothSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.obd_bluetooth_settings_missing, Toast.LENGTH_LONG).show()
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
