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
    ;

    /** The value in its display unit (rpm, km/h, °C or %), from the data bytes [a] and [b] of the answer. */
    fun decode(a: Int, b: Int): Int = when (this) {
        Rpm -> (a * 256 + b) / 4
        Speed -> a
        Coolant, Intake -> a - 40
        Load, Throttle -> Math.round(a * 100f / 255f)
        FuelTrim -> Math.round((a - 128) * 100f / 128f)
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
) {
    fun with(pid: Pid, value: Int?): ObdValues = when (pid) {
        Pid.Rpm -> copy(rpm = value)
        Pid.Speed -> copy(speedKmh = value)
        Pid.Coolant -> copy(coolantC = value)
        Pid.Intake -> copy(intakeC = value)
        Pid.Load -> copy(loadPercent = value)
        Pid.Throttle -> copy(throttlePercent = value)
        Pid.FuelTrim -> copy(fuelTrimPercent = value)
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

/** The decoded value of [pid] in [response], or null when the car said "NO DATA" or anything unusable. */
fun parsePid(response: String, pid: Pid): Int? {
    val bytes = parsePidResponse(response, pid.code, pid.byteCount) ?: return null
    return pid.decode(bytes[0], bytes.getOrElse(1) { 0 })
}

private val VOLTAGE = Regex("""(?<![\d.])(\d{1,2}(?:\.\d{1,2})?)\s*V""", RegexOption.IGNORE_CASE)

/** The battery voltage from the adapter's answer to ATRV, for example "12.4V". */
fun parseVoltage(response: String): Float? = VOLTAGE.find(response)?.groupValues?.get(1)?.toFloatOrNull()
