package com.minhphan.launcher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.trip.DriveClock
import com.minhphan.trip.DriveRecord
import com.minhphan.trip.OpenDrive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardShape = RoundedCornerShape(22.dp)

/** Figures all the same width, so a clock that counts does not jitter. */
private const val Tabular = "tnum"

/**
 * The trip screen, opened from the trip button on the scene. A journey runs from when the driver starts it to when they
 * end it, for as many days as it takes, and counts only its time on the move (see [DriveClock]). On the left the
 * journey in progress: its time on the move, counting while the car moves, when it began and how long ago, and the
 * button to start or end one; on the right every journey before, newest first, under the day it began.
 */
@Composable
fun TripSheet(
    drives: StateFlow<DriveClock>,
    locationGranted: Boolean,
    onStartDrive: () -> Unit,
    onEndDrive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clock by drives.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 980.dp)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.trip_card_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_close), style = MaterialTheme.typography.titleMedium)
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CurrentDrive(clock.current, locationGranted, onStartDrive, onEndDrive, Modifier.weight(0.42f).fillMaxHeight())
                History(clock.history, Modifier.weight(0.58f).fillMaxHeight())
            }
        }
    }
}

/**
 * The journey in progress, or the button to start one. Its time on the move ticks every second while the car moves;
 * standing still, only the time since it began moves on, every half minute.
 */
@Composable
private fun CurrentDrive(open: OpenDrive?, locationGranted: Boolean, onStart: () -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val now by produceState(System.currentTimeMillis(), open) {
        value = System.currentTimeMillis()
        while (open != null) {
            delay(if (open.moving) 1_000 else 30_000)
            value = System.currentTimeMillis()
        }
    }
    Column(
        modifier.clip(CardShape).background(colors.surfaceVariant).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DriveStatus(open)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.trip_moving_time), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        Text(
            stopwatch(open?.movingSecondsAt(now) ?: 0L),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 88.sp, lineHeight = 92.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular,
            ),
            color = if (open != null) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.4f),
            maxLines = 1,
        )
        Text(
            when {
                open != null -> stringResource(R.string.trip_started_at, moment(open.startedAt))
                !locationGranted -> stringResource(R.string.trip_needs_location)
                else -> stringResource(R.string.trip_manual_hint)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (open != null) {
            // How long the journey has lasted, nights and stops included: on a long one, the days it has been going.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha = 0.6f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.trip_elapsed), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    duration(open.elapsedSecondsAt(now)),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular),
                    color = colors.onSurface,
                )
            }
            EndButton(open.startedAt, onEnd)
        } else {
            Button(
                onClick = onStart,
                enabled = locationGranted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(R.string.trip_start), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/**
 * Ends the journey on a second press within a few seconds: a journey of days ended by a stray touch on a bumpy road
 * could not be had back. The first press only asks.
 */
@Composable
private fun EndButton(journey: Long, onEnd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var armed by remember(journey) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(END_CONFIRM_MS)
            armed = false
        }
    }
    Button(
        onClick = { if (armed) onEnd() else armed = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        colors = if (armed) {
            ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
        } else {
            ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)
        },
    ) { Text(stringResource(if (armed) R.string.trip_end_confirm else R.string.trip_end), style = MaterialTheme.typography.titleMedium) }
}

private const val END_CONFIRM_MS = 4_000L

/** A dot and a word: on the move, standing in the middle of a journey, or none running. */
@Composable
private fun DriveStatus(open: OpenDrive?) {
    val (color, text) = when {
        open == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) to R.string.trip_status_none
        open.moving -> StatusGood to R.string.trip_status_moving
        else -> StatusWarn to R.string.trip_status_stopped
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** The journeys before, newest first, under the day each began, with that day's count and time on the move. */
@Composable
private fun History(history: List<DriveRecord>, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // Grouped once for each list, not on every recomposition.
    val days = remember(history) { history.groupBy { localDate(it.startedAt) }.toList() }
    Column(modifier.clip(CardShape).background(colors.surfaceVariant).padding(top = 18.dp, start = 18.dp, end = 18.dp)) {
        Text(stringResource(R.string.trip_history), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = colors.onSurface)
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.trip_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((day, dayDrives) in days) {
                item(key = "day-$day") { DayHeader(day, dayDrives) }
                items(dayDrives, key = { it.startedAt }) { DriveRow(it) }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, drives: List<DriveRecord>) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayName(day), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text(
            pluralStringResource(R.plurals.trip_day_summary, drives.size, drives.size, duration(drives.sumOf { it.movingSeconds })),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One journey: when it began and ended (with the day of the end too when it ran past the day it began), and how long it
 * lasted under them; its time on the move large on the right.
 */
@Composable
private fun DriveRow(drive: DriveRecord) {
    val colors = MaterialTheme.colorScheme
    val sameDay = localDate(drive.startedAt) == localDate(drive.endedAt)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.trip_range, timeOfDay(drive.startedAt), if (sameDay) timeOfDay(drive.endedAt) else dayAndTime(drive.endedAt)),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = Tabular),
                color = colors.onSurface,
            )
            Text(
                stringResource(R.string.trip_lasted, duration(drive.elapsedSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                duration(drive.movingSeconds),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular),
                color = colors.onSurface,
            )
            Text(stringResource(R.string.trip_on_the_move), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

private fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Today", "Yesterday", or the weekday and date. */
@Composable
private fun dayName(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.trip_day_today)
        today.minusDays(1) -> stringResource(R.string.trip_day_yesterday)
        else -> {
            val weekday = stringArrayResource(R.array.weekdays)[day.dayOfWeek.value - 1]
            val date = DateTimeFormatter.ofPattern(stringResource(R.string.date_pattern), Locale.getDefault()).format(day)
            stringResource(R.string.date_format, weekday, date)
        }
    }
}

/** The time of day, with the day before it when that is not today: "08:12", or "Yesterday, 08:12". */
@Composable
private fun moment(epochMs: Long): String = if (localDate(epochMs) == LocalDate.now()) timeOfDay(epochMs) else dayAndTime(epochMs)

/** The day and the time of day, today too: "Today, 08:12", for the end of a journey that began on another day. */
@Composable
private fun dayAndTime(epochMs: Long): String = stringResource(R.string.trip_day_and_time, dayName(localDate(epochMs)), timeOfDay(epochMs))

/** The time of day in the device's 12- or 24-hour format. */
@Composable
private fun timeOfDay(epochMs: Long): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

/** Seconds as a stopwatch: m:ss, or h:mm:ss from an hour on, the hours running past 24 on a long journey. */
private fun stopwatch(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Seconds in words: "2 days 5 h", "1 h 05 min", "28 min", or "45 s" under a minute. */
@Composable
private fun duration(seconds: Long): String = when {
    seconds >= 86_400 -> stringResource(R.string.duration_days, seconds / 86_400, seconds % 86_400 / 3600)
    seconds >= 3600 -> stringResource(R.string.duration_hours, seconds / 3600, seconds % 3600 / 60)
    seconds >= 60 -> stringResource(R.string.duration_minutes, seconds / 60)
    else -> stringResource(R.string.duration_seconds, seconds)
}
