package com.minhphan.launcher.data

import android.content.Context
import com.minhphan.launcher.obd.DEFAULT_OBD_FIELDS
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.VoltageCalibration
import com.minhphan.launcher.obd.availableFields
import com.minhphan.launcher.obd.decodeCarPids
import com.minhphan.launcher.obd.encodeCarPids
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
    /** How the adapter's battery voltage is corrected to what a multimeter reads at the battery. */
    val voltageCalibration: VoltageCalibration = VoltageCalibration(),
    /** The mode 01 PIDs the car said it answers, the last time it said; null until then. */
    val carPids: Set<Int>? = null,
) {
    /** The chosen values the car can fill: what Home shows. The others stay saved, in case the car lists them again. */
    val shownObdFields: List<ObdField> get() = obdFields.filter { it in availableFields(carPids) }
}

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
        val fields = withField(_settings.value.shownObdFields, field, on)
        prefs.edit().putString(KEY_OBD_FIELDS, encodeObdFields(fields)).apply()
        _settings.value = _settings.value.copy(obdFields = fields)
    }

    /**
     * Remembers what the car answers, so values it does not have are neither offered nor shown, even while the
     * adapter is not connected. Added to what it said before: a support block that timed out on one connection must
     * not hide values the car does have.
     */
    fun setCarPids(pids: Set<Int>) {
        val current = _settings.value
        val merged = (current.carPids ?: emptySet()) + pids
        if (merged == current.carPids) return
        prefs.edit().putString(KEY_CAR_PIDS, encodeCarPids(merged)).apply()
        _settings.value = current.copy(carPids = merged)
    }

    fun setTankLiters(value: Int) {
        val liters = value.coerceIn(TANK_LITERS_RANGE)
        prefs.edit().putInt(KEY_TANK, liters).apply()
        _settings.value = _settings.value.copy(tankLiters = liters)
    }

    fun setVoltageCalibration(value: VoltageCalibration) {
        prefs.edit()
            .putBoolean(KEY_VOLT_ON, value.enabled)
            .putFloat(KEY_VOLT_OFF_ADAPTER, value.offAdapter)
            .putFloat(KEY_VOLT_OFF_REAL, value.offReal)
            .putFloat(KEY_VOLT_RUN_ADAPTER, value.runningAdapter)
            .putFloat(KEY_VOLT_RUN_REAL, value.runningReal)
            .apply()
        _settings.value = _settings.value.copy(voltageCalibration = value)
    }

    private fun readVoltageCalibration(): VoltageCalibration {
        val default = VoltageCalibration()
        return VoltageCalibration(
            enabled = prefs.getBoolean(KEY_VOLT_ON, default.enabled),
            offAdapter = prefs.getFloat(KEY_VOLT_OFF_ADAPTER, default.offAdapter),
            offReal = prefs.getFloat(KEY_VOLT_OFF_REAL, default.offReal),
            runningAdapter = prefs.getFloat(KEY_VOLT_RUN_ADAPTER, default.runningAdapter),
            runningReal = prefs.getFloat(KEY_VOLT_RUN_REAL, default.runningReal),
        )
    }

    private fun read() = LauncherSettings(
        theme = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.Auto),
        obdAddress = prefs.getString(KEY_OBD, "").orEmpty(),
        syncTrips = prefs.getBoolean(KEY_SYNC, true),
        tankLiters = prefs.getInt(KEY_TANK, DEFAULT_TANK_LITERS).coerceIn(TANK_LITERS_RANGE),
        obdFields = decodeObdFields(prefs.getString(KEY_OBD_FIELDS, null)),
        voltageCalibration = readVoltageCalibration(),
        carPids = decodeCarPids(prefs.getString(KEY_CAR_PIDS, null)),
    )

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_OBD = "obd_address"
        const val KEY_SYNC = "sync_trips"
        const val KEY_TANK = "tank_liters"
        const val KEY_OBD_FIELDS = "obd_fields"
        const val KEY_CAR_PIDS = "car_pids"
        const val KEY_VOLT_ON = "voltage_calibration"
        const val KEY_VOLT_OFF_ADAPTER = "voltage_off_adapter"
        const val KEY_VOLT_OFF_REAL = "voltage_off_real"
        const val KEY_VOLT_RUN_ADAPTER = "voltage_running_adapter"
        const val KEY_VOLT_RUN_REAL = "voltage_running_real"
    }
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
