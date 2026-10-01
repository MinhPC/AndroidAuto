package com.minhphan.launcher.data

import android.content.Context
import android.content.SharedPreferences
import com.minhphan.trip.DriveClock
import com.minhphan.trip.Fix
import com.minhphan.trip.Odometer
import com.minhphan.trip.TripEvent
import com.minhphan.trip.decodeDrives
import com.minhphan.trip.decodeOpenDrive
import com.minhphan.trip.encodeDrives
import com.minhphan.trip.encodeOpenDrive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the car remembers about its drives: the [Odometer] (fed by the cloud recorder with every GPS fix) and the
 * [DriveClock], the time on the move drive by drive, which the trip screen shows. All of it survives a restart, in
 * the "drive_log" preferences. The recorder writes from its own thread and the screen from the main one, so the
 * changes are serialised; the flows can be read from anywhere.
 */
class DriveLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive_log", Context.MODE_PRIVATE)
    private val odometer = Odometer(prefs.getDouble(TOTAL_KM) ?: 0.0)
    private var savedKm = odometer.totalKm
    private var openTrip: String? = prefs.getString(OPEN_TRIP, null) // "account/trip"

    private val _totalKm = MutableStateFlow(odometer.totalKm)

    /** Kilometres driven since the log began, as far as the GPS saw. */
    val totalKm: StateFlow<Double> = _totalKm

    private val _drives = MutableStateFlow(
        DriveClock(decodeOpenDrive(prefs.getString(OPEN_DRIVE, null)), decodeDrives(prefs.getString(DRIVES, null))),
    )

    /** The drive in progress and the ones before it, newest first. */
    val drives: StateFlow<DriveClock> = _drives
    private var openSavedAt = 0L

    @Synchronized
    fun onFix(fix: Fix) {
        if (!odometer.onFix(fix)) return
        _totalKm.value = odometer.totalKm
        // Every 50 m or so, not every fix: writing preferences a second is wasteful and losing 50 m is nothing.
        if (odometer.totalKm - savedKm >= SAVE_EVERY_KM) saveTotal()
    }

    /** Writes the odometer and the drive in progress now; the recorder calls it when it stops. */
    @Synchronized
    fun flush() {
        saveTotal()
        prefs.edit().putStringOrRemove(OPEN_DRIVE, encodeOpenDrive(_drives.value.current)).apply()
    }

    /** Whether the car is moving now, every second or two while a journey runs; see [DriveClock.onReading]. */
    @Synchronized
    fun onDriveReading(now: Long, moving: Boolean) = updateDrives(_drives.value.onReading(now, moving), now)

    /** The driver starts a journey. */
    @Synchronized
    fun startDrive(now: Long) = updateDrives(_drives.value.start(now), now)

    /** The driver ends the journey in progress. */
    @Synchronized
    fun endDrive(now: Long) = updateDrives(_drives.value.endNow(now), now)

    private fun updateDrives(next: DriveClock, now: Long) {
        val before = _drives.value
        if (next == before) return
        _drives.value = next
        val edit = prefs.edit()
        if (next.history !== before.history) edit.putString(DRIVES, encodeDrives(next.history))
        // The journey in progress changes with every reading; written when it starts or ends and every so often
        // between, so a head unit that loses power with the car loses at most that much of it.
        val startedOrEnded = (next.current == null) != (before.current == null)
        if (startedOrEnded || next.history !== before.history || now - openSavedAt >= SAVE_OPEN_DRIVE_MS) {
            edit.putStringOrRemove(OPEN_DRIVE, encodeOpenDrive(next.current))
            openSavedAt = now
        }
        edit.apply()
    }

    /**
     * Remembers which trip is open, from what the tracker sends. If the head unit is switched off with the car the
     * process is killed without a word, and the trip would stay "ongoing" in Firebase for ever; the next start
     * asks for it with [takeOpenTrip] and closes it.
     */
    @Synchronized
    fun noteTripEvents(events: List<TripEvent>, uid: String) {
        val before = openTrip
        for (event in events) {
            if (event !is TripEvent.Save) continue
            val key = "$uid/${event.summary.id}"
            if (event.summary.ongoing) openTrip = key else if (openTrip == key) openTrip = null
        }
        if (openTrip != before) prefs.edit().putStringOrRemove(OPEN_TRIP, openTrip).apply()
    }

    /** The account and id of the trip that was still open when the last run ended without saying so, if any. */
    @Synchronized
    fun openTrip(): Pair<String, String>? = openTrip?.let { it.substringBefore('/') to it.substringAfter('/') }

    /** Forgets the open trip: it has been closed. */
    @Synchronized
    fun clearOpenTrip() {
        openTrip = null
        prefs.edit().remove(OPEN_TRIP).apply()
    }

    private fun saveTotal() {
        savedKm = odometer.totalKm
        prefs.edit().putDouble(TOTAL_KM, savedKm).apply()
    }

    private companion object {
        const val SAVE_EVERY_KM = 0.05

        const val OPEN_TRIP = "open_trip_id"

        const val TOTAL_KM = "total_km"
        const val DRIVES = "drives"
        const val OPEN_DRIVE = "open_drive"
        const val SAVE_OPEN_DRIVE_MS = 30_000L
    }
}

// Preferences have no Double: one is kept as its bits, and a missing one is a missing key.
private fun SharedPreferences.getDouble(key: String): Double? =
    if (contains(key)) Double.fromBits(getLong(key, 0)) else null

private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor =
    putLong(key, value.toRawBits())

private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?): SharedPreferences.Editor =
    if (value == null) remove(key) else putString(key, value)
