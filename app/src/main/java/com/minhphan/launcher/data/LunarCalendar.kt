package com.minhphan.launcher.data

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** A day of the Vietnamese lunar calendar together with the can-chi names shown on the home screen. */
data class LunarDate(
    val day: Int,
    val month: Int,
    val isLeapMonth: Boolean,
    val yearName: String,
    val monthName: String,
    val dayName: String,
)

private val CAN = listOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")
private val CHI = listOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")

// Vietnam is UTC+7, and its lunar calendar is computed for that meridian (not China's UTC+8).
private const val VIETNAM_TIME_ZONE = 7.0

/**
 * Solar to lunar conversion using Hồ Ngọc Đức's astronomical algorithm (new moon and sun longitude for UTC+7),
 * valid for the years 1200-2199. [LunarCalendar.of] never throws for dates in that range.
 */
object LunarCalendar {
    fun of(date: LocalDate): LunarDate {
        val (day, month, year, leap) = solarToLunar(date.dayOfMonth, date.monthValue, date.year)
        val jd = julianDay(date.dayOfMonth, date.monthValue, date.year)
        // Every leap month keeps the name of the month before it, so the name only depends on the number.
        val monthCan = (year * 12 + month + 3).mod(10)
        return LunarDate(
            day = day,
            month = month,
            isLeapMonth = leap,
            yearName = "${CAN[(year + 6).mod(10)]} ${CHI[(year + 8).mod(12)]}",
            monthName = "${CAN[monthCan]} ${CHI[(month + 1).mod(12)]}",
            dayName = "${CAN[(jd + 9).mod(10)]} ${CHI[(jd + 1).mod(12)]}",
        )
    }

    private data class Lunar(val day: Int, val month: Int, val year: Int, val leap: Boolean)

    private fun julianDay(dd: Int, mm: Int, yy: Int): Int {
        val a = (14 - mm) / 12
        val y = yy + 4800 - a
        val m = mm + 12 * a - 3
        val jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        return if (jd < 2299161) dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083 else jd
    }

    /** Julian day number of the k-th new moon (counted from 1900-01-01) in local time. */
    private fun newMoonDay(k: Int): Int {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        var jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd1 += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c1 -= 0.4068 * sin(mpr * dr) - 0.0161 * sin(dr * 2 * mpr)
        c1 -= 0.0004 * sin(dr * 3 * mpr)
        c1 += 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr))
        c1 -= 0.0074 * sin(dr * (m - mpr)) - 0.0004 * sin(dr * (2 * f + m))
        c1 -= 0.0004 * sin(dr * (2 * f - m)) + 0.0006 * sin(dr * (2 * f + mpr))
        c1 += 0.0010 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
        val deltaT = if (t < -11) {
            0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        } else {
            -0.000278 + 0.000265 * t + 0.000262 * t2
        }
        return floor(jd1 + c1 - deltaT + 0.5 + VIETNAM_TIME_ZONE / 24).toInt()
    }

    /** Sun longitude in 30-degree steps (0..11) at local midnight of the given Julian day. */
    private fun sunLongitude(jdn: Int): Int {
        val t = (jdn - 2451545.5 - VIETNAM_TIME_ZONE / 24) / 36525
        val t2 = t * t
        val dr = PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m)
        dl += (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.000290 * sin(dr * 3 * m)
        var l = (l0 + dl) * dr
        l -= PI * 2 * floor(l / (PI * 2))
        return floor(l / PI * 6).toInt()
    }

    /** Start of the lunar month that contains the winter solstice (always month 11) of the given solar year. */
    private fun lunarMonth11(yy: Int): Int {
        val off = julianDay(31, 12, yy) - 2415021
        val k = floor(off / 29.530588853).toInt()
        val nm = newMoonDay(k)
        return if (sunLongitude(nm) >= 9) newMoonDay(k - 1) else nm
    }

    /** Offset (in months after month 11) of the leap month in a 13-month lunar year. */
    private fun leapMonthOffset(a11: Int): Int {
        val k = floor((a11 - 2415021.076998695) / 29.530588853 + 0.5).toInt()
        var i = 1
        var arc = sunLongitude(newMoonDay(k + i))
        var last: Int
        do {
            last = arc
            i++
            arc = sunLongitude(newMoonDay(k + i))
        } while (arc != last && i < 14)
        return i - 1
    }

    private fun solarToLunar(dd: Int, mm: Int, yy: Int): Lunar {
        val dayNumber = julianDay(dd, mm, yy)
        val k = floor((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = newMoonDay(k + 1)
        if (monthStart > dayNumber) monthStart = newMoonDay(k)
        var a11 = lunarMonth11(yy)
        var b11 = a11
        var lunarYear: Int
        if (a11 >= monthStart) {
            lunarYear = yy
            a11 = lunarMonth11(yy - 1)
        } else {
            lunarYear = yy + 1
            b11 = lunarMonth11(yy + 1)
        }
        val diff = floor((monthStart - a11) / 29.0).toInt()
        var leap = false
        var lunarMonth = diff + 11
        if (b11 - a11 > 365) {
            val leapDiff = leapMonthOffset(a11)
            if (diff >= leapDiff) {
                lunarMonth = diff + 10
                if (diff == leapDiff) leap = true
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1
        return Lunar(dayNumber - monthStart + 1, lunarMonth, lunarYear, leap)
    }
}
