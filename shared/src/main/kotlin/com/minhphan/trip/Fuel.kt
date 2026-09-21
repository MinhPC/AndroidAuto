package com.minhphan.trip

import kotlin.math.roundToLong

/**
 * One fill-up. Consumption is only known for a fill-up that topped the tank up ([full]) and had a full one before
 * it: [distanceKm] is what was driven between the two and [litersInPeriod] all the fuel put in since (earlier
 * part fills included), so [kmPerLiter] is the distance over the fuel the engine actually burnt.
 */
data class Refuel(
    val id: String,
    val at: Long,
    val liters: Double,
    val amountVnd: Long,
    val full: Boolean,
    val distanceKm: Double? = null,
    val litersInPeriod: Double? = null,
) {
    val pricePerLiter: Long get() = if (liters > 0) (amountVnd / liters).roundToLong() else 0L

    val kmPerLiter: Double? get() =
        if (distanceKm != null && litersInPeriod != null && litersInPeriod > 0) distanceKm / litersInPeriod else null
}

/**
 * How much fuel is thought to be in the tank. The car does not say (its fuel level is not on OBD), so it is worked
 * out: the tank was full at the last full fill-up, and the kilometres since then burnt fuel at the usual economy.
 * [assumed] is true while there is no measured economy yet and a typical one stands in for it.
 */
data class FuelEstimate(
    val liters: Double,
    val rangeKm: Double,
    val percent: Int,
    val kmPerLiter: Double,
    val assumed: Boolean,
)

/** What a small petrol car does in mixed driving, used until the driver's own figure is known. */
const val DEFAULT_KM_PER_LITER = 12.0

/**
 * What the car remembers about fuel between fill-ups. The fuel gauge is not readable over OBD on this car, so the
 * economy is worked out the way drivers do on paper: fill to the top, drive, fill to the top again, and divide
 * the kilometres by the litres. Kilometres come from the [Odometer] unless the driver corrects them.
 */
data class FuelBook(
    /** Where the odometer stood at the last full fill-up; null until there has been one. */
    val baselineKm: Double? = null,
    /** Fuel put in by part fill-ups since then. */
    val partialLiters: Double = 0.0,
    /** Distance and fuel of every finished period; their ratio is the average economy. */
    val trackedKm: Double = 0.0,
    val trackedLiters: Double = 0.0,
    val last: Refuel? = null,
) {
    val averageKmPerLiter: Double? get() = if (trackedLiters > 0 && trackedKm > 0) trackedKm / trackedLiters else null

    /**
     * The fuel thought to be left after the kilometres driven since the last full fill-up, for a tank of
     * [tankLiters]; null when there has been no full fill-up to start from. Part fill-ups since then add their fuel.
     */
    fun estimate(odometerKm: Double, tankLiters: Double): FuelEstimate? {
        val from = baselineKm ?: return null
        if (tankLiters <= 0) return null
        val economy = averageKmPerLiter ?: DEFAULT_KM_PER_LITER
        val driven = (odometerKm - from).coerceAtLeast(0.0)
        val liters = (tankLiters - driven / economy + partialLiters).coerceIn(0.0, tankLiters)
        return FuelEstimate(
            liters = liters,
            rangeKm = liters * economy,
            percent = Math.round(liters / tankLiters * 100).toInt(),
            kmPerLiter = economy,
            assumed = averageKmPerLiter == null,
        )
    }

    /** The distance since the last full fill-up, as far as the odometer knows; null when there is none to measure from. */
    fun suggestedKm(odometerKm: Double): Double? = baselineKm?.let { odometerKm - it }?.takeIf { it > 0 }

    /**
     * Records a fill-up. [distanceKm] is what the driver typed, if anything; otherwise the odometer's is used.
     * A part fill-up only adds to the fuel of the period; a full one closes it and starts the next.
     */
    fun record(at: Long, liters: Double, amountVnd: Long, full: Boolean, odometerKm: Double, distanceKm: Double? = null): FuelBook {
        if (!full) {
            val refuel = Refuel(at.toString(), at, liters, amountVnd, full = false)
            // Before the first full fill-up there is no period yet to add the fuel to.
            return copy(partialLiters = if (baselineKm != null) partialLiters + liters else 0.0, last = refuel)
        }
        val km = if (baselineKm == null) null else (distanceKm ?: suggestedKm(odometerKm))?.takeIf { it > 0 }
        val period = if (km != null) partialLiters + liters else null
        return copy(
            baselineKm = odometerKm,
            partialLiters = 0.0,
            trackedKm = trackedKm + (km ?: 0.0),
            trackedLiters = trackedLiters + (period ?: 0.0),
            last = Refuel(at.toString(), at, liters, amountVnd, full = true, distanceKm = km, litersInPeriod = period),
        )
    }
}
