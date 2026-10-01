package com.minhphan.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlightsTest {
    @Test
    fun dayBrightnessIsNotDimmed() {
        assertFalse(isDimmed(brightness = 255, dayLevel = 255))
    }

    @Test
    fun dimmedForTheLightsCountsAsLightsOn() {
        assertTrue(isDimmed(brightness = 102, dayLevel = 255))
    }

    @Test
    fun smallChangeByHandDoesNotCount() {
        assertFalse(isDimmed(brightness = 220, dayLevel = 255))
    }

    @Test
    fun threeQuartersOfDayLevelIsDimmed() {
        assertTrue(isDimmed(brightness = 150, dayLevel = 200))
        assertFalse(isDimmed(brightness = 151, dayLevel = 200))
    }

    @Test
    fun nothingIsDimmedBeforeDayLevelIsKnown() {
        assertFalse(isDimmed(brightness = 0, dayLevel = 0))
    }

    @Test
    fun dayLevelIsTheBrightestSeen() {
        assertEquals(255, learnDayLevel(dayLevel = 100, brightness = 255))
        assertEquals(255, learnDayLevel(dayLevel = 255, brightness = 100))
    }
}
