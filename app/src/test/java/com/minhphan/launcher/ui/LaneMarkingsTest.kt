package com.minhphan.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneMarkingsTest {
    private fun dashes(phase: Float, length: Float = 3f): List<Pair<Float, Float>> =
        buildList { forEachDash(phase, length) { near, far -> add(near to far) } }

    @Test
    fun theRoadComesOnFasterCloseBy() {
        assertEquals(IMAGE_HEIGHT, laneY(3f), 0.01f)
        // A metre close to the car covers far more of the picture than a metre far off.
        assertTrue(laneY(3f) - laneY(4f) > 10 * (laneY(30f) - laneY(31f)))
    }

    @Test
    fun drivingAMetreBringsEveryDashAMetreCloser() {
        val before = dashes(2f)
        val after = dashes(3f)
        // The nearest may have gone under the bottom of the picture; the rest are the same dashes a metre closer.
        val farBefore = before.map { it.second }
        after.map { it.second }.forEach { far -> assertTrue("$far", farBefore.any { kotlin.math.abs(it - 1f - far) < 1e-4f }) }
    }

    @Test
    fun dashesAreAPeriodApartAndInThePicture() {
        val list = dashes(4f)
        assertTrue(list.size >= 5)
        list.zipWithNext().drop(1).forEach { (a, b) -> assertEquals(DASH_PERIOD_M, b.first - a.first, 1e-4f) }
        list.forEach { (near, far) -> assertTrue(near >= 3f && far > near && near < 60f) }
    }

    @Test
    fun theLoopWrapsWithoutAJump() {
        // Just before the phase wraps and just after, the dashes stand where they did.
        val end = dashes(DASH_PERIOD_M - 1e-4f).map { it.second }
        val start = dashes(0f).map { it.second }
        start.forEach { far -> assertTrue("$far", end.any { kotlin.math.abs(it - far) < 1e-3f }) }
    }
}
