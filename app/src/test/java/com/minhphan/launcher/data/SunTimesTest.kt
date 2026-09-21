package com.minhphan.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SunTimesTest {
    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")
    private val hanoi = Coordinates(21.0285, 105.8542)
    private val danang = Coordinates(16.0544, 108.2022)
    private val saigon = Coordinates(10.8231, 106.6297)

    private fun minutesOfDay(instant: java.time.Instant?): Double {
        val local = instant!!.atZone(vietnam)
        return local.hour * 60.0 + local.minute + local.second / 60.0
    }

    // Reference values come from the NOAA solar calculator equations, a different formulation than the code's.
    private fun assertSun(day: LocalDate, at: Coordinates, sunrise: Double, sunset: Double) {
        val times = sunTimes(day, at, vietnam)
        assertEquals("sunrise on $day", sunrise, minutesOfDay(times.sunrise), 4.0)
        assertEquals("sunset on $day", sunset, minutesOfDay(times.sunset), 4.0)
    }

    @Test
    fun hanoiAcrossTheYear() {
        assertSun(LocalDate.of(2026, 9, 21), hanoi, 345.1, 1074.2)
        assertSun(LocalDate.of(2026, 6, 21), hanoi, 316.0, 1120.7)
        assertSun(LocalDate.of(2026, 12, 21), hanoi, 388.8, 1040.2)
    }

    @Test
    fun otherCities() {
        assertSun(LocalDate.of(2026, 9, 21), danang, 336.1, 1064.5)
        assertSun(LocalDate.of(2026, 9, 21), saigon, 342.8, 1070.4)
        assertSun(LocalDate.of(2026, 12, 21), saigon, 366.6, 1056.2)
    }

    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, 21, hour, minute, 0, 0, vietnam)

    @Test
    fun daytimeFollowsTheSunAtTheCarsPosition() {
        // Hanoi on 21 Sep: sunrise about 05:45, sunset about 17:54.
        assertFalse(isDaytime(at(5, 0), hanoi))
        assertTrue(isDaytime(at(6, 30), hanoi))
        assertTrue(isDaytime(at(12), hanoi))
        assertTrue(isDaytime(at(17, 30), hanoi))
        assertFalse(isDaytime(at(18, 30), hanoi))
        assertFalse(isDaytime(at(0, 5), hanoi))
    }

    @Test
    fun withoutAPositionItUsesFixedHours() {
        assertFalse(isDaytime(at(5, 59), null))
        assertTrue(isDaytime(at(6, 0), null))
        assertTrue(isDaytime(at(17, 59), null))
        assertFalse(isDaytime(at(18, 0), null))
    }

    @Test
    fun polarDayAndNightDoNotCrash() {
        val tromso = Coordinates(69.65, 18.96)
        val oslo = ZoneId.of("Europe/Oslo")
        assertTrue(isDaytime(ZonedDateTime.of(2026, 6, 21, 2, 0, 0, 0, oslo), tromso))
        assertFalse(isDaytime(ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, oslo), tromso))
    }
}
