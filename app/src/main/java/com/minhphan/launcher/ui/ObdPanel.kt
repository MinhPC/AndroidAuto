package com.minhphan.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import com.minhphan.trip.FuelBook
import com.minhphan.trip.TripMeter
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** How faded the values are while the connection is being re-established. */
private const val STALE_ALPHA = 0.45f

// Where each smaller reading turns amber and red.
private const val COOLANT_WARN_C = 100
private const val COOLANT_HOT_C = 105
private const val INTAKE_WARN_C = 60
private const val INTAKE_HOT_C = 75
private const val TRIM_WARN_PERCENT = 10
private const val TRIM_BAD_PERCENT = 20
private const val TRIM_SPAN_PERCENT = 25f
private const val VOLTAGE_LOW = 11.8f
private const val VOLTAGE_HIGH = 15f

/** How a reading is coloured: normal, worth a look, or wrong. */
private enum class Tone { Normal, Warn, Danger }

/**
 * The right half of Home: the trip computer and the fuel book on top (they need no adapter), then, read from the
 * OBD adapter, coolant and intake air temperature, battery voltage, engine load, throttle and fuel trim as small
 * tiles with a level bar. Speed and engine speed are not here: the dashboard already shows them. Values the car
 * does not report show "--"; while there is no connection the panel says why and, where the user can fix it,
 * offers the way. While the link is only being re-established the last reading stays up, faded.
 */
@Composable
fun ObdPanel(
    obd: StateFlow<ObdState>,
    trip: StateFlow<TripMeter>,
    totalKm: StateFlow<Double>,
    fuel: StateFlow<FuelBook>,
    recording: Boolean,
    bluetooth: BluetoothPermission,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onRefuel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collected here, not by the caller, so a new reading recomposes only this panel and not the whole home screen.
    val state by obd.collectAsStateWithLifecycle()
    val (heldReading, expiresInMs) = when (val s = state) {
        is ObdState.Connected -> null to 0L
        is ObdState.Connecting -> s.last to s.lastExpiresInMs
        is ObdState.Problem -> s.last to s.lastExpiresInMs
    }
    // The connection can stay silent for longer than a reading is worth showing, so the panel drops it on its own.
    var expired by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (heldReading != null) {
            delay(expiresInMs)
            expired = true
        }
    }
    val last = heldReading.takeUnless { expired }
    val values = (state as? ObdState.Connected)?.values ?: last ?: ObdValues()
    val dim = if (last != null) STALE_ALPHA else 1f
    val problem = (state as? ObdState.Problem)?.takeIf { last == null }?.problem

    val shape = RoundedCornerShape(28.dp)
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        // Type in the tiles is sized from the panel height, so it fills the panel on a wide screen and a tall one.
        val tileValueSize = with(density) { (maxHeight * 0.055f).toSp() }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(state)
            Row(Modifier.weight(2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripCard(trip, totalKm, recording, onStartTrip, onStopTrip)
                RefuelCard(fuel, onRefuel)
            }
            if (problem != null) {
                Recovery(problem, bluetooth, onOpenSettings, Modifier.weight(2f).fillMaxWidth())
                return@Column
            }

            TileRow(dim) {
                Metric(
                    label = stringResource(R.string.obd_coolant),
                    value = values.coolantC?.toString(),
                    unit = "°C",
                    valueSize = tileValueSize,
                    fraction = values.coolantC?.let { (it - 40) / 80f },
                    tone = toneAbove(values.coolantC, COOLANT_WARN_C, COOLANT_HOT_C),
                )
                Metric(
                    label = stringResource(R.string.obd_intake),
                    value = values.intakeC?.toString(),
                    unit = "°C",
                    valueSize = tileValueSize,
                    fraction = values.intakeC?.let { it / 80f },
                    tone = toneAbove(values.intakeC, INTAKE_WARN_C, INTAKE_HOT_C),
                )
                Metric(
                    label = stringResource(R.string.obd_voltage),
                    value = values.voltage?.let { "%.1f".format(it) },
                    unit = "V",
                    valueSize = tileValueSize,
                    fraction = values.voltage?.let { (it - 10f) / 6f },
                    tone = if (values.voltage?.let { it < VOLTAGE_LOW || it > VOLTAGE_HIGH } == true) Tone.Danger else Tone.Normal,
                )
            }
            TileRow(dim) {
                Metric(
                    label = stringResource(R.string.obd_load),
                    value = values.loadPercent?.toString(),
                    unit = "%",
                    valueSize = tileValueSize,
                    fraction = values.loadPercent?.let { it / 100f },
                )
                Metric(
                    label = stringResource(R.string.obd_throttle),
                    value = values.throttlePercent?.toString(),
                    unit = "%",
                    valueSize = tileValueSize,
                    fraction = values.throttlePercent?.let { it / 100f },
                )
                Metric(
                    label = stringResource(R.string.obd_fuel_trim),
                    value = values.fuelTrimPercent?.let { "%+d".format(it) },
                    unit = "%",
                    valueSize = tileValueSize,
                    // The bar sits half full at zero: filled more when the engine adds fuel, less when it takes it away.
                    fraction = values.fuelTrimPercent?.let { (it + TRIM_SPAN_PERCENT) / (2 * TRIM_SPAN_PERCENT) },
                    tone = toneAway(values.fuelTrimPercent, TRIM_WARN_PERCENT, TRIM_BAD_PERCENT),
                )
            }
        }
    }
}

private fun toneAbove(value: Int?, warn: Int, danger: Int) = when {
    value == null -> Tone.Normal
    value >= danger -> Tone.Danger
    value >= warn -> Tone.Warn
    else -> Tone.Normal
}

/** Like [toneAbove], for a reading that is wrong in either direction: what counts is how far it is from zero. */
private fun toneAway(value: Int?, warn: Int, danger: Int) = toneAbove(value?.let { abs(it) }, warn, danger)

@Composable
private fun ColumnScope.TileRow(alpha: Float, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.weight(1f).fillMaxWidth().alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Header(state: ObdState) {
    val (color, text) = when (state) {
        is ObdState.Connected -> StatusGood to stringResource(R.string.obd_status_connected)
        is ObdState.Connecting -> StatusWarn to stringResource(R.string.obd_status_connecting)
        is ObdState.Problem -> MaterialTheme.colorScheme.error to stringResource(problemText(state.problem))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.obd_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // A pill with a dot, so the state reads at a glance even from the corner of the eye.
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
            )
        }
    }
}

/** Says what is wrong and what the user can do about it: nothing (it is retried), or a button. */
@Composable
private fun Recovery(problem: ObdProblem, bluetooth: BluetoothPermission, onOpenSettings: () -> Unit, modifier: Modifier) {
    val hint = when (problem) {
        ObdProblem.NoPermission -> R.string.obd_hint_permission
        ObdProblem.NoAdapter -> R.string.obd_hint_no_adapter
        ObdProblem.NoVehicle -> R.string.obd_hint_no_vehicle
        ObdProblem.BluetoothOff -> R.string.obd_hint_bluetooth_off
        ObdProblem.CannotConnect, ObdProblem.Lost -> R.string.obd_hint_retrying
    }
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when (problem) {
            ObdProblem.NoPermission ->
                Button(onClick = bluetooth.request, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.obd_grant), style = MaterialTheme.typography.titleMedium)
                }
            ObdProblem.NoAdapter, ObdProblem.CannotConnect ->
                Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.obd_open_settings), style = MaterialTheme.typography.titleMedium)
                }
            else -> Unit
        }
    }
}

private fun problemText(problem: ObdProblem) = when (problem) {
    ObdProblem.NoPermission -> R.string.obd_status_no_permission
    ObdProblem.BluetoothOff -> R.string.obd_status_bluetooth_off
    ObdProblem.NoAdapter -> R.string.obd_status_no_adapter
    ObdProblem.CannotConnect -> R.string.obd_status_cannot_connect
    ObdProblem.NoVehicle -> R.string.obd_status_no_vehicle
    ObdProblem.Lost -> R.string.obd_status_lost
}

/**
 * One smaller reading: label, value with its unit, and a level bar coloured by [tone] (the value turns the same
 * colour when something is wrong). A missing [value] shows "--" and the tile fades, so an unknown reading is not
 * mistaken for zero.
 */
@Composable
private fun RowScope.Metric(
    label: String,
    value: String?,
    unit: String,
    valueSize: TextUnit,
    fraction: Float?,
    tone: Tone = Tone.Normal,
) {
    val colors = MaterialTheme.colorScheme
    val toneColor = when (tone) {
        Tone.Normal -> colors.primary
        Tone.Warn -> StatusWarn
        Tone.Danger -> colors.error
    }
    val valueColor = if (tone == Tone.Normal) colors.onSurface else toneColor
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceVariant)
            .alpha(if (value == null) 0.55f else 1f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value ?: "--",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = valueSize, lineHeight = valueSize, fontWeight = FontWeight.SemiBold),
                color = valueColor,
                maxLines = 1,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                maxLines = 1,
            )
        }
        LevelBar(fraction, toneColor, Modifier.padding(top = 6.dp))
    }
}

/** A thin rounded bar filled to [fraction] (0..1); empty when the level is unknown. */
@Composable
private fun LevelBar(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(track)) {
        if (fraction != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.03f, 1f)).clip(RoundedCornerShape(50)).background(color))
        }
    }
}
