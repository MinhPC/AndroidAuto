package com.minhphan.launcher.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.minhphan.launcher.LauncherApplication
import com.minhphan.launcher.MainActivity
import com.minhphan.launcher.R
import com.minhphan.launcher.data.gpsLocations
import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import com.minhphan.trip.EngineData
import com.minhphan.trip.Fix
import com.minhphan.trip.LatLon
import com.minhphan.trip.TripEvent
import com.minhphan.trip.TripTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Records trips for as long as it runs, also while another app such as a map is on screen: it listens to the GPS
 * and, while the OBD adapter is connected, to the engine, hands both to a [TripTracker] and sends the result to
 * Firestore through the [TripUploader]. It runs as a foreground service (with a small notification) because Android
 * would otherwise stop location updates the moment the launcher leaves the screen.
 *
 * The home screen starts and stops it with [start] and [stop] whenever the settings, the account or the
 * location permission change.
 */
class TripRecorderService : Service() {
    private var scope: CoroutineScope? = null
    private var tracker: TripTracker? = null

    // The head unit's clock can be minutes out, so "now" is worked out from the GPS time of the last fix.
    private var lastFixTime = 0L
    private var lastFixElapsed = 0L

    private fun gpsNow() = if (lastFixTime > 0) lastFixTime + (SystemClock.elapsedRealtime() - lastFixElapsed) else System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi") // the constant is inlined; older versions ignore the type
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Android refuses to start a location service from the background, or without the permission.
            Log.w(TAG, "Cannot run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as LauncherApplication
        if (!app.settingsStore.settings.value.syncTrips || app.cloud.uid == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) startRecording(app)
        return START_STICKY
    }

    private fun startRecording(app: LauncherApplication) {
        val tracker = TripTracker(ZoneId.systemDefault()).also { this.tracker = it }
        val uploader = app.tripUploader
        app.syncStatus.recording(true)
        // The one thread of the process for this work, so a service that is stopped and started again cannot overlap.
        val scope = CoroutineScope(SupervisorJob() + app.recorderDispatcher).also { this.scope = it }
        var engine = EngineData()
        // What OBD itself says the car is doing, kept next to [engine] so GPS and OBD can stand in for each other:
        // a fix missing its own speed (poor signal) borrows this, and [tracker] bridges a lost GPS fix with it.
        var obdSpeedKmh: Float? = null

        // The uploader and the tracker are used from this one thread; everything goes through here so that what was
        // sent is also noted in the drive log.
        fun send(events: List<TripEvent>) {
            // Only what really went out is noted: a trip that was never sent has nothing to close.
            uploader.handle(events)?.let { uid -> app.driveLog.noteTripEvents(events, uid) }
        }

        scope.launch {
            // What an earlier run left behind: a trip it never got to close (the head unit went dark with the car),
            // and fill-ups logged while nobody was signed in.
            app.driveLog.openTrip()?.let { (uid, tripId) -> if (uploader.closeTrip(uid, tripId)) app.driveLog.clearOpenTrip() }
            app.uploadPendingRefuels()
            uploader.cleanupOldRoutes(System.currentTimeMillis())
        }
        scope.launch {
            app.obdHub.states.collect { state ->
                engine = state.toEngine()
                obdSpeedKmh = state.speedKmh()
            }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                if (lastFixTime > 0) send(tracker.tick(gpsNow(), obdSpeedKmh))
            }
        }
        scope.launch {
            gpsLocations(this@TripRecorderService).collect { location ->
                val fix = location.toFix(obdSpeedKmh)
                lastFixTime = fix.timeMs
                lastFixElapsed = SystemClock.elapsedRealtime()
                app.syncStatus.fix()
                app.driveLog.onFix(fix)
                send(tracker.onFix(fix, engine))
            }
            // The flow ends when there is no location permission or no GPS: nothing to record.
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        val tracker = tracker
        if (tracker != null) {
            // On the same thread as the recording, after whatever step was running: the trip in progress is closed.
            val app = application as LauncherApplication
            val finishTime = gpsNow()
            CoroutineScope(app.recorderDispatcher).launch {
                val events = tracker.finish(finishTime)
                app.tripUploader.handle(events)?.let { uid -> app.driveLog.noteTripEvents(events, uid) }
            }
        }
        scope = null
        this.tracker = null
        (application as LauncherApplication).apply {
            driveLog.flush()
            syncStatus.recording(false)
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.trip_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_route)
            .setContentTitle(getString(R.string.trip_notification_title))
            .setContentText(getString(R.string.trip_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "TripRecorder"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 30_000L

        /** Starts recording; safe to call again while it runs. Call it while the launcher is on screen. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TripRecorderService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses when the launcher has just left the screen; the next time it is shown will retry.
                Log.w(TAG, "Cannot start the recorder now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripRecorderService::class.java))
        }
    }
}

/** [obdFallbackKmh] stands in when this fix has a position but, in poor signal, no speed of its own. */
private fun Location.toFix(obdFallbackKmh: Float?) = Fix(
    timeMs = if (time > 0) time else System.currentTimeMillis(),
    position = LatLon(latitude, longitude),
    accuracyM = if (hasAccuracy()) accuracy else null,
    speedKmh = if (hasSpeed()) speed * 3.6f else obdFallbackKmh ?: 0f,
)

/**
 * What the tracker is told about the engine: what it reads; 0 rpm once the car has gone quiet (the ignition is off,
 * which ends a stopped trip soon); and nothing while the link to the adapter is being made or is lost, which says
 * nothing about the engine.
 */
internal fun ObdState.toEngine(): EngineData = when (this) {
    is ObdState.Connected -> values.toEngine()
    is ObdState.Connecting -> if (carSilent) EngineData(rpm = 0) else EngineData()
    is ObdState.Problem -> if (problem == ObdProblem.NoVehicle) EngineData(rpm = 0) else EngineData()
}

internal fun ObdValues.toEngine() = EngineData(
    rpm = rpm,
    coolantC = coolantC,
    intakeC = intakeC,
    loadPercent = loadPercent,
    throttlePercent = throttlePercent,
    fuelTrimPercent = fuelTrimPercent,
    voltage = voltage,
)

/** What the OBD adapter itself reports for road speed, when it is connected and says so; null otherwise. */
internal fun ObdState.speedKmh(): Float? = (this as? ObdState.Connected)?.values?.speedKmh?.toFloat()
