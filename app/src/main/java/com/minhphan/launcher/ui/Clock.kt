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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // ACTION_TIME_TICK fires every minute while registered; the other two cover manual clock/zone changes.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                now = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        now = LocalDateTime.now()
        onDispose { context.unregisterReceiver(receiver) }
    }

    val locale = Locale.getDefault()
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = DateTimeFormatter.ofPattern(timePattern, locale).format(now)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(now)

    Column(modifier) {
        Text(
            text = time,
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Light),
        )
        Text(
            text = date,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
