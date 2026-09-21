package com.minhphan.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.trip.FuelBook
import com.minhphan.trip.TripMeter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.NumberFormat
import java.util.Locale

private val CardShape = RoundedCornerShape(18.dp)

/**
 * The trip computer: press to start counting kilometres, time and average speed of one trip, press again to end it
 * and keep what it came to. The kilometres come from the GPS through the trip recorder, so without the recorder
 * (recording switched off, or nobody signed in) there is nothing to count and the button is off.
 */
@Composable
fun RowScope.TripCard(
    trip: StateFlow<TripMeter>,
    totalKm: StateFlow<Double>,
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meter by trip.collectAsStateWithLifecycle()
    val running = meter.running
    ActionCard(stringResource(R.string.trip_card_title), modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (running != null) {
                // Only this card recomposes as the kilometres and the clock move.
                val km by totalKm.collectAsStateWithLifecycle()
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        delay(1000)
                        value = System.currentTimeMillis()
                    }
                }
                val so = running.resultAt(now, km)
                Text(stringResource(R.string.trip_km, oneDecimal(so.km)), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    stringResource(R.string.trip_time_and_speed, clock(so.seconds), so.avgKmh.toInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val last = meter.last
                Text(
                    text = when {
                        !recording -> stringResource(R.string.trip_needs_recording)
                        last != null -> stringResource(R.string.trip_last, oneDecimal(last.km), clock(last.seconds))
                        else -> stringResource(R.string.trip_hint)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (running != null) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(stringResource(R.string.trip_stop), style = MaterialTheme.typography.titleMedium) }
        } else {
            Button(onClick = onStart, enabled = recording, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.trip_start), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The fuel book on the home screen: the last fill-up (litres and money), its economy and the average, and the button to add one. */
@Composable
fun RowScope.RefuelCard(fuel: StateFlow<FuelBook>, onRefuel: () -> Unit, modifier: Modifier = Modifier) {
    val book by fuel.collectAsStateWithLifecycle()
    val last = book.last
    ActionCard(stringResource(R.string.refuel_card_title), modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (last == null) {
                Text(
                    stringResource(R.string.refuel_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.refuel_last, oneDecimal(last.liters), money(last.amountVnd)),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val economy = last.kmPerLiter
                val average = book.averageKmPerLiter
                Text(
                    text = when {
                        economy != null && average != null ->
                            stringResource(R.string.refuel_economy, oneDecimal(economy), oneDecimal(average))
                        average != null -> stringResource(R.string.refuel_average, oneDecimal(average))
                        else -> stringResource(R.string.refuel_economy_none)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Button(onClick = onRefuel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.refuel_button), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RowScope.ActionCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/**
 * The form for a fill-up: litres and money, whether the tank was filled to the top, and the kilometres since the
 * last full fill-up (offered from the GPS odometer, and the driver's own figure wins). Shows the price per litre and
 * the economy as they are typed, so a slip in a figure shows before it is saved.
 */
@Composable
fun RefuelDialog(
    book: FuelBook,
    suggestedKm: Double?,
    onSave: (liters: Double, amountVnd: Long, full: Boolean, distanceKm: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var litersText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf(suggestedKm?.let { oneDecimal(it, Locale.US) } ?: "") }
    var full by remember { mutableStateOf(true) }

    val liters = number(litersText)
    val amount = amountText.toLongOrNull()
    val distance = number(distanceText)
    val valid = liters != null && liters > 0 && amount != null && amount > 0
    val preview = if (valid) {
        book.record(0, liters!!, amount!!, full, odometerKm = 0.0, distanceKm = distance ?: suggestedKm).last
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refuel_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = litersText,
                        onValueChange = { litersText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        label = { Text(stringResource(R.string.refuel_liters)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.refuel_amount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = full, onCheckedChange = { full = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.refuel_full), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.refuel_full_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (full && book.baselineKm != null) {
                    OutlinedTextField(
                        value = distanceText,
                        onValueChange = { distanceText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        label = { Text(stringResource(R.string.refuel_distance)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (full) {
                    Text(
                        stringResource(R.string.refuel_first_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview != null) {
                    val economy = preview.kmPerLiter
                    Text(
                        text = buildString {
                            append(stringResource(R.string.refuel_price_per_liter, money(preview.pricePerLiter)))
                            if (economy != null) append("  ·  ").append(stringResource(R.string.refuel_economy_preview, oneDecimal(economy)))
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(liters!!, amount!!, full, if (full && book.baselineKm != null) distance else null) },
            ) { Text(stringResource(R.string.refuel_save), style = MaterialTheme.typography.titleMedium) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.refuel_cancel), style = MaterialTheme.typography.titleMedium) }
        },
    )
}

/** What the driver typed as a number; a comma counts as the decimal point. */
private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

private fun oneDecimal(value: Double, locale: Locale = Locale.getDefault()) = String.format(locale, "%.1f", value)

private fun money(vnd: Long): String = NumberFormat.getIntegerInstance().format(vnd)

/** Seconds as m:ss, or h:mm:ss from an hour on. */
private fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
