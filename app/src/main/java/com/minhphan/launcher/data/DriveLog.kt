package com.minhphan.launcher.data

import android.content.Context
import android.content.SharedPreferences
import com.minhphan.trip.Fix
import com.minhphan.trip.FuelBook
import com.minhphan.trip.ManualTrip
import com.minhphan.trip.Odometer
import com.minhphan.trip.Refuel
import com.minhphan.trip.TripMeter
import com.minhphan.trip.TripResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the car remembers about how far it has gone and what it has drunk: the [Odometer] (fed by the trip recorder
 * with every GPS fix), the trip the driver started by hand, and the fuel book. All of it survives a restart, in the
 * "drive_log" preferences. The recorder writes from its own thread and the screen from the main one, so the
 * changes are serialised; the flows can be read from anywhere.
 */
class DriveLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive_log", Context.MODE_PRIVATE)
    private val odometer = Odometer(prefs.getDouble(TOTAL_KM) ?: 0.0)
    private var savedKm = odometer.totalKm

    private val _totalKm = MutableStateFlow(odometer.totalKm)

    /** Kilometres driven since the log began, as far as the GPS saw. */
    val totalKm: StateFlow<Double> = _totalKm

    private val _trip = MutableStateFlow(readTrip())
    val trip: StateFlow<TripMeter> = _trip

    private val _fuel = MutableStateFlow(readFuel())
    val fuel: StateFlow<FuelBook> = _fuel

    @Synchronized
    fun onFix(fix: Fix) {
        if (!odometer.onFix(fix)) return
        _totalKm.value = odometer.totalKm
        // Every 50 m or so, not every fix: writing preferences a second is wasteful and losing 50 m is nothing.
        if (odometer.totalKm - savedKm >= SAVE_EVERY_KM) saveTotal()
    }

    /** Writes the odometer now; the recorder calls it when it stops. */
    @Synchronized
    fun flush() = saveTotal()

    @Synchronized
    fun startTrip(now: Long) {
        _trip.value = _trip.value.start(now, odometer.totalKm)
        saveTrip()
    }

    @Synchronized
    fun stopTrip(now: Long) {
        _trip.value = _trip.value.stop(now, odometer.totalKm)
        saveTrip()
    }

    /** The distance since the last full fill-up that is offered as the default in the fill-up form. */
    @Synchronized
    fun suggestedKm(): Double? = _fuel.value.suggestedKm(odometer.totalKm)

    /** Records a fill-up and returns it as stored. [distanceKm] is the driver's correction, if any. */
    @Synchronized
    fun recordRefuel(now: Long, liters: Double, amountVnd: Long, full: Boolean, distanceKm: Double?): Refuel {
        val book = _fuel.value.record(now, liters, amountVnd, full, odometer.totalKm, distanceKm)
        _fuel.value = book
        saveFuel()
        saveTotal()
        return book.last!!
    }

    private fun saveTotal() {
        savedKm = odometer.totalKm
        prefs.edit().putDouble(TOTAL_KM, savedKm).apply()
    }

    private fun saveTrip() {
        val trip = _trip.value
        prefs.edit()
            .putLongOrRemove(RUN_STARTED_AT, trip.running?.startedAt)
            .putDoubleOrRemove(RUN_START_KM, trip.running?.startKm)
            .putDoubleOrRemove(LAST_KM, trip.last?.km)
            .putLongOrRemove(LAST_SECONDS, trip.last?.seconds)
            .putLongOrRemove(LAST_ENDED_AT, trip.last?.endedAt)
            .apply()
    }

    private fun saveFuel() {
        val book = _fuel.value
        val last = book.last
        prefs.edit()
            .putDoubleOrRemove(BASELINE_KM, book.baselineKm)
            .putDouble(PARTIAL_LITERS, book.partialLiters)
            .putDouble(TRACKED_KM, book.trackedKm)
            .putDouble(TRACKED_LITERS, book.trackedLiters)
            .putLongOrRemove(REFUEL_AT, last?.at)
            .putDoubleOrRemove(REFUEL_LITERS, last?.liters)
            .putLongOrRemove(REFUEL_AMOUNT, last?.amountVnd)
            .putBooleanOrRemove(REFUEL_FULL, last?.full)
            .putDoubleOrRemove(REFUEL_DISTANCE, last?.distanceKm)
            .putDoubleOrRemove(REFUEL_PERIOD_LITERS, last?.litersInPeriod)
            .apply()
    }

    private fun readTrip(): TripMeter {
        val startedAt = prefs.getLongOrNull(RUN_STARTED_AT)
        val startKm = prefs.getDouble(RUN_START_KM)
        val km = prefs.getDouble(LAST_KM)
        val seconds = prefs.getLongOrNull(LAST_SECONDS)
        val endedAt = prefs.getLongOrNull(LAST_ENDED_AT)
        return TripMeter(
            running = if (startedAt != null && startKm != null) ManualTrip(startedAt, startKm) else null,
            last = if (km != null && seconds != null && endedAt != null) TripResult(km, seconds, endedAt) else null,
        )
    }

    private fun readFuel(): FuelBook {
        val at = prefs.getLongOrNull(REFUEL_AT)
        val liters = prefs.getDouble(REFUEL_LITERS)
        val last = if (at != null && liters != null) {
            Refuel(
                id = at.toString(),
                at = at,
                liters = liters,
                amountVnd = prefs.getLongOrNull(REFUEL_AMOUNT) ?: 0,
                full = prefs.getBooleanOrNull(REFUEL_FULL) ?: false,
                distanceKm = prefs.getDouble(REFUEL_DISTANCE),
                litersInPeriod = prefs.getDouble(REFUEL_PERIOD_LITERS),
            )
        } else {
            null
        }
        return FuelBook(
            baselineKm = prefs.getDouble(BASELINE_KM),
            partialLiters = prefs.getDouble(PARTIAL_LITERS) ?: 0.0,
            trackedKm = prefs.getDouble(TRACKED_KM) ?: 0.0,
            trackedLiters = prefs.getDouble(TRACKED_LITERS) ?: 0.0,
            last = last,
        )
    }

    private companion object {
        const val SAVE_EVERY_KM = 0.05

        const val TOTAL_KM = "total_km"
        const val RUN_STARTED_AT = "run_started_at"
        const val RUN_START_KM = "run_start_km"
        const val LAST_KM = "last_trip_km"
        const val LAST_SECONDS = "last_trip_seconds"
        const val LAST_ENDED_AT = "last_trip_ended_at"
        const val BASELINE_KM = "fuel_baseline_km"
        const val PARTIAL_LITERS = "fuel_partial_liters"
        const val TRACKED_KM = "fuel_tracked_km"
        const val TRACKED_LITERS = "fuel_tracked_liters"
        const val REFUEL_AT = "refuel_at"
        const val REFUEL_LITERS = "refuel_liters"
        const val REFUEL_AMOUNT = "refuel_amount_vnd"
        const val REFUEL_FULL = "refuel_full"
        const val REFUEL_DISTANCE = "refuel_distance_km"
        const val REFUEL_PERIOD_LITERS = "refuel_period_liters"
    }
}

// Preferences have no Double and no "not set" for the others: a number is kept as its bits, and a missing one is a missing key.
private fun SharedPreferences.getDouble(key: String): Double? =
    if (contains(key)) Double.fromBits(getLong(key, 0)) else null

private fun SharedPreferences.getLongOrNull(key: String): Long? = if (contains(key)) getLong(key, 0) else null

private fun SharedPreferences.getBooleanOrNull(key: String): Boolean? = if (contains(key)) getBoolean(key, false) else null

private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor =
    putLong(key, value.toRawBits())

private fun SharedPreferences.Editor.putDoubleOrRemove(key: String, value: Double?): SharedPreferences.Editor =
    if (value == null) remove(key) else putDouble(key, value)

private fun SharedPreferences.Editor.putLongOrRemove(key: String, value: Long?): SharedPreferences.Editor =
    if (value == null) remove(key) else putLong(key, value)

private fun SharedPreferences.Editor.putBooleanOrRemove(key: String, value: Boolean?): SharedPreferences.Editor =
    if (value == null) remove(key) else putBoolean(key, value)
