package com.minhphan.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.core.content.ContextCompat
import com.minhphan.launcher.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The current local time, refreshed every minute and whenever the clock or the time zone is changed. */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val context = LocalContext.current
    val now = remember { mutableStateOf(LocalDateTime.now()) }

    // ACTION_TIME_TICK fires every minute while registered; the other two cover manual clock/zone changes.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                now.value = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        now.value = LocalDateTime.now()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

/** Big time with the weekday and date under it, both centred. The sizes are passed in so the scene can scale them. */
@Composable
fun Clock(
    now: LocalDateTime,
    timeSize: TextUnit,
    dateSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    dateColor: Color = color,
    shadow: Shadow? = null,
) {
    val context = LocalContext.current
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault()).format(now)
    val weekday = stringArrayResource(R.array.weekdays)[now.dayOfWeek.value - 1]
    val date = DateTimeFormatter.ofPattern(stringResource(R.string.date_pattern), Locale.getDefault()).format(now)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = time,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = timeSize, lineHeight = timeSize, fontWeight = FontWeight.Bold, color = color, shadow = shadow,
            ),
        )
        Text(
            text = stringResource(R.string.date_format, weekday, date),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = dateSize, lineHeight = dateSize, color = dateColor, shadow = shadow,
            ),
        )
    }
}
