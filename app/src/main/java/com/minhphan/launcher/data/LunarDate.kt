package com.minhphan.launcher.data

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * A day of the Vietnamese lunar calendar: [day] of [month] (1 to 12, [leap] for the month repeated in a leap year) of
 * [year], the lunar year, which starts at Tết and so lags the solar one for its first weeks.
 */
data class LunarDate(val day: Int, val month: Int, val year: Int, val leap: Boolean) {
    /** The year's name in the sixty-year cycle, "Bính Ngọ" for 2026. */
    val yearName: String get() = STEMS[(year + 6) % 10] + " " + BRANCHES[(year + 8) % 12]

    companion object {
        private val STEMS = listOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")
        private val BRANCHES = listOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")

        /**
         * The lunar date of [date], as the Vietnamese calendar reckons it: months start on the day of the new moon in
         * Vietnam's time (UTC+7), the eleventh month is the one with the winter solstice, and a year of thirteen months
         * repeats the first month without a major solar term. Hồ Ngọc Đức's astronomical method.
         */
        fun of(date: LocalDate): LunarDate {
            val dayNumber = date.toEpochDay() + EPOCH_JD
            val k = floor((dayNumber - 2415021.076998695) / SYNODIC_MONTH).toInt()
            var monthStart = newMoonDay(k + 1)
            if (monthStart > dayNumber) monthStart = newMoonDay(k)
            var a11 = lunarMonth11(date.year)
            var b11 = a11
            var year: Int
            if (a11 >= monthStart) {
                year = date.year
                a11 = lunarMonth11(date.year - 1)
            } else {
                year = date.year + 1
                b11 = lunarMonth11(date.year + 1)
            }
            val day = (dayNumber - monthStart + 1).toInt()
            val diff = floor((monthStart - a11) / 29.0).toInt()
            var leap = false
            var month = diff + 11
            if (b11 - a11 > 365) {
                val leapDiff = leapMonthOffset(a11)
                if (diff >= leapDiff) {
                    month = diff + 10
                    leap = diff == leapDiff
                }
            }
            if (month > 12) month -= 12
            if (month >= 11 && diff < 4) year -= 1
            return LunarDate(day, month, year, leap)
        }

        // The Julian day number of 1970-01-01, so a LocalDate's epoch day gives its Julian day number.
        private const val EPOCH_JD = 2440588L
        private const val SYNODIC_MONTH = 29.530588853
        private const val TIME_ZONE = 7.0

        /** The Julian day number of the day (in Vietnam) of the [k]th new moon after the one of 1900-01-01. */
        private fun newMoonDay(k: Int): Long {
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
            c1 = c1 - 0.4068 * sin(mpr * dr) + 0.0161 * sin(dr * 2 * mpr)
            c1 -= 0.0004 * sin(dr * 3 * mpr)
            c1 = c1 + 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr))
            c1 = c1 - 0.0074 * sin(dr * (m - mpr)) + 0.0004 * sin(dr * (2 * f + m))
            c1 = c1 - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mpr))
            c1 = c1 + 0.0010 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
            val deltaT = if (t < -11) {
                0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
            } else {
                -0.000278 + 0.000265 * t + 0.000262 * t2
            }
            return floor(jd1 + c1 - deltaT + 0.5 + TIME_ZONE / 24).toLong()
        }

        /** Which of the twelve 30° sectors the sun stands in at the start of the day [jdn] in Vietnam, 0 at the spring equinox. */
        private fun sunSector(jdn: Long): Int {
            val t = (jdn - 2451545.5 - TIME_ZONE / 24) / 36525
            val t2 = t * t
            val dr = PI / 180
            val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
            val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
            var dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m)
            dl += (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.000290 * sin(dr * 3 * m)
            var l = (l0 + dl) * dr
            l -= 2 * PI * floor(l / (2 * PI))
            return floor(l / PI * 6).toInt()
        }

        /** The first day of the eleventh lunar month (the one with the winter solstice) that starts in solar [year]. */
        private fun lunarMonth11(year: Int): Long {
            val off = LocalDate.of(year, 12, 31).toEpochDay() + EPOCH_JD - 2415021
            val k = floor(off / SYNODIC_MONTH).toInt()
            val nm = newMoonDay(k)
            return if (sunSector(nm) >= 9) newMoonDay(k - 1) else nm
        }

        /** How many months after the eleventh month starting on [a11] the leap month comes, in a thirteen-month year. */
        private fun leapMonthOffset(a11: Long): Int {
            val k = floor((a11 - 2415021.076998695) / SYNODIC_MONTH + 0.5).toInt()
            var i = 1
            var arc = sunSector(newMoonDay(k + i))
            var last: Int
            do {
                last = arc
                i++
                arc = sunSector(newMoonDay(k + i))
            } while (arc != last && i < 14)
            return i - 1
        }
    }
}
