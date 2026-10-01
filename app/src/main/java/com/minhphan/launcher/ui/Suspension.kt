package com.minhphan.launcher.ui

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How the car's body moves on the road, for the scene: a small model of a car's suspension, so the body does not
 * bob to a beat, which the eye reads as fake at once.
 *
 * The road under each rear wheel is rough in no pattern ([roadNoise] along the distance driven, never repeating),
 * and the body sits on springs and dampers that let through only its slow swell: it rises and settles about 1.3
 * times a second, more over a rough stretch and less over a smooth one. The two wheels meet different bumps, so the
 * body also rolls a little. Speeding up squats the tail and braking lifts it. Over that, the tyres hum: a fine
 * tremble that grows with the speed. Standing still, all of it comes to rest.
 *
 * [bob] is how far the body is pushed down (positive) or lifted, as a fraction of the car's visible width, [roll]
 * how far it leans, in degrees clockwise, and [braking] whether the driver is on the brakes, from how fast the car
 * slows. [step] moves it on by one frame; it allocates nothing.
 */
class Suspension {
    var bob = 0f
        private set
    var roll = 0f
        private set

    /** How far the car has been driven, in metres; it runs on through [reset], so the road does not jump back. */
    var distance = 0.0
        private set
    private var height = 0f // body, fraction of the car's width
    private var heightSpeed = 0f
    private var lean = 0f // degrees
    private var leanSpeed = 0f
    private var lastSpeed = Float.NaN

    /** How fast the car is speeding up (negative: slowing down), in m/s², smoothed over a quarter of a second. */
    var acceleration = 0f
        private set

    /** Moves the body on by [seconds] at [speedKmh]. */
    fun step(speedKmh: Float, seconds: Float) {
        val dt = seconds.coerceIn(0f, MAX_FRAME_SECONDS)
        if (dt == 0f) return
        val speed = speedKmh / 3.6f // m/s
        // The acceleration from the change of speed, smoothed: the speed comes from the GPS in steps.
        val rawAcceleration = if (lastSpeed.isNaN()) 0f else (speed - lastSpeed) / dt
        lastSpeed = speed
        acceleration += (rawAcceleration - acceleration) * min(1f, dt / ACCELERATION_SMOOTHING_SECONDS)
        distance += speed * dt

        // A rougher ride the faster the car goes, up to highway speed.
        val rough = sqrt((speed / FULL_ROUGHNESS_MPS).coerceIn(0f, 1f))
        val left = roadNoise(distance, LEFT_SEED)
        val right = roadNoise(distance, RIGHT_SEED)
        // Where the springs would bring the body to rest over this bit of road: the mean of the two wheels, and the
        // squat or lift of the speeding up or braking.
        val heightTarget = (left + right) / 2f * ROAD_AMPLITUDE * rough + acceleration * SQUAT_PER_MPS2
        val leanTarget = (left - right) / 2f * ROLL_AMPLITUDE_DEGREES * rough

        // Springs and dampers, in steps short enough to stay stable on a slow frame.
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, SUBSTEP_SECONDS)
            heightSpeed += (BODY_OMEGA * BODY_OMEGA * (heightTarget - height) - 2f * BODY_DAMPING * BODY_OMEGA * heightSpeed) * h
            height += heightSpeed * h
            leanSpeed += (ROLL_OMEGA * ROLL_OMEGA * (leanTarget - lean) - 2f * ROLL_DAMPING * ROLL_OMEGA * leanSpeed) * h
            lean += leanSpeed * h
            remaining -= h
        }

        // The tyres' hum goes straight through, too quick for the springs to smooth.
        val hum = valueNoise(distance / HUM_WAVELENGTH_M, HUM_SEED) * HUM_AMPLITUDE * rough
        bob = height + hum
        roll = lean

        // On the brakes when slowing harder than the engine alone slows the car (letting off the throttle is about
        // 0.5 m/s²), off again once it eases off; two thresholds so it does not flicker at the edge.
        braking = if (braking) acceleration < -BRAKE_OFF_MPS2 else acceleration < -BRAKE_ON_MPS2
    }

    /** Whether the car is slowing as it does on the brakes, so the brake lights are lit. */
    var braking = false
        private set

    /** Back to rest at once, for when the scene stops animating. */
    fun reset() {
        bob = 0f
        roll = 0f
        height = 0f
        heightSpeed = 0f
        lean = 0f
        leanSpeed = 0f
        lastSpeed = Float.NaN
        acceleration = 0f
        braking = false
    }

    private companion object {
        const val MAX_FRAME_SECONDS = 0.1f
        const val SUBSTEP_SECONDS = 1f / 120f
        const val ACCELERATION_SMOOTHING_SECONDS = 0.25f
        const val FULL_ROUGHNESS_MPS = 20f // 72 km/h
        const val BRAKE_ON_MPS2 = 1.3f
        const val BRAKE_OFF_MPS2 = 0.6f

        // The body: a car's springs let it swell about 1.3 times a second, and the dampers stop it within a swell or two.
        val BODY_OMEGA = (2 * PI * 1.3).toFloat()
        const val BODY_DAMPING = 0.3f
        val ROLL_OMEGA = (2 * PI * 1.6).toFloat()
        const val ROLL_DAMPING = 0.35f

        // How much the road moves the body: about two pixels at most on a head unit, a quarter of a degree of lean.
        const val ROAD_AMPLITUDE = 0.006f
        const val ROLL_AMPLITUDE_DEGREES = 0.25f
        const val SQUAT_PER_MPS2 = 0.0015f
        const val HUM_AMPLITUDE = 0.0006f
        const val HUM_WAVELENGTH_M = 0.6

        const val LEFT_SEED = 11
        const val RIGHT_SEED = 29
        const val HUM_SEED = 53
    }
}

/**
 * The height of the road at [metres] along it, about -1 to 1: long swells with shorter bumps on them (6 m, 2.5 m and
 * 0.9 m), from value noise, so it never repeats. [seed] gives each wheel its own road.
 */
internal fun roadNoise(metres: Double, seed: Int): Float =
    (valueNoise(metres / 6.0, seed) + 0.5f * valueNoise(metres / 2.5, seed + 1) + 0.25f * valueNoise(metres / 0.9, seed + 2)) / 1.75f

/** Smooth noise along a line, about -1 to 1: a random height at each whole number, eased between them. */
internal fun valueNoise(x: Double, seed: Int): Float {
    val i = floor(x)
    val f = (x - i).toFloat()
    val t = f * f * (3f - 2f * f)
    val a = lattice(i.toLong(), seed)
    val b = lattice(i.toLong() + 1, seed)
    return a + (b - a) * t
}

/** A fixed random number from -1 to 1 for each whole number [i] and [seed]. */
private fun lattice(i: Long, seed: Int): Float {
    var h = i * 0x9E3779B97F4A7C15uL.toLong() + seed * 0xBF58476D1CE4E5B9uL.toLong()
    h = (h xor (h ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
    h = (h xor (h ushr 27)) * 0x94D049BB133111EBuL.toLong()
    h = h xor (h ushr 31)
    return ((h ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0).toFloat()
}
