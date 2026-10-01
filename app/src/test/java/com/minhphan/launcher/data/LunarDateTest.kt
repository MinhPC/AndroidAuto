package com.minhphan.launcher.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LunarDateTest {
    private fun lunar(y: Int, m: Int, d: Int) = LunarDate.of(LocalDate.of(y, m, d))

    @Test
    fun tetFallsOnTheFirstDayOfTheFirstMonth() {
        assertEquals(LunarDate(1, 1, 2024, false), lunar(2024, 2, 10))
        assertEquals(LunarDate(1, 1, 2025, false), lunar(2025, 1, 29))
        assertEquals(LunarDate(1, 1, 2026, false), lunar(2026, 2, 17))
    }

    @Test
    fun theDaysBeforeTetBelongToTheOldYear() {
        assertEquals(LunarDate(30, 12, 2023, false), lunar(2024, 2, 9))
        assertEquals(LunarDate(29, 12, 2024, false), lunar(2025, 1, 28))
    }

    @Test
    fun midAutumn() {
        assertEquals(LunarDate(15, 8, 2024, false), lunar(2024, 9, 17))
        assertEquals(LunarDate(15, 8, 2025, false), lunar(2025, 10, 6))
    }

    @Test
    fun theLeapSixthMonthOf2025() {
        assertEquals(LunarDate(30, 6, 2025, false), lunar(2025, 7, 24))
        assertEquals(LunarDate(1, 6, 2025, true), lunar(2025, 7, 25))
        assertEquals(LunarDate(1, 7, 2025, false), lunar(2025, 8, 23))
    }

    @Test
    fun yearNames() {
        assertEquals("Giáp Thìn", LunarDate(1, 1, 2024, false).yearName)
        assertEquals("Ất Tỵ", LunarDate(1, 1, 2025, false).yearName)
        assertEquals("Bính Ngọ", LunarDate(1, 1, 2026, false).yearName)
    }
}
