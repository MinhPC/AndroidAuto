package com.minhphan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchemaTest {
    @Test
    fun aTripSurvivesTheTripThroughFirestoreWhichReturnsAllNumbersAsLongOrDouble() {
        val trip = TripSummary(
            id = "1", startedAt = 1_000, endedAt = 9_000, ongoing = false, day = "2026-09-21", distanceKm = 12.34,
            movingSeconds = 800, maxSpeedKmh = 88f, start = LatLon(21.0, 105.8), end = LatLon(21.1, 105.9),
            maxRpm = 4200, maxCoolantC = 92, minVoltage = 13.2f, fuelStartPercent = 70, pointCount = 42,
        )
        assertEquals(trip, tripSummaryFrom("1", firestoreLike(trip.toMap())))
        assertNull(tripSummaryFrom("2", mapOf("day" to "2026-09-21")))
    }

    @Test
    fun dayTotalsPointsAndLiveStatusSurviveToo() {
        val day = DayTotal("2026-09-21", 30.5, 2, 3600, 90f)
        assertEquals(day, dayTotalFrom("2026-09-21", firestoreLike(day.toMap())))

        val point = TripPoint(5_000, LatLon(21.0, 105.8), 61.5f, EngineData(rpm = 2100, coolantC = 88, voltage = 14.2f))
        assertEquals(point, tripPointFrom(firestoreLike(point.toMap())))

        val live = LiveStatus(LatLon(21.0, 105.8), 40f, true, 7_000, EngineData(fuelPercent = 55))
        assertEquals(live, liveStatusFrom(firestoreLike(live.toMap())))
    }

    @Test
    fun chunksSortAsText() {
        assertEquals("00007", Schema.chunkId(7))
        assertEquals(listOf("00009", "00010"), listOf(Schema.chunkId(10), Schema.chunkId(9)).sorted())
    }

    /** Firestore stores Int as Long and Float as Double, and gives them back that way, nested maps included. */
    private fun firestoreLike(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> firestoreValue(v) }

    @Suppress("UNCHECKED_CAST")
    private fun firestoreValue(v: Any?): Any? = when (v) {
        is Int -> v.toLong()
        is Float -> v.toDouble()
        is Map<*, *> -> firestoreLike(v as Map<String, Any?>)
        else -> v
    }
}
