package com.minhphan.launcher.obd

import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * An ELM327 that needs no adapter and no car: it answers like one, with a speed that rises and falls. It does
 * not know the fuel trim, like some cars, so that tile can be seen empty. Only debug builds offer it.
 */
class SimulatedElmLink : ElmLink {
    private val startedAt = System.currentTimeMillis()

    override suspend fun send(command: String, timeoutMs: Long): String {
        delay(40)
        val t = (System.currentTimeMillis() - startedAt) / 1000.0
        val speed = max(0.0, 50 + 45 * sin(t * 2 * PI / 40)).toInt()
        val rpm = 800 + speed * 28 + ((t * 7).toInt() % 40)
        return when (val c = command.filterNot { it.isWhitespace() }.uppercase()) {
            "ATZ" -> "ELM327 v1.5"
            "ATRV" -> "%.1fV".format(Locale.US, if (speed > 0) 14.2 else 12.6)
            "0100" -> "41 00 BE 3F A8 13"
            "010C" -> "41 0C " + hex(rpm * 4 / 256) + " " + hex(rpm * 4 % 256)
            "010D" -> "41 0D " + hex(speed)
            "0105" -> "41 05 " + hex(40 + 78 + (t / 4).toInt().coerceAtMost(14))
            "0104" -> "41 04 " + hex((30 + speed).coerceAtMost(100) * 255 / 100)
            "0111" -> "41 11 " + hex((10 + speed / 2).coerceAtMost(100) * 255 / 100)
            "010F" -> "41 0F " + hex(40 + 32 + (t / 6).toInt().coerceAtMost(8))
            "0107" -> "NO DATA"
            else -> if (c.startsWith("AT")) "OK" else "?"
        }
    }

    override fun close() = Unit

    private fun hex(value: Int) = "%02X".format(value and 0xFF)
}
