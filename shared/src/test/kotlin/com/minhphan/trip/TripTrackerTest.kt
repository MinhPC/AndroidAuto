package com.minhphan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TripTrackerTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-09-21T08:00:00Z").toEpochMilli()

    /** A car driving due north: [meters] from the start, [speedKmh] as the GPS reports it, [seconds] after 08:00. */
    private fun fix(seconds: Long, meters: Double, speedKmh: Float, accuracy: Float? = 5f, startMs: Long = t0) =
        Fix(startMs + seconds * 1000, LatLon(21.0 + meters / 111_195.0, 105.8), accuracy, speedKmh)

    /** One fix a second for [minutes] at 60 km/h, from [fromSecond]; returns every event produced. */
    private fun TripTracker.cruise(minutes: Int, fromSecond: Long = 0, engine: EngineData = EngineData(), startMs: Long = t0): List<TripEvent> {
        val events = ArrayList<TripEvent>()
        for (s in 0..minutes * 60L) {
            val second = fromSecond + s
            events += onFix(fix(second, 16.6667 * second, 60f, startMs = startMs), engine)
        }
        return events
    }

    private fun List<TripEvent>.saves() = filterIsInstance<TripEvent.Save>().map { it.summary }

    @Test
    fun aTenMinuteDriveIsOneTripOfTheRightLength() {
        val tracker = TripTracker(zone)
        val events = tracker.cruise(minutes = 10).toMutableList()
        events += tracker.onFix(fix(600 + 400, 10_000.0, 0f)) // stands for over 5 minutes

        val trip = events.saves().last()
        assertFalse(trip.ongoing)
        assertEquals(10.0, trip.distanceKm, 0.15)
        assertEquals(600.0, trip.movingSeconds.toDouble(), 2.0)
        assertEquals(60f, trip.maxSpeedKmh, 0.01f)
        assertEquals(60f, trip.avgSpeedKmh, 1.5f)
        assertEquals("2026-09-21", trip.day)
        assertEquals(t0, trip.startedAt)
        assertEquals(t0 + 600_000, trip.endedAt) // the trip ends when the car stopped, not when it was declared over
        assertFalse(tracker.driving)
    }

    @Test
    fun theDayTotalsAddUpToTheTrip() {
        val tracker = TripTracker(zone)
        val events = tracker.cruise(minutes = 10).toMutableList()
        events += tracker.finish(t0 + 601_000)

        val changes = events.filterIsInstance<TripEvent.DayChange>()
        assertEquals(1, changes.sumOf { it.newTrips })
        assertEquals(events.saves().last().distanceKm, changes.sumOf { it.km }, 0.0001)
        assertEquals(events.saves().last().movingSeconds, changes.sumOf { it.movingSeconds })
        assertTrue(changes.all { it.day == "2026-09-21" })
    }

    @Test
    fun theRouteIsSentInPiecesThatJoinUp() {
        val tracker = TripTracker(zone)
        val events = tracker.cruise(minutes = 5).toMutableList()
        events += tracker.finish(t0 + 301_000)

        val routes = events.filterIsInstance<TripEvent.Route>()
        assertEquals(routes.indices.toList(), routes.map { it.seq })
        val points = routes.flatMap { it.points }
        assertEquals(events.saves().last().pointCount, points.size)
        assertTrue(points.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 5_000 })
        assertEquals(t0, points.first().timeMs)
    }

    @Test
    fun engineDataBecomesTheTripStatistics() {
        val tracker = TripTracker(zone)
        tracker.onFix(fix(0, 0.0, 30f), EngineData(rpm = 1500, coolantC = 70, intakeC = 30, voltage = 14.1f, fuelTrimPercent = 2))
        tracker.onFix(fix(20, 333.0, 60f), EngineData(rpm = 3200, coolantC = 88, intakeC = 45, voltage = 13.8f, fuelTrimPercent = -3))
        val trip = tracker.finish(t0 + 21_000).saves().last()

        assertEquals(3200, trip.maxRpm)
        assertEquals(88, trip.maxCoolantC)
        assertEquals(45, trip.maxIntakeC)
        assertEquals(13.8f, trip.minVoltage!!, 0.001f)
        assertEquals(14.1f, trip.maxVoltage!!, 0.001f)
    }

    @Test
    fun aParkedCarWhoseGpsWobblesNeverStartsATrip() {
        val tracker = TripTracker(zone)
        val events = (0..1800L step 3).flatMap { s -> tracker.onFix(fix(s, (s % 7) * 2.0, (s % 3).toFloat())) }
        assertTrue(events.none { it is TripEvent.Save || it is TripEvent.DayChange || it is TripEvent.Route })
    }

    @Test
    fun aShortShuntInTheCarParkLeavesNoTrace() {
        val tracker = TripTracker(zone)
        val events = ArrayList<TripEvent>()
        for (s in 0..5L) events += tracker.onFix(fix(s, 2.7 * s, 10f)) // about 14 m
        events += tracker.onFix(fix(400, 14.0, 0f))
        assertTrue(events.none { it is TripEvent.Save || it is TripEvent.DayChange || it is TripEvent.Route })
        assertFalse(tracker.driving)
    }

    @Test
    fun aRedLightDoesNotEndTheTrip() {
        val tracker = TripTracker(zone)
        val events = tracker.cruise(minutes = 3).toMutableList()
        for (s in 181..300L) events += tracker.onFix(fix(s, 3_000.0, 0f)) // standing for two minutes
        events += tracker.onFix(fix(301, 3_017.0, 60f))
        assertTrue(tracker.driving)
        assertTrue(events.saves().none { !it.ongoing })
    }

    @Test
    fun aBadFixIsIgnored() {
        val tracker = TripTracker(zone)
        val events = tracker.onFix(fix(0, 0.0, 90f, accuracy = 300f))
        assertTrue(events.isEmpty())
        assertFalse(tracker.driving)
    }

    @Test
    fun aGpsJumpAddsNoDistance() {
        val tracker = TripTracker(zone)
        tracker.cruise(minutes = 1)
        tracker.onFix(fix(61, 60_000.0, 60f)) // 59 km in one second
        val trip = tracker.finish(t0 + 62_000).saves().last()
        assertEquals(1.0, trip.distanceKm, 0.05)
    }

    @Test
    fun aTripWhoseGpsWentSilentEnds() {
        val tracker = TripTracker(zone)
        tracker.cruise(minutes = 2)
        assertTrue(tracker.tick(t0 + 120_000 + 200_000).isEmpty())
        val ended = tracker.tick(t0 + 120_000 + 301_000)
        assertFalse(ended.saves().single().ongoing)
        assertFalse(tracker.driving)
    }

    @Test
    fun aTripAcrossMidnightIsSplitBetweenTheTwoDays() {
        val tracker = TripTracker(zone)
        val start = Instant.parse("2026-09-21T23:55:00Z").toEpochMilli()
        val events = tracker.cruise(minutes = 10, startMs = start).toMutableList()
        events += tracker.finish(start + 601_000)

        val km = events.filterIsInstance<TripEvent.DayChange>().groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.km } }
        assertEquals(setOf("2026-09-21", "2026-09-22"), km.keys)
        assertEquals(5.0, km.getValue("2026-09-21"), 0.2)
        assertEquals(5.0, km.getValue("2026-09-22"), 0.2)
        assertEquals("2026-09-21", events.saves().last().day) // the trip itself belongs to the day it started
    }

    @Test
    fun theLivePositionIsSentEveryFewSecondsWhileDrivingAndRarelyWhileParked() {
        val moving = TripTracker(zone)
        val drivingLive = moving.cruise(minutes = 1).filterIsInstance<TripEvent.Live>()
        assertTrue("live events: ${drivingLive.size}", drivingLive.size in 4..5)
        assertTrue("not all moving: $drivingLive", drivingLive.all { it.status.moving })

        val parked = TripTracker(zone)
        val parkedLive = (0 until 1200L step 5).flatMap { parked.onFix(fix(it, 0.0, 0f)) }.filterIsInstance<TripEvent.Live>()
        assertEquals(2, parkedLive.size) // at once, then after 10 minutes
        assertTrue(parkedLive.none { it.status.moving })
        assertNotNull(parkedLive.first().status.position)
    }
}
