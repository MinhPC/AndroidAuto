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
    val homeAddress: String = "",
    val workAddress: String = "",
    val navigator: NavigatorChoice = NavigatorChoice.Auto,
    val theme: ThemeMode = ThemeMode.Auto,
) {
    fun addressOf(place: Place) = if (place == Place.Home) homeAddress else workAddress
}

/** Persists [LauncherSettings] next to the dock apps in the "launcher" preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = _settings

    fun setHomeAddress(value: String) {
        prefs.edit().putString(KEY_HOME, value).apply()
        _settings.value = _settings.value.copy(homeAddress = value)
    }

    fun setWorkAddress(value: String) {
        prefs.edit().putString(KEY_WORK, value).apply()
        _settings.value = _settings.value.copy(workAddress = value)
    }

    fun setNavigator(value: NavigatorChoice) {
        prefs.edit().putString(KEY_NAVIGATOR, value.name).apply()
        _settings.value = _settings.value.copy(navigator = value)
    }

    fun setTheme(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
        _settings.value = _settings.value.copy(theme = value)
    }

    private fun read() = LauncherSettings(
        homeAddress = prefs.getString(KEY_HOME, "").orEmpty(),
        workAddress = prefs.getString(KEY_WORK, "").orEmpty(),
        navigator = enumOrDefault(prefs.getString(KEY_NAVIGATOR, null), NavigatorChoice.Auto),
        theme = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.Auto),
    )

    private companion object {
        const val KEY_HOME = "home_address"
        const val KEY_WORK = "work_address"
        const val KEY_NAVIGATOR = "navigator"
        const val KEY_THEME = "theme_mode"
    }
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
