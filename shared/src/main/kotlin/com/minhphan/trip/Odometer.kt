package com.minhphan.trip

/**
 * Adds up the distance the car drives, from the same GPS fixes the [TripTracker] gets and with the same rules: a
 * fix that is not accurate enough, a jump faster than a car can go, and a fix taken while standing still add
 * nothing. The total only grows; a manual trip and a fill-up read it and remember where it was.
 * Not thread safe.
 */
class Odometer(initialKm: Double = 0.0, private val config: TripConfig = TripConfig()) {
    var totalKm: Double = initialKm
        private set

    private var last: Fix? = null

    /** Adds the distance from the previous fix to [fix]; true when the total grew. */
    fun onFix(fix: Fix): Boolean {
        if (fix.accuracyM != null && fix.accuracyM > config.maxAccuracyM) return false
        val previous = last
        if (previous != null && fix.timeMs <= previous.timeMs) return false // a repeat or a late one
        last = fix
        if (previous == null) return false
        val dtMs = fix.timeMs - previous.timeMs
        if (dtMs > config.silenceEndsTripMs || fix.speedKmh < config.stopSpeedKmh) return false
        val meters = distanceMeters(previous.position, fix.position)
        if (meters / (dtMs / 1000.0) > config.maxPlausibleSpeedMs) return false
        totalKm += meters / 1000.0
        return true
    }
}
