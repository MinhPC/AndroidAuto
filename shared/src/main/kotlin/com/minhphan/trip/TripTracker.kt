package com.minhphan.trip

import java.time.Instant
import java.time.ZoneId

/** One GPS reading. [accuracyM] null means the receiver did not say. */
data class Fix(val timeMs: Long, val position: LatLon, val accuracyM: Float?, val speedKmh: Float)

data class TripConfig(
    /** Above this speed a trip starts. Below [stopSpeedKmh] the car counts as standing; in between it keeps its state. */
    val startSpeedKmh: Float = 5f,
    val stopSpeedKmh: Float = 2f,
    /** A fix less accurate than this is ignored: in a car park or a tunnel the GPS can be hundreds of metres out. */
    val maxAccuracyM: Float = 50f,
    /** A trip ends after the car has stood this long (a red light is not the end of a trip, an hour in a car park is). */
    val stopAfterMs: Long = 5 * 60_000L,
    /**
     * Once the engine has been seen (an OBD adapter is connected), a stopped car whose engine reads 0 rpm (the car has
     * gone quiet with the ignition) is parked with the engine off: the trip is over after this long, not after
     * [stopAfterMs]. A reading that is merely missing (the adapter link dropped) is not that, and a 0 for a few
     * seconds at a red light is why it takes a while.
     */
    val engineOffAfterMs: Long = 20_000L,
    /** A car that stands with its engine running (a jam, a drive-through) keeps its trip open this long instead. */
    val engineIdleStopAfterMs: Long = 15 * 60_000L,
    /** A trip also ends when neither GPS nor (while bridging, see [gpsLostAfterMs]) OBD has said anything for this long. */
    val silenceEndsTripMs: Long = 5 * 60_000L,
    /**
     * No real GPS fix for this long, while OBD still reports the car moving: the distance since is guessed from
     * that speed instead of from position (a tunnel or an underground car park, not a real stop). Longer than a
     * normal gap between fixes (about one second), so a moment of jitter does not start it.
     */
    val gpsLostAfterMs: Long = 15_000L,
    /**
     * While GPS is lost, two OBD speeds further apart than this (the adapter's link dropped in between) say nothing
     * about the stretch between them, which adds no distance.
     */
    val maxBridgeStepMs: Long = 60_000L,
    /** While GPS is lost the guessed distance is fed in reading by reading, but sent no more often than this. */
    val bridgeFlushEveryMs: Long = 30_000L,
    val pointEveryMs: Long = 5_000L,
    val flushEveryPoints: Int = 12,
    /**
     * When the car comes to a stop what is waiting is sent at once, because the driver may switch the engine off
     * (and the head unit with it) before the next scheduled send. Stops closer together than this are one stop:
     * a queue of traffic must not write to Firestore at every crawl.
     */
    val stopFlushMinGapMs: Long = 10_000L,
    val liveEveryMs: Long = 15_000L,
    val idleLiveEveryMs: Long = 10 * 60_000L,
    /** A trip shorter than this is GPS drift or a move in the car park and is dropped without a trace. */
    val confirmDistanceKm: Double = 0.1,
    /** A jump faster than this between two fixes (250 km/h) is a GPS glitch and adds no distance. */
    val maxPlausibleSpeedMs: Double = 70.0,
    /** Standing time between two fixes further apart than this is not counted as driving time. */
    val maxDrivingGapMs: Long = 30_000L,
)

/** "Not yet" for the times of the last point and the last live update; half of MIN_VALUE so that subtracting it cannot overflow. */
private const val NEVER = Long.MIN_VALUE / 2

/** What the tracker wants written; the caller does the writing. */
sealed interface TripEvent {
    /** Create or replace the trip; final when [TripSummary.ongoing] is false. */
    data class Save(val summary: TripSummary) : TripEvent

    /** The next stretch of the route of a trip. */
    data class Route(val tripId: String, val seq: Int, val points: List<TripPoint>) : TripEvent

    /** Add this to the totals of [day]; each change is sent once, so the caller can keep a running sum. */
    data class DayChange(val day: String, val km: Double, val movingSeconds: Long, val newTrips: Int, val maxSpeedKmh: Float) : TripEvent

    data class Live(val status: LiveStatus) : TripEvent
}

/**
 * Turns the stream of GPS fixes (and what the engine said at each) into trips: when one starts and ends, how far it
 * went, its route and its statistics, and the distance driven on each day. It does no I/O and reads no clock, so
 * it is driven by [onFix], [tick] and [finish] and returns the [TripEvent]s to store. Not thread safe.
 */
class TripTracker(private val zone: ZoneId, private val config: TripConfig = TripConfig()) {
    private class DayChanges(var km: Double = 0.0, var movingMs: Long = 0, var newTrips: Int = 0, var maxSpeedKmh: Float = 0f)

    private class Active(val startFix: Fix, val day: String) {
        val id = startFix.timeMs.toString()
        var lastFix = startFix
        var lastMovingFix = startFix
        var distanceM = 0.0
        var movingMs = 0L
        var maxSpeedKmh = 0f
        var confirmed = false
        var lastPointAt = NEVER
        var lastLiveAt = NEVER
        var lastStopFlushAt = NEVER
        var sawEngine = false
        var engineOffSince = NEVER
        var seq = 0
        /** The last time either a real fix or, while [bridging], OBD confirmed the car was still going. */
        var lastActivityAt = startFix.timeMs
        /** True from the moment GPS fixes stop arriving and the distance is being guessed from OBD speed, until a
         *  real fix resyncs the position. */
        var bridging = false
        /** The last OBD speed taken while [bridging], and when: the guessed distance runs from there. */
        var lastBridgeAt = NEVER
        var lastBridgeKmh = 0f
        /** The end of the last stretch guessed as moving while GPS was lost: where the trip ends if it ends unseen. */
        var lastBridgeMovingAt = NEVER
        var lastBridgeFlushAt = NEVER
        var pointCount = 0
        val buffer = ArrayList<TripPoint>()
        val days = LinkedHashMap<String, DayChanges>()
        var maxRpm: Int? = null
        var maxCoolantC: Int? = null
        var maxIntakeC: Int? = null
        var minVoltage: Float? = null
        var maxVoltage: Float? = null
    }

    private var trip: Active? = null
    private var lastIdleLiveAt = NEVER

    /** Whether a trip is being recorded. */
    val driving: Boolean get() = trip != null

    fun onFix(fix: Fix, engine: EngineData = EngineData()): List<TripEvent> {
        if (fix.accuracyM != null && fix.accuracyM > config.maxAccuracyM) return emptyList()
        val out = ArrayList<TripEvent>()
        trip?.let { t ->
            if (fix.timeMs <= t.lastFix.timeMs) return out // a repeat or a late one
            if (fix.timeMs - t.lastActivityAt > config.silenceEndsTripMs) end(t, t.lastFix, fix.timeMs, out)
        }

        val current = trip
        if (current == null) {
            if (fix.speedKmh < config.startSpeedKmh) {
                idleLive(fix, engine, out)
                return out
            }
            val started = Active(fix, dayOf(fix.timeMs))
            trip = started
            started.maxSpeedKmh = fix.speedKmh
            noteEngine(started, engine)
            addPoint(started, fix, engine)
            live(started, fix, engine, out)
            return out
        }
        advance(current, fix, engine, out)
        return out
    }

    /**
     * Call now and then, also while there are no fixes, so a trip whose GPS went silent still ends - or, while
     * [obdSpeedKmh] says the car is still moving, keeps going on a guessed distance instead ([bridge]). Call it with
     * every OBD reading too: the guess is only as good as the readings it is made from. With GPS lost, the same rules
     * as with it end the trip: [engine] going quiet (0 rpm) once the car had stopped (an underground car park), or
     * standing with the engine running for [TripConfig.engineIdleStopAfterMs] (a jam in a tunnel).
     */
    fun tick(nowMs: Long, obdSpeedKmh: Float? = null, engine: EngineData = EngineData()): List<TripEvent> {
        val t = trip ?: return emptyList()
        val out = ArrayList<TripEvent>()
        val gpsLost = nowMs - t.lastFix.timeMs >= config.gpsLostAfterMs
        if (gpsLost) {
            if (obdSpeedKmh != null) bridge(t, nowMs, obdSpeedKmh, out)
            noteEngine(t, engine)
            // With no speed now (the car silent, or the link dropped) it is the last speed heard: an ECU that stops
            // answering for a moment in a tunnel is not a car that has stopped.
            val lastKnownKmh = obdSpeedKmh ?: if (t.bridging) t.lastBridgeKmh else t.lastFix.speedKmh
            val stopped = lastKnownKmh < config.stopSpeedKmh
            if (stopped && t.sawEngine && engine.rpm == 0) {
                if (t.engineOffSince == NEVER) t.engineOffSince = nowMs
                if (nowMs - t.engineOffSince >= config.engineOffAfterMs) {
                    end(t, t.lastFix, nowMs, out)
                    return out
                }
            } else if (engine.rpm != null) {
                t.engineOffSince = NEVER
            }
            // Standing with the engine heard running: a jam, as with GPS, not the silence of a car that is gone.
            if (obdSpeedKmh != null && stopped && (engine.rpm ?: 0) > 0) {
                if (nowMs - lastMovedAt(t) >= config.engineIdleStopAfterMs) end(t, t.lastFix, nowMs, out)
                return out
            }
        }
        if (nowMs - t.lastActivityAt > config.silenceEndsTripMs) end(t, t.lastFix, nowMs, out)
        return out
    }

    /**
     * Adds the distance covered since the last real fix or the last OBD reading, guessed from the speed at either end
     * of that stretch and the time it took - GPS is gone (a tunnel, an underground car park) but the engine is still
     * heard from. The route itself is not extended: without a heading there is no way to say where the car went, only
     * how far: the map simply jumps to the next real fix. [advance] must not double count this once that fix
     * resyncs the position, which is why it checks [Active.bridging] first; it adds the stretch since the last reading.
     */
    private fun bridge(t: Active, nowMs: Long, obdSpeedKmh: Float, out: MutableList<TripEvent>) {
        if (!t.bridging) {
            t.bridging = true
            t.lastBridgeAt = t.lastFix.timeMs
            t.lastBridgeKmh = t.lastFix.speedKmh
            // The first stretch, back to the last fix, is known to be GPS lost, however long: guessed like the rest.
            if (nowMs > t.lastBridgeAt) addGuessed(t, (t.lastBridgeKmh + obdSpeedKmh) / 2f, nowMs - t.lastBridgeAt, nowMs)
        } else {
            val dtMs = nowMs - t.lastBridgeAt
            if (dtMs <= 0) return
            // An adapter that went quiet for long says nothing about how far the car went meanwhile.
            if (dtMs <= config.maxBridgeStepMs) addGuessed(t, (t.lastBridgeKmh + obdSpeedKmh) / 2f, dtMs, nowMs)
        }
        t.lastBridgeAt = nowMs
        t.lastBridgeKmh = obdSpeedKmh
        if (obdSpeedKmh >= config.stopSpeedKmh) {
            t.lastActivityAt = nowMs
            t.maxSpeedKmh = maxOf(t.maxSpeedKmh, obdSpeedKmh)
            val day = t.days.getOrPut(dayOf(nowMs)) { DayChanges() }
            day.maxSpeedKmh = maxOf(day.maxSpeedKmh, obdSpeedKmh)
        }
        if (!t.confirmed && t.distanceM >= config.confirmDistanceKm * 1000.0) {
            t.confirmed = true
            t.days.getOrPut(t.day) { DayChanges() }.newTrips = 1
            t.lastBridgeFlushAt = nowMs
            flush(t, out)
        } else if (t.lastBridgeMovingAt > t.lastBridgeFlushAt && nowMs - t.lastBridgeFlushAt >= config.bridgeFlushEveryMs) {
            // Only once it has gone further: a car standing in an underground car park has nothing new to send.
            t.lastBridgeFlushAt = nowMs
            flush(t, out)
        }
    }

    /** Adds [dtMs] driven at [kmh] (an average over that time), ending at [atMs], to the trip and its day. */
    private fun addGuessed(t: Active, kmh: Float, dtMs: Long, atMs: Long) {
        val km = kmh * (dtMs / 3_600_000.0)
        t.distanceM += km * 1000.0
        val day = t.days.getOrPut(dayOf(atMs)) { DayChanges() }
        day.km += km
        if (kmh >= config.stopSpeedKmh) {
            t.movingMs += dtMs
            day.movingMs += dtMs
            t.lastBridgeMovingAt = maxOf(t.lastBridgeMovingAt, atMs)
        }
    }

    /** When the trip was last known to be on the move: its last fix, or OBD's last word while GPS was lost after it. */
    private fun endedAt(t: Active, endFix: Fix) = maxOf(endFix.timeMs, t.lastBridgeMovingAt)

    /** When the car last moved: by GPS, or by OBD while GPS was lost. */
    private fun lastMovedAt(t: Active) = maxOf(t.lastMovingFix.timeMs, t.lastBridgeMovingAt)

    /** Ends the trip in progress, if any, because recording is being stopped. */
    fun finish(nowMs: Long): List<TripEvent> {
        val t = trip ?: return emptyList()
        return ArrayList<TripEvent>().also { end(t, t.lastFix, nowMs, it) }
    }

    private fun advance(t: Active, fix: Fix, engine: EngineData, out: MutableList<TripEvent>) {
        // A real fix resyncs the position: the distance guessed by bridge() while it was gone already covers this
        // gap, so the jump to here must not be counted again as real, GPS-measured distance.
        val wasBridging = t.bridging
        t.bridging = false
        if (wasBridging) {
            // The guess ran up to the last OBD reading; the stretch from there to this fix is guessed too.
            val sinceReading = fix.timeMs - t.lastBridgeAt
            if (sinceReading in 1..config.maxBridgeStepMs) addGuessed(t, (t.lastBridgeKmh + fix.speedKmh) / 2f, sinceReading, fix.timeMs)
            // It moved there unseen: if the trip ends while it stands here, it ended here, when it last moved, and
            // not back where GPS was lost.
            if (t.lastBridgeMovingAt > t.lastMovingFix.timeMs) t.lastMovingFix = fix.copy(timeMs = t.lastBridgeMovingAt)
        }
        val dtMs = fix.timeMs - t.lastFix.timeMs
        val moving = fix.speedKmh >= config.stopSpeedKmh
        val justStopped = !moving && t.lastFix.speedKmh >= config.stopSpeedKmh
        val meters = distanceMeters(t.lastFix.position, fix.position)
        val day = t.days.getOrPut(dayOf(fix.timeMs)) { DayChanges() }
        if (!wasBridging && moving && meters / (dtMs / 1000.0) <= config.maxPlausibleSpeedMs) {
            t.distanceM += meters
            day.km += meters / 1000.0
        }
        if (!wasBridging && moving && dtMs <= config.maxDrivingGapMs) {
            t.movingMs += dtMs
            day.movingMs += dtMs
        }
        t.maxSpeedKmh = maxOf(t.maxSpeedKmh, fix.speedKmh)
        day.maxSpeedKmh = maxOf(day.maxSpeedKmh, fix.speedKmh)
        noteEngine(t, engine)
        t.lastFix = fix
        t.lastActivityAt = fix.timeMs
        val rpm = engine.rpm ?: 0
        val engineOff = t.sawEngine && engine.rpm == 0
        if (!moving && engineOff) {
            if (t.engineOffSince == NEVER) t.engineOffSince = fix.timeMs
        } else {
            t.engineOffSince = NEVER
        }
        if (moving) {
            t.lastMovingFix = fix
        } else {
            // A car out of a long tunnel and straight into a queue has not been standing all that time.
            val standingFor = fix.timeMs - lastMovedAt(t)
            val over = when {
                t.engineOffSince != NEVER -> fix.timeMs - t.engineOffSince >= config.engineOffAfterMs
                rpm > 0 -> standingFor >= config.engineIdleStopAfterMs
                else -> standingFor >= config.stopAfterMs
            }
            if (over) {
                end(t, t.lastMovingFix, fix.timeMs, out)
                return
            }
        }

        if (moving && fix.timeMs - t.lastPointAt >= config.pointEveryMs) {
            addPoint(t, fix, engine)
            if (t.buffer.size >= config.flushEveryPoints) flush(t, out)
        }
        if (!t.confirmed && t.distanceM >= config.confirmDistanceKm * 1000.0) {
            t.confirmed = true
            t.days.getOrPut(t.day) { DayChanges() }.newTrips = 1
            flush(t, out)
        }
        if (justStopped && t.confirmed && fix.timeMs - t.lastStopFlushAt >= config.stopFlushMinGapMs) {
            // The car has just come to a stop, maybe for good: send the route up to this spot, the trip and the day totals now.
            t.lastStopFlushAt = fix.timeMs
            addPoint(t, fix, engine)
            flush(t, out)
            live(t, fix, engine, out, force = true)
        } else {
            live(t, fix, engine, out)
        }
    }

    private fun addPoint(t: Active, fix: Fix, engine: EngineData) {
        t.buffer += TripPoint(fix.timeMs, fix.position, fix.speedKmh, engine)
        t.pointCount++
        t.lastPointAt = fix.timeMs
    }

    private fun noteEngine(t: Active, e: EngineData) {
        if (e.rpm != null) t.sawEngine = true
        e.rpm?.let { t.maxRpm = maxOf(t.maxRpm ?: it, it) }
        e.coolantC?.let { t.maxCoolantC = maxOf(t.maxCoolantC ?: it, it) }
        e.intakeC?.let { t.maxIntakeC = maxOf(t.maxIntakeC ?: it, it) }
        e.voltage?.let {
            t.minVoltage = minOf(t.minVoltage ?: it, it)
            t.maxVoltage = maxOf(t.maxVoltage ?: it, it)
        }
    }

    /** Sends what has piled up: the route so far, the trip itself and the day totals. Nothing before the trip is confirmed. */
    private fun flush(t: Active, out: MutableList<TripEvent>) {
        if (!t.confirmed) return
        if (t.buffer.isNotEmpty()) {
            out += TripEvent.Route(t.id, t.seq++, t.buffer.toList())
            t.buffer.clear()
        }
        out += TripEvent.Save(summary(t, ongoing = true, endedAt = endedAt(t, t.lastFix), endAt = t.lastFix.position))
        drainDays(t, out)
    }

    private fun end(t: Active, endFix: Fix, nowMs: Long, out: MutableList<TripEvent>) {
        trip = null
        if (!t.confirmed) return
        if (t.buffer.isNotEmpty()) {
            out += TripEvent.Route(t.id, t.seq++, t.buffer.toList())
            t.buffer.clear()
        }
        out += TripEvent.Save(summary(t, ongoing = false, endedAt = endedAt(t, endFix), endAt = endFix.position))
        drainDays(t, out)
        out += TripEvent.Live(LiveStatus(t.lastFix.position, 0f, moving = false, updatedAt = nowMs, engine = EngineData()))
    }

    private fun drainDays(t: Active, out: MutableList<TripEvent>) {
        for ((day, c) in t.days) {
            if (c.km == 0.0 && c.movingMs == 0L && c.newTrips == 0 && c.maxSpeedKmh == 0f) continue
            out += TripEvent.DayChange(day, c.km, c.movingMs / 1000, c.newTrips, c.maxSpeedKmh)
            c.km = 0.0
            c.movingMs = 0
            c.newTrips = 0
            c.maxSpeedKmh = 0f
        }
    }

    private fun live(t: Active, fix: Fix, engine: EngineData, out: MutableList<TripEvent>, force: Boolean = false) {
        if (!force && fix.timeMs - t.lastLiveAt < config.liveEveryMs) return
        t.lastLiveAt = fix.timeMs
        // A car standing in a trip is not moving: the last live document written before the engine goes off says so.
        out += TripEvent.Live(LiveStatus(fix.position, fix.speedKmh, moving = fix.speedKmh >= config.stopSpeedKmh, updatedAt = fix.timeMs, engine = engine))
    }

    /** While parked the phone still learns where the car is, but only now and then. */
    private fun idleLive(fix: Fix, engine: EngineData, out: MutableList<TripEvent>) {
        if (fix.timeMs - lastIdleLiveAt < config.idleLiveEveryMs) return
        lastIdleLiveAt = fix.timeMs
        out += TripEvent.Live(LiveStatus(fix.position, 0f, moving = false, updatedAt = fix.timeMs, engine = engine))
    }

    private fun summary(t: Active, ongoing: Boolean, endedAt: Long, endAt: LatLon) = TripSummary(
        id = t.id,
        startedAt = t.startFix.timeMs,
        endedAt = endedAt,
        ongoing = ongoing,
        day = t.day,
        distanceKm = t.distanceM / 1000.0,
        movingSeconds = t.movingMs / 1000,
        maxSpeedKmh = t.maxSpeedKmh,
        start = t.startFix.position,
        end = endAt,
        maxRpm = t.maxRpm,
        maxCoolantC = t.maxCoolantC,
        maxIntakeC = t.maxIntakeC,
        minVoltage = t.minVoltage,
        maxVoltage = t.maxVoltage,
        pointCount = t.pointCount,
    )

    private fun dayOf(timeMs: Long): String = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toString()
}
