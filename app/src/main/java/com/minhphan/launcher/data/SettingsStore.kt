package com.minhphan.launcher.data

import android.content.Context
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
)

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

    private fun read() = LauncherSettings(
        theme = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.Auto),
        obdAddress = prefs.getString(KEY_OBD, "").orEmpty(),
    )

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_OBD = "obd_address"
    }
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
