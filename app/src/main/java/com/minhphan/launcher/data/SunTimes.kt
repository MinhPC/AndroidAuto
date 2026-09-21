package com.minhphan.launcher.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

data class Coordinates(val latitude: Double, val longitude: Double)

/** Sunrise and sunset on one local date. Both are null when the sun does not cross the horizon that day. */
data class SunTimes(val sunrise: Instant?, val sunset: Instant?, val sunAlwaysUp: Boolean)

/** Sun's zenith angle at sunrise / sunset: 90° plus 50 arc-minutes for refraction and the sun's radius. */
private const val ZENITH_DEGREES = 90.833

/** Local hours used as "day" when there is no GPS position yet. */
private const val FALLBACK_DAY_START_HOUR = 6
private const val FALLBACK_DAY_END_HOUR = 18

private fun rad(degrees: Double) = Math.toRadians(degrees)

private fun deg(radians: Double) = Math.toDegrees(radians)

private fun wrap(value: Double, limit: Double) = ((value % limit) + limit) % limit

/**
 * Sunrise and sunset for [date] at [at], using the algorithm of the Almanac for Computers (1990), which is
 * accurate to a couple of minutes at the latitudes cars drive at. [date] is the local date in [zone].
 */
fun sunTimes(date: LocalDate, at: Coordinates, zone: java.time.ZoneId): SunTimes {
    val rising = solarEvent(date, at, zone, rising = true)
    val setting = solarEvent(date, at, zone, rising = false)
    // cos(H) outside -1..1 means the sun never rises or never sets on this day.
    val cosH = cosLocalHourAngle(date, at, rising = true)
    return SunTimes(sunrise = rising, sunset = setting, sunAlwaysUp = cosH < -1.0)
}

/** True between sunrise and sunset. Without a position it falls back to fixed hours, 06:00 to 18:00. */
fun isDaytime(now: ZonedDateTime, at: Coordinates?): Boolean {
    if (at == null) return now.hour in FALLBACK_DAY_START_HOUR until FALLBACK_DAY_END_HOUR
    val times = sunTimes(now.toLocalDate(), at, now.zone)
    if (times.sunAlwaysUp) return true
    val sunrise = times.sunrise ?: return false // polar night
    val sunset = times.sunset ?: return false
    val instant = now.toInstant()
    return !instant.isBefore(sunrise) && instant.isBefore(sunset)
}

private fun cosLocalHourAngle(date: LocalDate, at: Coordinates, rising: Boolean): Double {
    val t = approximateDay(date, at, rising)
    val (_, sinDec) = rightAscensionAndSinDec(t)
    val cosDec = cos(asin(sinDec))
    return (cos(rad(ZENITH_DEGREES)) - sinDec * sin(rad(at.latitude))) / (cosDec * cos(rad(at.latitude)))
}

private fun approximateDay(date: LocalDate, at: Coordinates, rising: Boolean): Double {
    val lngHour = at.longitude / 15.0
    return date.dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
}

/** Right ascension in hours and the sine of the declination of the sun at approximate day [t]. */
private fun rightAscensionAndSinDec(t: Double): Pair<Double, Double> {
    val meanAnomaly = 0.9856 * t - 3.289
    val trueLongitude = wrap(
        meanAnomaly + 1.916 * sin(rad(meanAnomaly)) + 0.020 * sin(rad(2 * meanAnomaly)) + 282.634,
        360.0,
    )
    var ra = wrap(deg(atan(0.91764 * tan(rad(trueLongitude)))), 360.0)
    // The arctangent loses the quadrant; put the right ascension back in the same quadrant as the longitude.
    ra += floor(trueLongitude / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
    return ra / 15.0 to 0.39782 * sin(rad(trueLongitude))
}

private fun solarEvent(date: LocalDate, at: Coordinates, zone: java.time.ZoneId, rising: Boolean): Instant? {
    val t = approximateDay(date, at, rising)
    val (ra, _) = rightAscensionAndSinDec(t)
    val cosH = cosLocalHourAngle(date, at, rising)
    if (cosH > 1.0 || cosH < -1.0) return null
    val hourAngle = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
    val localMeanTime = hourAngle + ra - 0.06571 * t - 6.622
    val utcHours = wrap(localMeanTime - at.longitude / 15.0, 24.0)
    // utcHours is only a time of day; of the neighbouring UTC days pick the one that lands on the local [date].
    val base = date.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofMillis((utcHours * 3_600_000).toLong()))
    return (-1L..1L)
        .map { base.plus(Duration.ofDays(it)) }
        .filter { it.atZone(zone).toLocalDate() == date }
        .minByOrNull { Duration.between(it, date.atTime(12, 0).atZone(zone).toInstant()).abs() }
}
