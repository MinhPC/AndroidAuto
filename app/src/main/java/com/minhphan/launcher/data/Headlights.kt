package com.minhphan.launcher.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The screen at this share of its day brightness or below counts as dimmed by the headlights. */
internal const val DIMMED_SHARE = 0.75f

/** Whether the screen at [brightness] is dimmed from [dayLevel], its brightness with the lights off. */
internal fun isDimmed(brightness: Int, dayLevel: Int): Boolean = dayLevel > 0 && brightness <= dayLevel * DIMMED_SHARE

/** The day level after seeing the screen at [brightness]: the brightest it has been. */
internal fun learnDayLevel(dayLevel: Int, brightness: Int): Int = maxOf(dayLevel, brightness)

/**
 * The screen's brightness setting ([brightness], 0 to 255 on most units, -1 where it cannot be read) and the level it
 * has by day ([dayLevel]).
 */
data class ScreenLight(val brightness: Int, val dayLevel: Int) {
    /** Whether the headlights are on, as far as the screen shows it. */
    val dimmed: Boolean get() = isDimmed(brightness, dayLevel)
}

/**
 * Whether the headlights are on, told by the screen: a head unit wired to the lighting circuit dims its screen when
 * the lights come on, and this watches Android's brightness setting for that drop. Nothing tells the day level from
 * the night one, so the brightest the screen has been is taken as the day level and remembered across restarts; the
 * screen at [DIMMED_SHARE] of that or below counts as the lights on. Turning the brightness up by hand for a while
 * raises the day level with it, so it can be set back to the brightness on show ([relearn]).
 */
class HeadlightMonitor(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ScreenLight(-1, prefs.getInt(KEY_DAY_LEVEL, 0)))
    val state: StateFlow<ScreenLight> = _state

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        update()
    }

    /** Takes the brightness on show as the day level. */
    fun relearn() {
        val brightness = read()
        if (brightness < 0) return
        save(brightness)
        _state.value = ScreenLight(brightness, brightness)
    }

    private fun update() {
        val brightness = read()
        val current = _state.value
        val dayLevel = if (brightness < 0) current.dayLevel else learnDayLevel(current.dayLevel, brightness)
        if (dayLevel != current.dayLevel) save(dayLevel)
        _state.value = ScreenLight(brightness, dayLevel)
    }

    private fun read(): Int = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)

    private fun save(dayLevel: Int) {
        prefs.edit().putInt(KEY_DAY_LEVEL, dayLevel).apply()
    }

    private companion object {
        const val KEY_DAY_LEVEL = "day_brightness"
    }
}
