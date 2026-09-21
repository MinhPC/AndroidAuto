package com.minhphan.trip

/** A trip the driver started by hand: when, and where the [Odometer] stood. */
data class ManualTrip(val startedAt: Long, val startKm: Double) {
    fun resultAt(now: Long, odometerKm: Double) = TripResult(
        km = (odometerKm - startKm).coerceAtLeast(0.0),
        seconds = ((now - startedAt) / 1000).coerceAtLeast(0),
        endedAt = now,
    )
}

/** What a manual trip came to. */
data class TripResult(val km: Double, val seconds: Long, val endedAt: Long) {
    /** The average speed over the whole time, standing still included, as a trip computer shows it. */
    val avgKmh: Double get() = if (seconds > 0) km / (seconds / 3600.0) else 0.0
}

/** The trip computer: at most one trip running, and what the last one came to. */
data class TripMeter(val running: ManualTrip? = null, val last: TripResult? = null) {
    fun start(now: Long, odometerKm: Double): TripMeter =
        if (running != null) this else copy(running = ManualTrip(now, odometerKm))

    fun stop(now: Long, odometerKm: Double): TripMeter =
        running?.let { copy(running = null, last = it.resultAt(now, odometerKm)) } ?: this
}
