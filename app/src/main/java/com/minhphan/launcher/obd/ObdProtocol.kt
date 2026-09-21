package com.minhphan.launcher.obd

/** The standard OBD-II "mode 01" values shown on Home, with the byte layout of each answer. */
enum class Pid(val code: Int, val byteCount: Int) {
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
    ;

    /** The value in its display unit (rpm, km/h, °C, kPa, g/s or %), from the data bytes [a] and [b] of the answer. */
    fun decode(a: Int, b: Int): Int = when (this) {
        Rpm -> (a * 256 + b) / 4
        Speed, Map, Baro -> a
        Coolant, Intake, Ambient, Oil -> a - 40
        Load, Throttle, FuelLevel -> Math.round(a * 100f / 255f)
        FuelTrim, ShortTrim -> Math.round((a - 128) * 100f / 128f)
        Timing -> Math.round(a / 2f - 64f)
        Maf -> Math.round((a * 256 + b) / 100f)
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
    0x04 -> "Engine load"
    0x05 -> "Coolant temperature"
    0x06 -> "Short-term fuel trim"
    0x07 -> "Long-term fuel trim"
    0x0A -> "Fuel pressure"
    0x0B -> "Intake manifold pressure"
    0x0C -> "Engine speed"
    0x0D -> "Vehicle speed"
    0x0E -> "Timing advance"
    0x0F -> "Intake air temperature"
    0x10 -> "Air flow (MAF)"
    0x11 -> "Throttle position"
    0x1F -> "Run time since start"
    0x21 -> "Distance with fault light on"
    0x2F -> "Fuel level"
    0x31 -> "Distance since codes cleared"
    0x33 -> "Barometric pressure"
    0x42 -> "Control module voltage"
    0x43 -> "Absolute load"
    0x45 -> "Relative throttle position"
    0x46 -> "Ambient air temperature"
    0x5C -> "Engine oil temperature"
    0x5E -> "Fuel rate"
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
