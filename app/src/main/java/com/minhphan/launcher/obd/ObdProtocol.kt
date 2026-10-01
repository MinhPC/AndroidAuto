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

    /** What is sent to ask for it ("010C"), made once instead of on every round. */
    val command: String = "01%02X".format(code)

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
    var start = 0
    while (start <= response.length) {
        val end = lineEnd(response, start)
        parseLine(response, start, end, code, byteCount)?.let { return it }
        start = end + 1
    }
    return null
}

/** The data bytes of every ECU that answered "01 <code>": more than one can reply, and their lines all count. */
fun parseAllPidResponses(response: String, code: Int, byteCount: Int): List<IntArray> {
    val answers = ArrayList<IntArray>(1)
    var start = 0
    while (start <= response.length) {
        val end = lineEnd(response, start)
        parseLine(response, start, end, code, byteCount)?.let { answers += it }
        start = end + 1
    }
    return answers
}

// Read on every answer, several times a second, so it walks the characters once and makes nothing but the result.

private fun lineEnd(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i] != '\r' && text[i] != '\n') i++
    return i
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'F' -> c - 'A' + 10
    in 'a'..'f' -> c - 'a' + 10
    else -> -1
}

/**
 * The [byteCount] data bytes of the line from [start] to [end] if it answers "01 <code>" (it reads "41", [code], then
 * the bytes in hex, spaces anywhere), or null. Anything after the bytes is ignored.
 */
private fun parseLine(text: String, start: Int, end: Int, code: Int, byteCount: Int): IntArray? {
    val wanted = 4 + byteCount * 2 // hex digits: "41", the code, the data
    var bytes: IntArray? = null
    var digits = 0
    var i = start
    while (i < end && digits < wanted) {
        val c = text[i++]
        if (c.isWhitespace()) continue
        val d = hexDigit(c)
        when (digits) {
            0 -> if (d != 4) return null
            1 -> if (d != 1) return null
            2 -> if (d != ((code shr 4) and 0xF)) return null
            3 -> if (d != (code and 0xF)) return null
            else -> {
                if (d < 0) return null
                val out = bytes ?: IntArray(byteCount).also { bytes = it }
                val k = (digits - 4) / 2
                out[k] = out[k] * 16 + d
            }
        }
        digits++
    }
    return if (digits == wanted) bytes ?: IntArray(0) else null
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

/** What a mode 01 PID is called, in Vietnamese, for the diagnostics screen; null for one this app does not know. */
fun obdPidName(code: Int): String? = when (code) {
    0x01 -> "Trạng thái kiểm tra từ khi xoá mã lỗi"
    0x03 -> "Trạng thái hệ thống nhiên liệu"
    0x04 -> "Tải động cơ"
    0x05 -> "Nhiệt độ nước làm mát"
    0x06 -> "Bù nhiên liệu ngắn hạn"
    0x07 -> "Bù nhiên liệu dài hạn"
    0x08 -> "Bù nhiên liệu ngắn hạn, dãy 2"
    0x09 -> "Bù nhiên liệu dài hạn, dãy 2"
    0x0A -> "Áp suất nhiên liệu"
    0x0B -> "Áp suất cổ hút"
    0x0C -> "Vòng tua máy"
    0x0D -> "Tốc độ xe"
    0x0E -> "Góc đánh lửa sớm"
    0x0F -> "Nhiệt độ khí nạp"
    0x10 -> "Lưu lượng khí nạp (MAF)"
    0x11 -> "Vị trí bướm ga"
    0x12 -> "Trạng thái khí phụ"
    0x13 -> "Cảm biến oxy có trên xe"
    in 0x14..0x1B -> "Điện áp cảm biến oxy ${code - 0x13}"
    0x1C -> "Chuẩn OBD"
    0x1D -> "Cảm biến oxy có trên xe (4 dãy)"
    0x1E -> "Trạng thái ngõ vào phụ"
    0x1F -> "Thời gian chạy từ lúc nổ máy"
    0x21 -> "Quãng đường khi đèn lỗi sáng"
    0x22 -> "Áp suất ống phân phối (chân không)"
    0x23 -> "Áp suất ống phân phối nhiên liệu"
    in 0x24..0x2B -> "Lambda cảm biến oxy ${code - 0x23}"
    0x2C -> "EGR theo lệnh"
    0x2D -> "Sai lệch EGR"
    0x2E -> "Xả hơi xăng theo lệnh"
    0x2F -> "Mức nhiên liệu"
    0x30 -> "Số lần hâm nóng từ khi xoá mã lỗi"
    0x31 -> "Quãng đường từ khi xoá mã lỗi"
    0x32 -> "Áp suất hơi hệ thống bay hơi"
    0x33 -> "Áp suất khí quyển"
    in 0x34..0x3B -> "Lambda cảm biến oxy ${code - 0x33} (dòng)"
    0x3C -> "Nhiệt độ bộ xúc tác, dãy 1 cảm biến 1"
    0x3D -> "Nhiệt độ bộ xúc tác, dãy 2 cảm biến 1"
    0x3E -> "Nhiệt độ bộ xúc tác, dãy 1 cảm biến 2"
    0x3F -> "Nhiệt độ bộ xúc tác, dãy 2 cảm biến 2"
    0x41 -> "Trạng thái kiểm tra chu kỳ lái này"
    0x42 -> "Điện áp hộp điều khiển"
    0x43 -> "Tải tuyệt đối"
    0x44 -> "Tỷ lệ khí/nhiên liệu theo lệnh (lambda)"
    0x45 -> "Vị trí bướm ga tương đối"
    0x46 -> "Nhiệt độ môi trường"
    0x47 -> "Vị trí bướm ga tuyệt đối B"
    0x48 -> "Vị trí bướm ga tuyệt đối C"
    0x49 -> "Vị trí bàn đạp ga D"
    0x4A -> "Vị trí bàn đạp ga E"
    0x4B -> "Vị trí bàn đạp ga F"
    0x4C -> "Bộ chấp hành bướm ga theo lệnh"
    0x4D -> "Thời gian đèn lỗi sáng"
    0x4E -> "Thời gian từ khi xoá mã lỗi"
    0x51 -> "Loại nhiên liệu"
    0x52 -> "Tỷ lệ ethanol"
    0x59 -> "Áp suất tuyệt đối ống phân phối"
    0x5A -> "Vị trí bàn đạp ga tương đối"
    0x5B -> "Dung lượng còn lại pin hybrid"
    0x5C -> "Nhiệt độ dầu máy"
    0x5D -> "Thời điểm phun nhiên liệu"
    0x5E -> "Lượng nhiên liệu tiêu thụ"
    0x5F -> "Tiêu chuẩn khí thải"
    0x61 -> "Mô-men yêu cầu của tài xế"
    0x62 -> "Mô-men thực của động cơ"
    0x63 -> "Mô-men tham chiếu của động cơ"
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
