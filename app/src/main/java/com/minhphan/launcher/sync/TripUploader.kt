package com.minhphan.launcher.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.minhphan.cloud.CloudAccount
import com.minhphan.trip.LiveStatus
import com.minhphan.trip.Schema
import com.minhphan.trip.TripEvent
import com.minhphan.trip.toMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Writes what the [com.minhphan.trip.TripTracker] produces to the signed-in user's part of Firestore. Firestore keeps
 * writes made without a connection and sends them when the car is online again, even after the app has been
 * restarted, so a trip in a car park without signal is not lost. Call it from one thread at a time.
 *
 * Day totals are sent as increments, which the server adds up: a total is never replaced by a smaller number,
 * whatever this device has forgotten (cleared data, a reinstall) and however many devices write.
 */
class TripUploader(context: Context, private val account: CloudAccount, private val status: SyncStatus) {
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
                    user.collection(Schema.DAYS).document(event.day).track { set(total, SetOptions.merge()) }
                }
                is TripEvent.Live -> user.collection(Schema.LIVE).document(Schema.LIVE_DOC).write(event.status.toMap())
            }
        }
    }

    /**
     * Writes [live] as the car's live position, marked as a test, and reads it back from the server. That proves the
     * whole path: the sign-in, the rules for writing, the connection and the rules for reading.
     */
    suspend fun sendTest(live: LiveStatus): TestResult {
        val db = db ?: return TestResult.Failed("Firebase is not set up in this build")
        val uid = account.uid ?: return TestResult.Failed("Not signed in")
        val document = db.collection(Schema.USERS).document(uid).collection(Schema.LIVE).document(Schema.LIVE_DOC)
        return try {
            withTimeout(TEST_TIMEOUT_MS) {
                document.set(live.toMap() + ("test" to true)).await()
                if (document.get(Source.SERVER).await().exists()) TestResult.Confirmed
                else TestResult.Failed("Written, but not found when read back")
            }
        } catch (_: TimeoutCancellationException) {
            TestResult.NotConfirmed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestResult.Failed(e.message ?: e.javaClass.simpleName)
        }
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

        /** Firestore keeps a write it cannot send and never answers, so a test must not wait for ever. */
        const val TEST_TIMEOUT_MS = 10_000L
    }
}

/** How a test send ended. */
sealed interface TestResult {
    /** The server has the data and it can be read back. */
    data object Confirmed : TestResult

    /** The server did not answer in time; Firestore keeps the write and sends it when there is a connection. */
    data object NotConfirmed : TestResult

    /** Firestore refused, and says why (for instance PERMISSION_DENIED when the rules are not published). */
    data class Failed(val reason: String) : TestResult
}
