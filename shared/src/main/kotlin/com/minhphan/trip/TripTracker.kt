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
    /** A trip also ends when the GPS has said nothing for this long. */
    val silenceEndsTripMs: Long = 5 * 60_000L,
    val pointEveryMs: Long = 5_000L,
    val flushEveryPoints: Int = 12,
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
        var seq = 0
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
            if (fix.timeMs - t.lastFix.timeMs > config.silenceEndsTripMs) end(t, t.lastFix, fix.timeMs, out)
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

    /** Call now and then, also while there are no fixes, so a trip whose GPS went silent still ends. */
    fun tick(nowMs: Long): List<TripEvent> {
        val t = trip ?: return emptyList()
        if (nowMs - t.lastFix.timeMs <= config.silenceEndsTripMs) return emptyList()
        return ArrayList<TripEvent>().also { end(t, t.lastFix, nowMs, it) }
    }

    /** Ends the trip in progress, if any, because recording is being stopped. */
    fun finish(nowMs: Long): List<TripEvent> {
        val t = trip ?: return emptyList()
        return ArrayList<TripEvent>().also { end(t, t.lastFix, nowMs, it) }
    }

    private fun advance(t: Active, fix: Fix, engine: EngineData, out: MutableList<TripEvent>) {
        val dtMs = fix.timeMs - t.lastFix.timeMs
        val moving = fix.speedKmh >= config.stopSpeedKmh
        val meters = distanceMeters(t.lastFix.position, fix.position)
        val day = t.days.getOrPut(dayOf(fix.timeMs)) { DayChanges() }
        if (moving && meters / (dtMs / 1000.0) <= config.maxPlausibleSpeedMs) {
            t.distanceM += meters
            day.km += meters / 1000.0
        }
        if (moving && dtMs <= config.maxDrivingGapMs) {
            t.movingMs += dtMs
            day.movingMs += dtMs
        }
        t.maxSpeedKmh = maxOf(t.maxSpeedKmh, fix.speedKmh)
        day.maxSpeedKmh = maxOf(day.maxSpeedKmh, fix.speedKmh)
        noteEngine(t, engine)
        t.lastFix = fix
        if (moving) {
            t.lastMovingFix = fix
        } else if (fix.timeMs - t.lastMovingFix.timeMs >= config.stopAfterMs) {
            end(t, t.lastMovingFix, fix.timeMs, out)
            return
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
        live(t, fix, engine, out)
    }

    private fun addPoint(t: Active, fix: Fix, engine: EngineData) {
        t.buffer += TripPoint(fix.timeMs, fix.position, fix.speedKmh, engine)
        t.pointCount++
        t.lastPointAt = fix.timeMs
    }

    private fun noteEngine(t: Active, e: EngineData) {
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
        out += TripEvent.Save(summary(t, ongoing = true, endedAt = t.lastFix.timeMs, endAt = t.lastFix.position))
        drainDays(t, out)
    }

    private fun end(t: Active, endFix: Fix, nowMs: Long, out: MutableList<TripEvent>) {
        trip = null
        if (!t.confirmed) return
        if (t.buffer.isNotEmpty()) {
            out += TripEvent.Route(t.id, t.seq++, t.buffer.toList())
            t.buffer.clear()
        }
        out += TripEvent.Save(summary(t, ongoing = false, endedAt = endFix.timeMs, endAt = endFix.position))
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

    private fun live(t: Active, fix: Fix, engine: EngineData, out: MutableList<TripEvent>) {
        if (fix.timeMs - t.lastLiveAt < config.liveEveryMs) return
        t.lastLiveAt = fix.timeMs
        out += TripEvent.Live(LiveStatus(fix.position, fix.speedKmh, moving = true, updatedAt = fix.timeMs, engine = engine))
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
