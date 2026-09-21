package com.minhphan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerTest {
    private fun fix(seconds: Long, metersNorth: Double, speedKmh: Float = 40f, accuracy: Float? = 5f) =
        Fix(1_000_000 + seconds * 1000, LatLon(21.0 + metersNorth / 111_195.0, 105.8), accuracy, speedKmh)

    @Test
    fun addsUpTheDistanceBetweenFixes() {
        val odometer = Odometer()
        assertFalse(odometer.onFix(fix(0, 0.0))) // nothing to measure from yet
        assertTrue(odometer.onFix(fix(10, 100.0)))
        assertTrue(odometer.onFix(fix(20, 250.0)))

        assertEquals(0.25, odometer.totalKm, 0.001)
    }

    @Test
    fun startsFromWhereItWasLeft() {
        val odometer = Odometer(initialKm = 12.5)
        odometer.onFix(fix(0, 0.0))
        odometer.onFix(fix(10, 100.0))
        assertEquals(12.6, odometer.totalKm, 0.001)
    }

    @Test
    fun standingStillAddsNothingHoweverTheGpsWobbles() {
        val odometer = Odometer()
        odometer.onFix(fix(0, 0.0, speedKmh = 0f))
        odometer.onFix(fix(10, 15.0, speedKmh = 0.5f))
        assertEquals(0.0, odometer.totalKm, 0.0)
    }

    @Test
    fun aGlitchOrAnInaccurateFixAddsNothing() {
        val odometer = Odometer()
        odometer.onFix(fix(0, 0.0))
        odometer.onFix(fix(1, 5_000.0)) // 5 km in a second
        odometer.onFix(fix(2, 20.0, accuracy = 300f))
        assertEquals(0.0, odometer.totalKm, 0.0)
    }

    @Test
    fun aLongSilenceIsNotDrivenDistance() {
        val odometer = Odometer()
        odometer.onFix(fix(0, 0.0))
        odometer.onFix(fix(3_600, 20_000.0)) // an hour later and 20 km away: the GPS was off or the car was carried
        assertEquals(0.0, odometer.totalKm, 0.0)
        odometer.onFix(fix(3_610, 20_100.0)) // but from here on it counts again
        assertEquals(0.1, odometer.totalKm, 0.001)
    }

    @Test
    fun aRepeatedFixIsIgnored() {
        val odometer = Odometer()
        odometer.onFix(fix(0, 0.0))
        odometer.onFix(fix(10, 100.0))
        assertFalse(odometer.onFix(fix(10, 100.0)))
        assertEquals(0.1, odometer.totalKm, 0.001)
    }
}
