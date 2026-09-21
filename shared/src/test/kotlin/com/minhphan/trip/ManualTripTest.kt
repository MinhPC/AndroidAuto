package com.minhphan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ManualTripTest {
    @Test
    fun aTripCountsFromWhereTheOdometerStoodWhenItStarted() {
        val meter = TripMeter().start(now = 10_000, odometerKm = 100.0)
        assertNotNull(meter.running)

        val done = meter.stop(now = 10_000 + 30 * 60_000, odometerKm = 115.0)

        assertNull(done.running)
        val result = done.last!!
        assertEquals(15.0, result.km, 0.001)
        assertEquals(1800, result.seconds)
        assertEquals(30.0, result.avgKmh, 0.001)
    }

    @Test
    fun startingWhileRunningOrStoppingWhileIdleChangesNothing() {
        val running = TripMeter().start(1_000, 5.0)
        assertSame(running, running.start(2_000, 9.0))
        val idle = TripMeter()
        assertSame(idle, idle.stop(2_000, 9.0))
    }

    @Test
    fun theNextTripReplacesTheLastResultOnlyWhenItEnds() {
        val first = TripMeter().start(0, 0.0).stop(60_000, 1.0)
        val second = first.start(120_000, 1.0)
        assertEquals(1.0, second.last!!.km, 0.0)
        assertEquals(3.0, second.stop(180_000, 4.0).last!!.km, 0.0)
    }

    @Test
    fun aClockThatWentBackOrAnOdometerThatDidNotMoveNeverGivesNegatives() {
        val result = ManualTrip(startedAt = 10_000, startKm = 5.0).resultAt(now = 5_000, odometerKm = 4.0)
        assertEquals(0.0, result.km, 0.0)
        assertEquals(0, result.seconds)
        assertEquals(0.0, result.avgKmh, 0.0)
    }
}
