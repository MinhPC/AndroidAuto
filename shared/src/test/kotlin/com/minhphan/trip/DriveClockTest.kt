package com.minhphan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveClockTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Readings every [stepMs] from [from] to [to], all [moving] or not. */
    private fun DriveClock.readings(from: Long, to: Long, moving: Boolean, stepMs: Long = 2_000): DriveClock {
        var clock = this
        var t = from
        while (t <= to) {
            clock = clock.onReading(t, moving)
            t += stepMs
        }
        return clock
    }

    @Test
    fun nothingIsCountedUntilTheDriverStarts() {
        val driving = DriveClock().readings(0, 10 * minute, moving = true)
        assertNull(driving.current)
        assertTrue(driving.history.isEmpty())
    }

    @Test
    fun onlyTheTimeOnTheMoveCounts() {
        val clock = DriveClock().start(0)
            .readings(0, 10 * minute, moving = true) // 10 minutes driving
            .readings(10 * minute + 2_000, 12 * minute, moving = false) // 2 minutes at the lights
            .readings(12 * minute + 2_000, 20 * minute, moving = true) // 8 more minutes
        // A step counts by what the reading at its start said: the 2 s into the stop do, the 2 s before moving off
        // again do not, so it comes to the 18 minutes either way.
        assertEquals(18 * 60L, clock.current!!.movingSecondsAt(20 * minute))
    }

    @Test
    fun standingStillForHoursDoesNotEndTheJourney() {
        val clock = DriveClock().start(0)
            .readings(0, hour, moving = true)
            .readings(hour + 2_000, 5 * hour, moving = false, stepMs = minute)
        assertEquals(0L, clock.current!!.startedAt)
        assertTrue(clock.history.isEmpty())
    }

    @Test
    fun aJourneyRunsOverDaysWithTheHeadUnitOffAtNight() {
        val clock = DriveClock().start(8 * hour)
            .readings(8 * hour, 12 * hour, moving = true) // 4 hours the first day
            // The head unit goes dark with the car while it was moving; the next morning it wakes up.
            .readings(day + 7 * hour, day + 10 * hour, moving = true) // 3 hours the next day
            .endNow(day + 10 * hour)
        val journey = clock.history.single()
        assertEquals(7 * 3600L, journey.movingSeconds)
        assertEquals(26 * 3600L, journey.elapsedSeconds)
    }

    @Test
    fun startingAgainWhileOneRunsChangesNothing() {
        val running = DriveClock().start(1_000)
        assertSame(running, running.start(5_000))
        val idle = DriveClock()
        assertSame(idle, idle.endNow(5_000))
    }

    @Test
    fun endingKeepsTheJourneyAtTheTopWithItsEndTime() {
        val first = DriveClock().start(0).readings(0, 5 * minute, moving = true).endNow(5 * minute + 1_000)
        assertNull(first.current)
        assertEquals(DriveRecord(0, 5 * minute + 1_000, 301), first.history.single())
        val second = first.start(hour).readings(hour, hour + minute, moving = true).endNow(hour + minute)
        assertEquals(hour, second.history[0].startedAt)
        assertEquals(2, second.history.size)
    }

    @Test
    fun aGapInTheReadingsIsNotCountedAsMoving() {
        val clock = DriveClock().start(0)
            .readings(0, minute, moving = true)
            .onReading(2 * minute, moving = true) // a minute without readings (a tunnel, no adapter)
        assertEquals(60L, clock.current!!.movingSecondsAt(2 * minute))
    }

    @Test
    fun aClockThatJumpsBackCountsNothing() {
        val clock = DriveClock().start(10 * minute).readings(10 * minute, 11 * minute, moving = true).onReading(5 * minute, moving = true)
        assertEquals(60L, clock.current!!.movingSecondsAt(5 * minute))
    }

    @Test
    fun onlySoManyJourneysAreKept() {
        var clock = DriveClock()
        repeat(DriveClock.MAX_HISTORY + 5) { i -> clock = clock.start(i * hour).endNow(i * hour + minute) }
        assertEquals(DriveClock.MAX_HISTORY, clock.history.size)
        assertTrue(clock.history[0].startedAt > clock.history[1].startedAt)
    }

    @Test
    fun journeysSurviveStorage() {
        val drives = listOf(DriveRecord(1, 2, 3), DriveRecord(40_000, 90_000, 45))
        assertEquals(drives, decodeDrives(encodeDrives(drives)))
        assertEquals(emptyList<DriveRecord>(), decodeDrives(null))
        assertEquals(listOf(DriveRecord(1, 2, 3)), decodeDrives("1,2,3;broken;4,x,6"))
        val open = OpenDrive(1, 3, 4_000, true)
        assertEquals(open, decodeOpenDrive(encodeOpenDrive(open)))
        assertNull(decodeOpenDrive(null))
        // What the version before this one stored: not read, rather than read wrong.
        assertNull(decodeOpenDrive("1,2,3,4000,true"))
    }
}
