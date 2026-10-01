package com.minhphan.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCadenceTest {
    @Test fun updatesHaveEqualSpacingAtEveryDisplayRate() {
        for (hz in listOf(60f, 90f, 120f, 144f)) {
            for (target in listOf(30, 60)) {
                val cadence = FrameCadence(hz, target)
                val frames = (1..144).filter { cadence.shouldDraw() }
                assertEquals(setOf(framesPerUpdate(hz, target)), frames.zipWithNext { a, b -> b - a }.toSet())
            }
        }
    }
    @Test fun smoothModeUsesEvenDisplayDivisors() {
        assertEquals(1, framesPerUpdate(60.000004f, 60))
        assertEquals(2, framesPerUpdate(90f, 60))
        assertEquals(2, framesPerUpdate(120f, 60))
        assertEquals(3, framesPerUpdate(144f, 60))
    }

    @Test fun savingModeCapsUpdatesAtThirty() {
        for (hz in listOf(60f, 90f, 120f, 144f)) {
            val cadence = FrameCadence(hz, 30)
            val rendered = (1..hz.toInt()).count { cadence.shouldDraw() }
            assertEquals((hz / framesPerUpdate(hz, 30)).toInt(), rendered)
        }
    }

    @Test fun invalidRatesFallBackToSixty() {
        assertEquals(2, framesPerUpdate(Float.NaN, 30))
        assertEquals(2, framesPerUpdate(0f, 30))
    }
}
