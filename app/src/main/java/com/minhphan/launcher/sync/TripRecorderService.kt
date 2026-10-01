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
import com.minhphan.launcher.inAppLanguage
import com.minhphan.launcher.data.DriveState
import com.minhphan.launcher.data.SpeedSource
import com.minhphan.launcher.data.carSpeed
import com.minhphan.launcher.data.gpsLocations
import com.minhphan.launcher.data.nextMoving
import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import com.minhphan.trip.EngineData
import com.minhphan.trip.Fix
import com.minhphan.trip.LatLon
import com.minhphan.trip.TripEvent
import com.minhphan.trip.TripTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Runs while the car is driven, also while another app such as a map is on screen, as a foreground service (with a
 * small notification) because Android would otherwise stop location updates the moment the launcher leaves the screen.
 *
 * While the driver has a journey running it keeps the trip clock ([com.minhphan.trip.DriveClock], in the drive log):
 * every couple of seconds it says whether the car is moving, from the OBD adapter's speed while it is connected and
 * the GPS's otherwise, so the journey counts its time on the move. When the driver has asked for trips to be sent to
 * their account and is signed in, it records them too: it hands the GPS and the engine to a [TripTracker] and sends
 * the result to Firestore through the [TripUploader].
 *
 * The home screen starts it with [start] while there is either to do, and again whenever that changes, so it takes up
 * or drops each; it stops it with [stop] when there is neither, or no location permission. With neither it stops
 * itself too, so the GPS is not kept on for nothing.
 */
class TripRecorderService : Service() {
    private var clockScope: CoroutineScope? = null
    private var cloudScope: CoroutineScope? = null
    private var tracker: TripTracker? = null

    // The head unit's clock can be minutes out, so "now" is worked out from the GPS time of the last fix.
    private var lastFixTime = 0L
    private var lastFixElapsed = 0L

    private fun gpsNow() = if (lastFixTime > 0) lastFixTime + (SystemClock.elapsedRealtime() - lastFixElapsed) else System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    // Its notification in the launcher's language, like the rest of it.
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.inAppLanguage())

    @SuppressLint("InlinedApi") // the constant is inlined; older versions ignore the type
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LauncherApplication
        val sending = app.settingsStore.settings.value.syncTrips && app.cloud.uid != null
        val counting = app.driveLog.drives.value.current != null
        if (!sending && !counting) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // Also when it already runs: the notification says whether anything is being sent.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(sending), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Android refuses to start a location service from the background, or without the permission.
            Log.w(TAG, "Cannot run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (counting && clockScope == null) startClock(app)
        if (!counting && clockScope != null) stopClock()
        if (sending && cloudScope == null) startSending(app)
        if (!sending && cloudScope != null) stopSending(app)
        return START_STICKY
    }

    /**
     * The trip clock: the latest GPS speed and the adapter's, and every [CLOCK_STEP_MS] whether the car is moving.
     * The readings only set these; the drive log is told no more often than that.
     */
    private fun startClock(app: LauncherApplication) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { clockScope = it }
        val speeds = ClockSpeeds()
        scope.launch { app.obdHub.states.collect { speeds.obdKmh = (it as? ObdState.Connected)?.values?.speedKmh } }
        scope.launch {
            gpsLocations(this@TripRecorderService).collect {
                speeds.gpsKmh = if (it.hasSpeed()) it.speed * 3.6f else 0f
                speeds.gpsAt = SystemClock.elapsedRealtime()
            }
        }
        scope.launch {
            var moving = false
            while (true) {
                delay(CLOCK_STEP_MS)
                val gpsFresh = speeds.gpsAt > 0 && SystemClock.elapsedRealtime() - speeds.gpsAt < GPS_FRESH_MS
                val speed = carSpeed(speeds.obdKmh, if (gpsFresh) DriveState.Fix(speeds.gpsKmh) else DriveState.NoFix)
                // Neither the adapter nor the GPS: not known to move, so nothing is counted until one says so.
                moving = speed.source != SpeedSource.None && nextMoving(moving, speed)
                app.driveLog.onDriveReading(System.currentTimeMillis(), moving)
            }
        }
    }

    private fun stopClock() {
        clockScope?.cancel()
        clockScope = null
    }

    private fun startSending(app: LauncherApplication) {
        val tracker = TripTracker(ZoneId.systemDefault()).also { this.tracker = it }
        val uploader = app.tripUploader
        app.syncStatus.recording(true)
        // The one thread of the process for this work, so a service that is stopped and started again cannot overlap.
        val scope = CoroutineScope(SupervisorJob() + app.recorderDispatcher).also { this.cloudScope = it }
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
            // What an earlier run left behind: a trip it never got to close (the head unit went dark with the car).
            app.driveLog.openTrip()?.let { (uid, tripId) -> if (uploader.closeTrip(uid, tripId)) app.driveLog.clearOpenTrip() }
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
        }
    }

    /** Stops sending; the trip being sent is closed, on the same thread as the sending, after whatever step was running. */
    private fun stopSending(app: LauncherApplication) {
        cloudScope?.cancel()
        cloudScope = null
        val tracker = tracker ?: return
        this.tracker = null
        val finishTime = gpsNow()
        CoroutineScope(app.recorderDispatcher).launch {
            val events = tracker.finish(finishTime)
            app.tripUploader.handle(events)?.let { uid -> app.driveLog.noteTripEvents(events, uid) }
        }
        app.syncStatus.recording(false)
    }

    override fun onDestroy() {
        val app = application as LauncherApplication
        stopClock()
        stopSending(app)
        app.driveLog.flush()
        super.onDestroy()
    }

    private fun notification(sending: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.trip_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_route)
            .setContentTitle(getString(if (sending) R.string.trip_notification_title else R.string.trip_notification_title_clock))
            .setContentText(getString(if (sending) R.string.trip_notification_text else R.string.trip_notification_text_clock))
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

        /** How often the trip clock is told whether the car moves. */
        private const val CLOCK_STEP_MS = 2_000L

        /** A GPS speed older than this says nothing about now (a tunnel, a garage). */
        private const val GPS_FRESH_MS = 5_000L

        /** Starts it, or brings it up to date with the settings; safe to call again while it runs. Call it while the launcher is on screen. */
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

/** The latest speeds the trip clock reads, written by the GPS and the adapter on their own threads. */
private class ClockSpeeds {
    @Volatile var obdKmh: Int? = null
    @Volatile var gpsKmh = 0f
    /** When the last fix came, in elapsed realtime; 0 before the first. */
    @Volatile var gpsAt = 0L
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
