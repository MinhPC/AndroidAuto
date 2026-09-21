package com.minhphan.trip

data class LatLon(val lat: Double, val lon: Double)

/** What the engine reports at one moment; null where the car does not say. */
data class EngineData(
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val intakeC: Int? = null,
    val loadPercent: Int? = null,
    val throttlePercent: Int? = null,
    /** Long-term fuel trim: how far the engine computer has had to shift the fuel it injects, in % (negative: less). */
    val fuelTrimPercent: Int? = null,
    val voltage: Float? = null,
)

/** One point of the route: where the car was, how fast it went and what the engine said. */
data class TripPoint(val timeMs: Long, val position: LatLon, val speedKmh: Float, val engine: EngineData = EngineData())

/** A trip as the phone lists it. While [ongoing] it is still growing; when it is not, it is final. */
data class TripSummary(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val ongoing: Boolean,
    /** The local date the trip started, "yyyy-MM-dd" in the time zone of the car. */
    val day: String,
    val distanceKm: Double,
    val movingSeconds: Long,
    val maxSpeedKmh: Float,
    val start: LatLon,
    val end: LatLon,
    val maxRpm: Int? = null,
    val maxCoolantC: Int? = null,
    val maxIntakeC: Int? = null,
    val minVoltage: Float? = null,
    val maxVoltage: Float? = null,
    val pointCount: Int = 0,
) {
    /** The average speed while moving, not counting the time spent standing still. */
    val avgSpeedKmh: Float get() = if (movingSeconds > 0) (distanceKm / (movingSeconds / 3600.0)).toFloat() else 0f
}

/** Everything driven on one local day: what the phone adds up into weeks, months and years. */
data class DayTotal(
    val day: String,
    val distanceKm: Double = 0.0,
    val trips: Int = 0,
    val movingSeconds: Long = 0,
    val maxSpeedKmh: Float = 0f,
)

/** Where the car is now and whether it is driving; the phone's "where is my car". */
data class LiveStatus(
    val position: LatLon,
    val speedKmh: Float,
    val moving: Boolean,
    val updatedAt: Long,
    val engine: EngineData = EngineData(),
    /** How much fuel is thought to be left; null until the driver has filled the tank up once. */
    val fuel: FuelEstimate? = null,
)
