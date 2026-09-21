package com.minhphan.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LunarCalendarTest {
    @Test
    fun `matches the reference mock-up for 7 August 2026`() {
        val lunar = LunarCalendar.of(LocalDate.of(2026, 8, 7))
        assertEquals(25, lunar.day)
        assertEquals(6, lunar.month)
        assertFalse(lunar.isLeapMonth)
        assertEquals("Bính Ngọ", lunar.yearName)
        assertEquals("Ất Mùi", lunar.monthName)
        assertEquals("Quý Sửu", lunar.dayName)
    }

    @Test
    fun `tet is the first day of the first month`() {
        for ((date, year) in listOf(
            LocalDate.of(2024, 2, 10) to "Giáp Thìn",
            LocalDate.of(2025, 1, 29) to "Ất Tỵ",
            LocalDate.of(2026, 2, 17) to "Bính Ngọ",
        )) {
            val lunar = LunarCalendar.of(date)
            assertEquals("$date", 1, lunar.day)
            assertEquals("$date", 1, lunar.month)
            assertEquals("$date", year, lunar.yearName)
        }
    }

    @Test
    fun `day before tet belongs to the previous year`() {
        val lunar = LunarCalendar.of(LocalDate.of(2026, 2, 16))
        assertEquals(12, lunar.month)
        assertEquals("Ất Tỵ", lunar.yearName)
    }

    @Test
    fun `finds the leap month of 2025`() {
        // 2025 has a leap 6th month from 25 July; the regular 6th month starts on 25 June, month 7 on 23 August.
        val leap = LunarCalendar.of(LocalDate.of(2025, 7, 25))
        assertEquals(1, leap.day)
        assertEquals(6, leap.month)
        assertTrue(leap.isLeapMonth)
        assertFalse(LunarCalendar.of(LocalDate.of(2025, 6, 25)).isLeapMonth)
        val next = LunarCalendar.of(LocalDate.of(2025, 8, 23))
        assertEquals(7, next.month)
        assertFalse(next.isLeapMonth)
    }

    @Test
    fun `day name repeats every sixty days`() {
        val a = LunarCalendar.of(LocalDate.of(2026, 1, 1))
        val b = LunarCalendar.of(LocalDate.of(2026, 1, 1).plusDays(60))
        assertEquals(a.dayName, b.dayName)
    }
}
