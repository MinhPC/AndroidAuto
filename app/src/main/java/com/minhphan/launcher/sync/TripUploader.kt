package com.minhphan.launcher.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.minhphan.cloud.CloudAccount
import com.minhphan.trip.Schema
import com.minhphan.trip.TripEvent
import com.minhphan.trip.toMap

/**
 * Writes what the [com.minhphan.trip.TripTracker] produces to the signed-in user's part of Firestore. Firestore keeps
 * writes made without a connection and sends them when the car is online again, even after the app has been
 * restarted, so a trip in a car park without signal is not lost. Call it from one thread at a time.
 *
 * Day totals are sent as increments, which the server adds up: a total is never replaced by a smaller number,
 * whatever this device has forgotten (cleared data, a reinstall) and however many devices write.
 */
class TripUploader(context: Context, private val account: CloudAccount) {
    private val db: FirebaseFirestore? =
        if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseFirestore.getInstance()

    /** The highest speed already sent for each day: the server cannot take a maximum, so only a higher one is sent. */
    private val sentMaxSpeed = HashMap<String, Float>()

    fun handle(events: List<TripEvent>) {
        val db = db ?: return
        val uid = account.uid ?: return
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
                    user.collection(Schema.DAYS).document(event.day)
                        .set(total, SetOptions.merge())
                        .addOnFailureListener { Log.w(TAG, "Could not add to day ${event.day}", it) }
                }
                is TripEvent.Live -> user.collection(Schema.LIVE).document(Schema.LIVE_DOC).write(event.status.toMap())
            }
        }
    }

    private fun DocumentReference.write(data: Map<String, Any?>) {
        set(data).addOnFailureListener { Log.w(TAG, "Could not write $path", it) }
    }

    private companion object {
        const val TAG = "TripUploader"
    }
}
