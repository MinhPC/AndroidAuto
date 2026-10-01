package com.minhphan.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SuspensionTest {
    private val frame = 1f / 60f

    /** The body's bob, frame by frame, driving at [kmh] for [seconds]. */
    private fun Suspension.drive(kmh: Float, seconds: Float): FloatArray =
        FloatArray((seconds / frame).toInt()) { step(kmh, frame); bob }

    @Test
    fun standingStillTheBodyStaysAtRest() {
        val body = Suspension()
        repeat(600) { body.step(0f, frame) }
        assertEquals(0f, body.bob, 0f)
        assertEquals(0f, body.roll, 0f)
    }

    @Test
    fun drivingTheBodyMovesAndLeansButOnlyALittle() {
        val body = Suspension()
        var mostRoll = 0f
        val bobs = FloatArray(3600) { body.step(72f, frame); mostRoll = maxOf(mostRoll, abs(body.roll)); body.bob }
        val most = bobs.maxOf { abs(it) }
        assertTrue("the body moves: $most", most > 0.001f)
        assertTrue("a couple of pixels at most on a head unit: $most", most < 0.015f)
        assertTrue("it leans: $mostRoll", mostRoll > 0.01f)
        assertTrue("a fraction of a degree: $mostRoll", mostRoll < 1f)
    }

    @Test
    fun theBobDoesNotRepeatToABeat() {
        // At 72 km/h the lane dashes come round every 0.83 s; the old bob repeated with them. This one must not: the
        // bob a dash period later is not the same bob again.
        val bobs = Suspension().apply { drive(72f, 5f) }.drive(72f, 20f)
        val lag = (0.83f / frame).toInt()
        val n = bobs.size - lag
        val mean = bobs.average().toFloat()
        var together = 0.0
        var alone = 0.0
        for (i in 0 until n) {
            together += (bobs[i] - mean).toDouble() * (bobs[i + lag] - mean)
            alone += (bobs[i] - mean).toDouble() * (bobs[i] - mean)
        }
        val correlation = together / alone
        assertTrue("a dash period later the bob is another: $correlation", correlation < 0.5)
    }

    @Test
    fun theBodySwellsSlowlyNotToEveryBump() {
        // Springs and dampers: the body turns about a couple of times a second, not at every frame.
        val bobs = Suspension().apply { drive(72f, 5f) }.drive(72f, 20f)
        val smooth = FloatArray(bobs.size - 1) { bobs[it + 1] - bobs[it] }
        var turns = 0
        for (i in 1 until smooth.size) if (smooth[i - 1] * smooth[i] < 0 && abs(smooth[i]) > 1e-5f) turns++
        assertTrue("turns a second: ${turns / 20f}", turns / 20f < 30f)
    }

    @Test
    fun brakingLiftsTheTailAndSpeedingUpSquatsIt() {
        val braking = Suspension().apply { drive(60f, 3f) }
        var lowest = 0f
        // From 60 km/h to 10 km/h in two seconds, about 7 m/s².
        for (i in 0 until 120) {
            braking.step(60f - 50f * i / 120f, frame)
            lowest = minOf(lowest, braking.bob)
        }
        assertTrue("the tail lifts: $lowest", lowest < -0.004f)

        val speeding = Suspension().apply { drive(10f, 3f) }
        var highest = 0f
        for (i in 0 until 120) {
            speeding.step(10f + 50f * i / 120f, frame)
            highest = maxOf(highest, speeding.bob)
        }
        assertTrue("the tail squats: $highest", highest > 0.004f)
    }

    @Test
    fun theBrakeLightsComeOnWhenBrakingButNotWhenLettingOffOrCruising() {
        val cruising = Suspension()
        cruising.drive(80f, 5f)
        assertFalse(cruising.braking)

        // Letting off the throttle: 80 to 72 km/h over four seconds, about 0.55 m/s².
        val coasting = Suspension().apply { drive(80f, 3f) }
        var lit = false
        for (i in 0 until 240) {
            coasting.step(80f - 8f * i / 240f, frame)
            lit = lit || coasting.braking
        }
        assertFalse("coasting is not braking", lit)

        // Braking: 80 to 30 km/h over three seconds, about 4.6 m/s², then holding 30.
        val braking = Suspension().apply { drive(80f, 3f) }
        for (i in 0 until 180) braking.step(80f - 50f * i / 180f, frame)
        assertTrue(braking.braking)
        braking.drive(30f, 2f)
        assertFalse("off again once the car holds its speed", braking.braking)
    }

    @Test
    fun aSlowFrameDoesNotThrowTheBodyAbout() {
        val body = Suspension().apply { drive(72f, 5f) }
        body.step(72f, 2f) // the head unit stalled for two seconds
        assertTrue(abs(body.bob) < 0.02f)
    }

    @Test
    fun resetPutsTheBodyAtRest() {
        val body = Suspension().apply { drive(72f, 3f) }
        body.reset()
        assertEquals(0f, body.bob, 0f)
        assertEquals(0f, body.roll, 0f)
    }

    @Test
    fun theRoadIsSmoothAndStaysInItsRange() {
        var previous = roadNoise(0.0, 11)
        var i = 1
        while (i < 100_000) {
            val metres = i * 0.01
            val height = roadNoise(metres, 11)
            assertTrue(height in -1f..1f)
            assertTrue("no step at $metres m", abs(height - previous) < 0.05f)
            previous = height
            i++
        }
        // Each wheel has its own road.
        val apart = (0 until 1000).map { roadNoise(it * 0.37, 11) - roadNoise(it * 0.37, 29) }
        assertTrue(sqrt(apart.sumOf { (it * it).toDouble() } / apart.size) > 0.1)
    }
}
