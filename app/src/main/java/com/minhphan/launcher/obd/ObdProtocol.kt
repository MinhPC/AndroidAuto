package com.minhphan.launcher.obd

/**
 * The standard OBD-II "mode 01" values that can be shown on Home, with the byte layout of each answer: the same list
 * of standard values that apps like Car Scanner read. [decode] gives whole steps of [step] display units, so a value
 * that needs decimals (the module voltage, in thousandths of a volt) still travels as a whole number.
 */
enum class Pid(val code: Int, val byteCount: Int, val step: Float = 1f) {
    Rpm(0x0C, 2),
    Speed(0x0D, 1),
    Coolant(0x05, 1),
    Load(0x04, 1),
    Throttle(0x11, 1),
    Intake(0x0F, 1),
    FuelTrim(0x07, 1),

    // Not read unless the driver puts them on Home (Settings).
    ShortTrim(0x06, 1),
    Map(0x0B, 1),
    Timing(0x0E, 1),
    Maf(0x10, 2),
    Baro(0x33, 1),
    Ambient(0x46, 1),
    Oil(0x5C, 1),
    FuelLevel(0x2F, 1),
    ShortTrim2(0x08, 1),
    FuelTrim2(0x09, 1),
    FuelPressure(0x0A, 1),
    RunTime(0x1F, 2, step = 1f / 60f),
    MilDistance(0x21, 2),
    RailPressure(0x23, 2, step = 0.1f),
    Egr(0x2C, 1),
    EvapPurge(0x2E, 1),
    WarmUps(0x30, 1),
    ClearedDistance(0x31, 2),
    Catalyst(0x3C, 2),
    ModuleVoltage(0x42, 2, step = 0.001f),
    AbsoluteLoad(0x43, 2),
    Lambda(0x44, 2, step = 1f / 32768f),
    RelativeThrottle(0x45, 1),
    Pedal(0x49, 1),
    ThrottleActuator(0x4C, 1),
    MilTime(0x4D, 2),
    ClearedTime(0x4E, 2),
    Ethanol(0x52, 1),
    FuelRate(0x5E, 2, step = 0.05f),
    DemandTorque(0x61, 1),
    Torque(0x62, 1),
    ReferenceTorque(0x63, 2),
    ;

    /**
     * The value in steps of [step] of its display unit (rpm, km/h, °C, kPa, bar, g/s, %, V, L/h, Nm, min, km or a
     * count), from the data bytes [a] and [b] of the answer.
     */
    fun decode(a: Int, b: Int): Int = when (this) {
        Rpm -> (a * 256 + b) / 4
        Speed, Map, Baro, WarmUps -> a
        Coolant, Intake, Ambient, Oil -> a - 40
        Load, Throttle, FuelLevel, Egr, EvapPurge, RelativeThrottle, Pedal, ThrottleActuator, Ethanol ->
            Math.round(a * 100f / 255f)
        FuelTrim, ShortTrim, FuelTrim2, ShortTrim2 -> Math.round((a - 128) * 100f / 128f)
        Timing -> Math.round(a / 2f - 64f)
        Maf -> Math.round((a * 256 + b) / 100f)
        FuelPressure -> a * 3
        // Seconds, shown in minutes; 10 kPa steps, shown in bar; millivolts; 1/32768 of lambda; 1/20 L/h.
        RunTime, RailPressure, ModuleVoltage, Lambda, FuelRate -> a * 256 + b
        MilDistance, ClearedDistance, MilTime, ClearedTime, ReferenceTorque -> a * 256 + b
        Catalyst -> Math.round((a * 256 + b) / 10f - 40f)
        AbsoluteLoad -> Math.round((a * 256 + b) * 100f / 255f)
        DemandTorque, Torque -> a - 125
    }
}

/** Latest reading of everything shown on Home; null while unknown or when this car does not report it. */
data class ObdValues(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val intakeC: Int? = null,
    val loadPercent: Int? = null,
    val throttlePercent: Int? = null,
    val fuelTrimPercent: Int? = null,
    val voltage: Float? = null,
    /** The battery voltage as the adapter said it, before [VoltageCalibration]; null until calibrated. */
    val adapterVoltage: Float? = null,
    /** The mode 01 PIDs the car says it answers, from its support bitmaps; null when it did not say. */
    val supported: Set<Int>? = null,
    /** The values of the other PIDs the driver has chosen to show. */
    val extra: Map<Pid, Int> = emptyMap(),
) {
    fun with(pid: Pid, value: Int?): ObdValues = when (pid) {
        Pid.Rpm -> copy(rpm = value)
        Pid.Speed -> copy(speedKmh = value)
        Pid.Coolant -> copy(coolantC = value)
        Pid.Intake -> copy(intakeC = value)
        Pid.Load -> copy(loadPercent = value)
        Pid.Throttle -> copy(throttlePercent = value)
        Pid.FuelTrim -> copy(fuelTrimPercent = value)
        else -> copy(extra = if (value == null) extra - pid else extra + (pid to value))
    }

    /** What the car last said for [pid]; null while unknown. */
    fun valueOf(pid: Pid): Int? = when (pid) {
        Pid.Rpm -> rpm
        Pid.Speed -> speedKmh
        Pid.Coolant -> coolantC
        Pid.Intake -> intakeC
        Pid.Load -> loadPercent
        Pid.Throttle -> throttlePercent
        Pid.FuelTrim -> fuelTrimPercent
        else -> extra[pid]
    }
}

/**
 * The last live reading, kept for a short while after the link drops so the screen can show it, dimmed, while
 * the adapter reconnects, instead of blanking on every Bluetooth hiccup. After [staleAfterMs] it is forgotten:
 * a speed that is a minute old is worse than no speed.
 */
class RecentValues(private val staleAfterMs: Long, private val now: () -> Long) {
    private var values: ObdValues? = null
    private var recordedAt = 0L

    fun record(latest: ObdValues) {
        values = latest
        recordedAt = now()
    }

    fun get(): ObdValues? = values?.takeIf { now() - recordedAt < staleAfterMs }

    /** How long [get] will still return the reading; 0 when there is none. */
    fun remainingMs(): Long = if (get() == null) 0L else staleAfterMs - (now() - recordedAt)
}

/** The data bytes of the answer to "01 <pid>" found in [response], or null if the car did not answer with them. */
fun parsePidResponse(response: String, code: Int, byteCount: Int): IntArray? {
    val prefix = "41%02X".format(code)
    for (line in response.lineSequence()) {
        val text = line.filterNot { it.isWhitespace() }.uppercase()
        if (!text.startsWith(prefix) || text.length < prefix.length + byteCount * 2) continue
        val bytes = (0 until byteCount).map { i ->
            text.substring(prefix.length + i * 2, prefix.length + i * 2 + 2).toIntOrNull(16)
        }
        if (bytes.all { it != null }) return bytes.map { it!! }.toIntArray()
    }
    return null
}

/** The data bytes of every ECU that answered "01 <code>": more than one can reply, and their lines all count. */
fun parseAllPidResponses(response: String, code: Int, byteCount: Int): List<IntArray> {
    val prefix = "41%02X".format(code)
    return response.lineSequence().mapNotNull { line ->
        val text = line.filterNot { it.isWhitespace() }.uppercase()
        if (!text.startsWith(prefix) || text.length < prefix.length + byteCount * 2) return@mapNotNull null
        val bytes = (0 until byteCount).map { i ->
            text.substring(prefix.length + i * 2, prefix.length + i * 2 + 2).toIntOrNull(16)
        }
        if (bytes.all { it != null }) bytes.map { it!! }.toIntArray() else null
    }.toList()
}

/**
 * The PIDs listed in one support bitmap: four bytes that answer "01 <base>" and cover the 32 PIDs after [base], the
 * highest bit for the first of them. The last bit says whether the next block (base + 0x20) exists.
 */
fun decodeSupported(base: Int, bytes: IntArray): Set<Int> = buildSet {
    for (i in 0 until 32) {
        val byte = bytes.getOrElse(i / 8) { 0 }
        if (((byte shr (7 - i % 8)) and 1) == 1) add(base + i + 1)
    }
}

/** What a mode 01 PID is called, for the diagnostics screen; null for one this app does not know. */
fun obdPidName(code: Int): String? = when (code) {
    0x01 -> "Monitor status since codes cleared"
    0x03 -> "Fuel system status"
    0x04 -> "Engine load"
    0x05 -> "Coolant temperature"
    0x06 -> "Short-term fuel trim"
    0x07 -> "Long-term fuel trim"
    0x08 -> "Short-term fuel trim, bank 2"
    0x09 -> "Long-term fuel trim, bank 2"
    0x0A -> "Fuel pressure"
    0x0B -> "Intake manifold pressure"
    0x0C -> "Engine speed"
    0x0D -> "Vehicle speed"
    0x0E -> "Timing advance"
    0x0F -> "Intake air temperature"
    0x10 -> "Air flow (MAF)"
    0x11 -> "Throttle position"
    0x12 -> "Secondary air status"
    0x13 -> "Oxygen sensors present"
    in 0x14..0x1B -> "Oxygen sensor ${code - 0x13} voltage"
    0x1C -> "OBD standard"
    0x1D -> "Oxygen sensors present (4 banks)"
    0x1E -> "Auxiliary input status"
    0x1F -> "Run time since start"
    0x21 -> "Distance with fault light on"
    0x22 -> "Fuel rail pressure (vacuum)"
    0x23 -> "Fuel rail pressure"
    in 0x24..0x2B -> "Oxygen sensor ${code - 0x23} lambda"
    0x2C -> "Commanded EGR"
    0x2D -> "EGR error"
    0x2E -> "Commanded evaporative purge"
    0x2F -> "Fuel level"
    0x30 -> "Warm-ups since codes cleared"
    0x31 -> "Distance since codes cleared"
    0x32 -> "Evaporative system vapour pressure"
    0x33 -> "Barometric pressure"
    in 0x34..0x3B -> "Oxygen sensor ${code - 0x33} lambda (current)"
    0x3C -> "Catalyst temperature, bank 1 sensor 1"
    0x3D -> "Catalyst temperature, bank 2 sensor 1"
    0x3E -> "Catalyst temperature, bank 1 sensor 2"
    0x3F -> "Catalyst temperature, bank 2 sensor 2"
    0x41 -> "Monitor status this drive cycle"
    0x42 -> "Control module voltage"
    0x43 -> "Absolute load"
    0x44 -> "Commanded air-fuel ratio (lambda)"
    0x45 -> "Relative throttle position"
    0x46 -> "Ambient air temperature"
    0x47 -> "Absolute throttle position B"
    0x48 -> "Absolute throttle position C"
    0x49 -> "Accelerator pedal position D"
    0x4A -> "Accelerator pedal position E"
    0x4B -> "Accelerator pedal position F"
    0x4C -> "Commanded throttle actuator"
    0x4D -> "Time with fault light on"
    0x4E -> "Time since codes cleared"
    0x51 -> "Fuel type"
    0x52 -> "Ethanol fuel"
    0x59 -> "Fuel rail absolute pressure"
    0x5A -> "Relative accelerator pedal position"
    0x5B -> "Hybrid battery remaining life"
    0x5C -> "Engine oil temperature"
    0x5D -> "Fuel injection timing"
    0x5E -> "Fuel rate"
    0x5F -> "Emission requirements"
    0x61 -> "Driver's demand torque"
    0x62 -> "Actual engine torque"
    0x63 -> "Engine reference torque"
    else -> null
}

/** The decoded value of [pid] in [response], or null when the car said "NO DATA" or anything unusable. */
fun parsePid(response: String, pid: Pid): Int? {
    val bytes = parsePidResponse(response, pid.code, pid.byteCount) ?: return null
    return pid.decode(bytes[0], bytes.getOrElse(1) { 0 })
}

private val VOLTAGE = Regex("""(?<![\d.])(\d{1,2}(?:\.\d{1,2})?)\s*V""", RegexOption.IGNORE_CASE)

/** The battery voltage from the adapter's answer to ATRV, for example "12.4V". */
fun parseVoltage(response: String): Float? = VOLTAGE.find(response)?.groupValues?.get(1)?.toFloatOrNull()
