package com.minhphan.launcher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStateTest {
    @Test
    fun parkedCarStartsMovingOnlyAtFourKmh() {
        assertFalse(nextMoving(wasMoving = false, speedKmh = 0f))
        assertFalse(nextMoving(wasMoving = false, speedKmh = 3.9f))
        assertTrue(nextMoving(wasMoving = false, speedKmh = 4f))
    }

    @Test
    fun movingCarKeepsMovingUntilBelowTwoKmh() {
        assertTrue(nextMoving(wasMoving = true, speedKmh = 30f))
        assertTrue(nextMoving(wasMoving = true, speedKmh = 2.5f))
        assertFalse(nextMoving(wasMoving = true, speedKmh = 2f))
        assertFalse(nextMoving(wasMoving = true, speedKmh = 0f))
    }

    @Test
    fun gpsJitterWhileParkedNeverStartsTheCar() {
        var moving = false
        for (jitter in listOf(0.5f, 1.8f, 2.6f, 3.2f, 1.1f, 3.9f)) moving = nextMoving(moving, jitter)
        assertFalse(moving)
    }

    @Test
    fun slowingThroughTheBandKeepsTheCarMovingUntilItReallyStops() {
        var moving = true
        val results = listOf(6f, 3.5f, 2.4f, 1.9f).map { speed -> nextMoving(moving, speed).also { moving = it } }
        assertTrue(results.take(3).all { it })
        assertFalse(results.last())
    }
}
