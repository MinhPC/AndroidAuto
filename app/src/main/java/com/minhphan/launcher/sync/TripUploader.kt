package com.minhphan.launcher.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.minhphan.cloud.CloudAccount
import com.minhphan.trip.FuelEstimate
import com.minhphan.trip.Refuel
import com.minhphan.trip.Schema
import com.minhphan.trip.TripEvent
import com.minhphan.trip.toMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Writes what the [com.minhphan.trip.TripTracker] produces to the signed-in user's part of Firestore. Firestore keeps
 * writes made without a connection and sends them when the car is online again, even after the app has been
 * restarted, so a trip in a car park without signal is not lost. Call it from one thread at a time.
 *
 * Day totals are sent as increments, which the server adds up: a total is never replaced by a smaller number,
 * whatever this device has forgotten (cleared data, a reinstall) and however many devices write.
 */
class TripUploader(
    context: Context,
    private val account: CloudAccount,
    private val status: SyncStatus,
    /** How much fuel is thought to be left now; sent with the live position. */
    private val fuelEstimate: () -> FuelEstimate? = { null },
) {
    private val db: FirebaseFirestore? =
        if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseFirestore.getInstance()

    private val cleanupPrefs = context.applicationContext.getSharedPreferences("trip_cleanup", Context.MODE_PRIVATE)

    /** The highest speed already sent for each day: the server cannot take a maximum, so only a higher one is sent. */
    private val sentMaxSpeed = HashMap<String, Float>()

    /** Sends what the tracker produced; returns the account it went to, or null when nothing was sent (no account, no Firebase). */
    fun handle(events: List<TripEvent>): String? {
        val db = db ?: return null
        val uid = account.uid ?: return null
        val user = db.collection(Schema.USERS).document(uid)
        for (event in events) {
            when (event) {
                is TripEvent.Save -> user.collection(Schema.TRIPS).document(event.summary.id).write(event.summary.toMap())
                is TripEvent.Route -> user.collection(Schema.TRIPS).document(event.tripId)
                    .collection(Schema.CHUNKS).document(Schema.chunkId(event.seq))
                    .write(mapOf(Schema.POINTS to event.points.map { it.toMap() }))
                is TripEvent.DayChange -> {
                    val total = mutableMapOf<String, Any>(
                        "distanceKm" to FieldValue.increment(event.km),
                        "trips" to FieldValue.increment(event.newTrips.toLong()),
                        "movingSeconds" to FieldValue.increment(event.movingSeconds),
                    )
                    val key = "$uid/${event.day}"
                    if (event.maxSpeedKmh > (sentMaxSpeed[key] ?: 0f)) {
                        sentMaxSpeed[key] = event.maxSpeedKmh
                        total["maxSpeedKmh"] = event.maxSpeedKmh
                    }
                    user.collection(Schema.DAYS).document(event.day).track { set(total, SetOptions.merge()) }
                }
                is TripEvent.Live -> user.collection(Schema.LIVE).document(Schema.LIVE_DOC).write(event.status.copy(fuel = fuelEstimate()).toMap())
            }
        }
        return uid
    }

    /**
     * Deletes the route detail ([Schema.CHUNKS], the GPS point every 5 seconds) of trips older than
     * [RETENTION_DAYS], the way a dashcam loops over its oldest footage. The trip itself (its distance, times, max
     * OBD readings) is a few hundred bytes and is kept forever; the route chunks are what actually grow towards
     * Firestore's 1 GiB free-plan quota (even driving around the clock every day, [RETENTION_DAYS] of chunks stays
     * a few hundred MB). Runs at most once a day and only a batch of trips at a time, so an account with years of
     * history does not delete thousands of chunks - and spend as many writes - in one go; it catches up over the
     * following days instead.
     */
    suspend fun cleanupOldRoutes(now: Long) {
        val db = db ?: return
        val uid = account.uid ?: return
        if (now - cleanupPrefs.getLong(KEY_LAST_RUN, 0) < CLEANUP_EVERY_MS) return
        val cutoff = now - RETENTION_DAYS * DAY_MS
        val cleanedBefore = cleanupPrefs.getLong(KEY_CLEANED_BEFORE, 0)
        // A failure here (no network, a refused request) is not urgent: KEY_LAST_RUN still moves on, so it is
        // simply tried again a day from now instead of hammering Firestore while the car is offline.
        try {
            if (cleanedBefore < cutoff) {
                val trips = db.collection(Schema.USERS).document(uid).collection(Schema.TRIPS)
                    .whereGreaterThanOrEqualTo("startedAt", cleanedBefore)
                    .whereLessThan("startedAt", cutoff)
                    .orderBy("startedAt")
                    .limit(CLEANUP_BATCH)
                    .get().await()
                var lastStartedAt = cleanedBefore
                for (trip in trips.documents) {
                    deleteChunks(db, trip.reference)
                    lastStartedAt = trip.getLong("startedAt") ?: lastStartedAt
                }
                val caughtUp = trips.size() < CLEANUP_BATCH
                cleanupPrefs.edit().putLong(KEY_CLEANED_BEFORE, if (caughtUp) cutoff else lastStartedAt + 1).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean up old routes", e)
        }
        cleanupPrefs.edit().putLong(KEY_LAST_RUN, now).apply()
    }

    private suspend fun deleteChunks(db: FirebaseFirestore, trip: DocumentReference) {
        val chunks = trip.collection(Schema.CHUNKS).get().await()
        for (group in chunks.documents.chunked(400)) {
            val batch = db.batch()
            for (doc in group) batch.delete(doc.reference)
            batch.commit().await()
        }
    }

    /**
     * Sends the fuel estimate alone, merged into the live document, so that it shows up at once after a fill-up
     * instead of with the next position. Nothing is sent while nobody is signed in.
     */
    fun saveFuel(estimate: FuelEstimate?) {
        val db = db ?: return
        val uid = account.uid ?: return
        estimate ?: return
        val fields = mapOf("fuel" to estimate.toMap())
        db.collection(Schema.USERS).document(uid).collection(Schema.LIVE).document(Schema.LIVE_DOC).track { set(fields, SetOptions.merge()) }
    }

    /**
     * Hands a fill-up to Firestore, which keeps it if there is no connection and sends it later; true when it was
     * handed over, false while nobody is signed in (the caller keeps it and tries again). Sending it twice is harmless.
     */
    fun saveRefuel(refuel: Refuel): Boolean {
        val db = db ?: return false
        val uid = account.uid ?: return false
        db.collection(Schema.USERS).document(uid).collection(Schema.REFUELS).document(refuel.id).write(refuel.toMap())
        return true
    }

    /**
     * Marks a trip as over that a run of the app left "ongoing" when it was killed; true when the write was handed to
     * Firestore. It is only done for the account the trip belongs to ([uid]), so no other account gets a stub of it.
     */
    fun closeTrip(uid: String, tripId: String): Boolean {
        val db = db ?: return false
        if (account.uid != uid) return false
        db.collection(Schema.USERS).document(uid).collection(Schema.TRIPS).document(tripId).track { set(mapOf("ongoing" to false), SetOptions.merge()) }
        return true
    }

    private fun DocumentReference.write(data: Map<String, Any?>) = track { set(data) }

    /** Runs a write and keeps the [status] up to date: it is confirmed once the server has it, or refused with a reason. */
    private fun DocumentReference.track(write: DocumentReference.() -> Task<Void>) {
        status.written()
        write()
            .addOnSuccessListener { status.confirmed() }
            .addOnFailureListener {
                Log.w(TAG, "Could not write $path", it)
                status.failed(it.message ?: it.javaClass.simpleName)
            }
    }

    private companion object {
        const val TAG = "TripUploader"
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val RETENTION_DAYS = 60L
        const val CLEANUP_EVERY_MS = DAY_MS
        const val CLEANUP_BATCH = 30L
        const val KEY_LAST_RUN = "cleanup_last_run"
        const val KEY_CLEANED_BEFORE = "cleanup_before_ms"
    }
}
