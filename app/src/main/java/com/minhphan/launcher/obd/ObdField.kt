package com.minhphan.launcher.obd

import androidx.annotation.StringRes
import com.minhphan.launcher.R

/**
 * Something the tiles on Home can show, that the driver can switch on and off in Settings. [pid] is the OBD value
 * behind it; the battery voltage has none, the adapter measures that itself.
 */
enum class ObdField(val pid: Pid?, @StringRes val label: Int, val unit: String) {
    COOLANT(Pid.Coolant, R.string.obd_coolant, "°C"),
    INTAKE(Pid.Intake, R.string.obd_intake, "°C"),
    VOLTAGE(null, R.string.obd_voltage, "V"),
    LOAD(Pid.Load, R.string.obd_load, "%"),
    THROTTLE(Pid.Throttle, R.string.obd_throttle, "%"),
    FUEL_TRIM(Pid.FuelTrim, R.string.obd_fuel_trim, "%"),
    SHORT_TRIM(Pid.ShortTrim, R.string.obd_short_trim, "%"),
    MAP(Pid.Map, R.string.obd_map, "kPa"),
    TIMING(Pid.Timing, R.string.obd_timing, "°"),
    MAF(Pid.Maf, R.string.obd_maf, "g/s"),
    AMBIENT(Pid.Ambient, R.string.obd_ambient, "°C"),
    BARO(Pid.Baro, R.string.obd_baro, "kPa"),
    OIL(Pid.Oil, R.string.obd_oil, "°C"),
    FUEL_LEVEL(Pid.FuelLevel, R.string.obd_fuel_level, "%"),
    SPEED(Pid.Speed, R.string.obd_speed, "km/h"),
    RPM(Pid.Rpm, R.string.obd_rpm, "rpm"),
}

/** What Home shows until the driver chooses otherwise. */
val DEFAULT_OBD_FIELDS = listOf(
    ObdField.COOLANT, ObdField.INTAKE, ObdField.VOLTAGE, ObdField.LOAD, ObdField.THROTTLE, ObdField.FUEL_TRIM,
)

/** Three tiles to a row and three rows fill the panel. */
const val MAX_OBD_FIELDS = 9

/**
 * [current] with [field] switched on or off. They stay in the order of the list above. With [max] already on, another
 * cannot be switched on: it is refused, and none of the others is dropped to make room.
 */
fun withField(current: List<ObdField>, field: ObdField, on: Boolean, max: Int = MAX_OBD_FIELDS): List<ObdField> {
    if (on && field !in current && current.size >= max) return current
    val wanted = if (on) current + field else current - field
    return ObdField.entries.filter { it in wanted }
}

fun encodeObdFields(fields: List<ObdField>): String = fields.joinToString(",") { it.name }

/** The fields saved as [encodeObdFields] wrote them; a name that no longer exists is skipped, and none saved is the default. */
fun decodeObdFields(text: String?): List<ObdField> {
    if (text == null) return DEFAULT_OBD_FIELDS
    val names = text.split(",").filter { it.isNotEmpty() }.toSet()
    return ObdField.entries.filter { it.name in names }
}

/** The pids that have to be asked for, on top of the ones always asked, to show [fields]. */
fun extraPidsFor(fields: List<ObdField>): Set<Pid> = fields.mapNotNull { it.pid }.toSet()

/** The reading behind [field] as a number for the tile; null while unknown. */
fun ObdValues.reading(field: ObdField): Float? = when (field) {
    ObdField.VOLTAGE -> voltage
    else -> field.pid?.let { valueOf(it) }?.toFloat()
}
