package com.minhphan.launcher.data

import android.content.Context
import com.minhphan.launcher.obd.DEFAULT_OBD_FIELDS
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.decodeObdFields
import com.minhphan.launcher.obd.encodeObdFields
import com.minhphan.launcher.obd.withField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the launcher decides between its day and night look. */
enum class ThemeMode {
    /** Sunrise and sunset at the car's last GPS position. */
    Auto,

    /** Whatever Android's night mode says (many head units never change it). */
    System,
    Light,
    Dark,
}

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.Auto,
    /** Bluetooth address of the OBD adapter; empty picks a paired one by its name. */
    val obdAddress: String = "",
    /** Record trips and send them to the signed-in account. Does nothing until an account is signed in. */
    val syncTrips: Boolean = true,
    /** The fuel tank in litres, for the estimate of how far the car can still go. */
    val tankLiters: Int = DEFAULT_TANK_LITERS,
    /** What the tiles on Home show, in the order of [ObdField]. */
    val obdFields: List<ObdField> = DEFAULT_OBD_FIELDS,
)

/** A Honda Civic (8th generation) holds 50 litres. */
const val DEFAULT_TANK_LITERS = 50
val TANK_LITERS_RANGE = 20..100

/** Persists [LauncherSettings] next to the dock apps in the "launcher" preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = _settings

    fun setTheme(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
        _settings.value = _settings.value.copy(theme = value)
    }

    fun setObdAddress(value: String) {
        prefs.edit().putString(KEY_OBD, value).apply()
        _settings.value = _settings.value.copy(obdAddress = value)
    }

    fun setSyncTrips(value: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC, value).apply()
        _settings.value = _settings.value.copy(syncTrips = value)
    }

    /** Switches one of the values on Home on or off; at most nine can be on. */
    fun setObdField(field: ObdField, on: Boolean) {
        val fields = withField(_settings.value.obdFields, field, on)
        prefs.edit().putString(KEY_OBD_FIELDS, encodeObdFields(fields)).apply()
        _settings.value = _settings.value.copy(obdFields = fields)
    }

    fun setTankLiters(value: Int) {
        val liters = value.coerceIn(TANK_LITERS_RANGE)
        prefs.edit().putInt(KEY_TANK, liters).apply()
        _settings.value = _settings.value.copy(tankLiters = liters)
    }

    private fun read() = LauncherSettings(
        theme = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.Auto),
        obdAddress = prefs.getString(KEY_OBD, "").orEmpty(),
        syncTrips = prefs.getBoolean(KEY_SYNC, true),
        tankLiters = prefs.getInt(KEY_TANK, DEFAULT_TANK_LITERS).coerceIn(TANK_LITERS_RANGE),
        obdFields = decodeObdFields(prefs.getString(KEY_OBD_FIELDS, null)),
    )

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_OBD = "obd_address"
        const val KEY_SYNC = "sync_trips"
        const val KEY_TANK = "tank_liters"
        const val KEY_OBD_FIELDS = "obd_fields"
    }
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
