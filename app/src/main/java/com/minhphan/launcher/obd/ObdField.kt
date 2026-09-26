package com.minhphan.launcher.obd

import androidx.annotation.StringRes
import com.minhphan.launcher.R

/** The groups the values are listed in under Settings, in the order they are listed. */
enum class ObdGroup(@StringRes val label: Int) {
    ENGINE(R.string.obd_group_engine),
    TEMPERATURE(R.string.obd_group_temperature),
    FUEL(R.string.obd_group_fuel),
    AIR(R.string.obd_group_air),
    ELECTRICAL(R.string.obd_group_electrical),
    FAULTS(R.string.obd_group_faults),
}

/**
 * Something the tiles on Home can show, that the driver can switch on and off in Settings, where it is listed under
 * its [group]. [pid] is the OBD value behind it; the battery voltage has none, the adapter measures that itself.
 */
enum class ObdField(val group: ObdGroup, val pid: Pid?, @StringRes val label: Int, val unit: String) {
    COOLANT(ObdGroup.TEMPERATURE, Pid.Coolant, R.string.obd_coolant, "°C"),
    INTAKE(ObdGroup.TEMPERATURE, Pid.Intake, R.string.obd_intake, "°C"),
    VOLTAGE(ObdGroup.ELECTRICAL, null, R.string.obd_voltage, "V"),
    LOAD(ObdGroup.ENGINE, Pid.Load, R.string.obd_load, "%"),
    THROTTLE(ObdGroup.ENGINE, Pid.Throttle, R.string.obd_throttle, "%"),
    FUEL_TRIM(ObdGroup.FUEL, Pid.FuelTrim, R.string.obd_fuel_trim, "%"),
    SHORT_TRIM(ObdGroup.FUEL, Pid.ShortTrim, R.string.obd_short_trim, "%"),
    MAP(ObdGroup.AIR, Pid.Map, R.string.obd_map, "kPa"),
    TIMING(ObdGroup.AIR, Pid.Timing, R.string.obd_timing, "°"),
    MAF(ObdGroup.AIR, Pid.Maf, R.string.obd_maf, "g/s"),
    AMBIENT(ObdGroup.TEMPERATURE, Pid.Ambient, R.string.obd_ambient, "°C"),
    BARO(ObdGroup.AIR, Pid.Baro, R.string.obd_baro, "kPa"),
    OIL(ObdGroup.TEMPERATURE, Pid.Oil, R.string.obd_oil, "°C"),
    FUEL_LEVEL(ObdGroup.FUEL, Pid.FuelLevel, R.string.obd_fuel_level, "%"),
    SPEED(ObdGroup.ENGINE, Pid.Speed, R.string.obd_speed, "km/h"),
    RPM(ObdGroup.ENGINE, Pid.Rpm, R.string.obd_rpm, "rpm"),
    SHORT_TRIM_2(ObdGroup.FUEL, Pid.ShortTrim2, R.string.obd_short_trim_2, "%"),
    FUEL_TRIM_2(ObdGroup.FUEL, Pid.FuelTrim2, R.string.obd_fuel_trim_2, "%"),
    ABSOLUTE_LOAD(ObdGroup.ENGINE, Pid.AbsoluteLoad, R.string.obd_absolute_load, "%"),
    RELATIVE_THROTTLE(ObdGroup.ENGINE, Pid.RelativeThrottle, R.string.obd_relative_throttle, "%"),
    THROTTLE_ACTUATOR(ObdGroup.ENGINE, Pid.ThrottleActuator, R.string.obd_throttle_actuator, "%"),
    PEDAL(ObdGroup.ENGINE, Pid.Pedal, R.string.obd_pedal, "%"),
    LAMBDA(ObdGroup.FUEL, Pid.Lambda, R.string.obd_lambda, "λ"),
    FUEL_RATE(ObdGroup.FUEL, Pid.FuelRate, R.string.obd_fuel_rate, "L/h"),
    FUEL_PRESSURE(ObdGroup.FUEL, Pid.FuelPressure, R.string.obd_fuel_pressure, "kPa"),
    RAIL_PRESSURE(ObdGroup.FUEL, Pid.RailPressure, R.string.obd_rail_pressure, "bar"),
    CATALYST(ObdGroup.TEMPERATURE, Pid.Catalyst, R.string.obd_catalyst, "°C"),
    EGR(ObdGroup.AIR, Pid.Egr, R.string.obd_egr, "%"),
    EVAP_PURGE(ObdGroup.AIR, Pid.EvapPurge, R.string.obd_evap_purge, "%"),
    ETHANOL(ObdGroup.FUEL, Pid.Ethanol, R.string.obd_ethanol, "%"),
    TORQUE(ObdGroup.ENGINE, Pid.Torque, R.string.obd_torque, "%"),
    DEMAND_TORQUE(ObdGroup.ENGINE, Pid.DemandTorque, R.string.obd_demand_torque, "%"),
    REFERENCE_TORQUE(ObdGroup.ENGINE, Pid.ReferenceTorque, R.string.obd_reference_torque, "Nm"),
    MODULE_VOLTAGE(ObdGroup.ELECTRICAL, Pid.ModuleVoltage, R.string.obd_module_voltage, "V"),
    RUN_TIME(ObdGroup.ENGINE, Pid.RunTime, R.string.obd_run_time, "min"),
    MIL_DISTANCE(ObdGroup.FAULTS, Pid.MilDistance, R.string.obd_mil_distance, "km"),
    MIL_TIME(ObdGroup.FAULTS, Pid.MilTime, R.string.obd_mil_time, "min"),
    CLEARED_DISTANCE(ObdGroup.FAULTS, Pid.ClearedDistance, R.string.obd_cleared_distance, "km"),
    CLEARED_TIME(ObdGroup.FAULTS, Pid.ClearedTime, R.string.obd_cleared_time, "min"),
    WARM_UPS(ObdGroup.FAULTS, Pid.WarmUps, R.string.obd_warm_ups, ""),
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

/** The fields this car can fill: all of them while [carPids] (what the car said it answers) is unknown. */
fun availableFields(carPids: Set<Int>?): List<ObdField> =
    ObdField.entries.filter { it.pid == null || carPids == null || it.pid.code in carPids }

/** The PIDs the car answers as saved in the settings ("05,0C,…"); null when never read or unreadable. */
fun decodeCarPids(text: String?): Set<Int>? =
    text?.split(",")?.filter { it.isNotEmpty() }?.map { it.toIntOrNull(16) ?: return null }?.toSet()

fun encodeCarPids(pids: Set<Int>): String = pids.sorted().joinToString(",") { "%02X".format(it) }

/** The pids that have to be asked for, on top of the ones always asked, to show [fields]. */
fun extraPidsFor(fields: List<ObdField>): Set<Pid> = fields.mapNotNull { it.pid }.toSet()

/** The reading behind [field] as a number for the tile, in its display unit; null while unknown. */
fun ObdValues.reading(field: ObdField): Float? = when (field) {
    ObdField.VOLTAGE -> voltage
    else -> field.pid?.let { pid -> valueOf(pid)?.let { it * pid.step } }
}
