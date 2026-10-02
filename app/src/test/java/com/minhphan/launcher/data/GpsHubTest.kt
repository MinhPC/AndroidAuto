package com.minhphan.launcher.data

import android.location.Location
import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsHubTest {
    @Test fun obdWithoutRecordingNeedsNoActiveGps() {
        assertEquals(GpsMode.Off, gpsMode(true, false, 40, false))
        assertEquals(GpsMode.Off, gpsMode(true, false, 0, true))
        assertEquals(GpsMode.Off, gpsMode(false, false, null, false))
    }

    @Test fun speedFallbackAndRecordingKeepFastGpsWithoutObd() {
        assertEquals(GpsMode.Fast, gpsMode(true, false, null, true))
        assertEquals(GpsMode.Fast, gpsMode(false, true, null, true))
        assertEquals(GpsMode.Fast, gpsMode(false, true, 50, true))
        assertEquals(GpsMode.Fast, gpsMode(false, true, 0, false))
        assertEquals(GpsMode.Parked, gpsMode(false, true, 0, true))
    }

    @Test fun mapOnScreenKeepsFastGpsEvenWithObd() {
        assertEquals(GpsMode.Fast, gpsMode(false, false, 40, false, map = true))
        assertEquals(GpsMode.Fast, gpsMode(false, true, 0, true, map = true))
    }

    private fun connected(kmh: Int?) = ObdState.Connected(ObdValues(speedKmh = kmh))

    private suspend fun gpsSpeeds(vararg states: ObdState): List<Int?> = obdSpeedForGps(states.toList().asFlow()).toList()

    @Test fun carSwitchedOffAtAStandstillCountsAsParked() = runBlocking {
        assertEquals(listOf(0), gpsSpeeds(connected(0), ObdState.Connecting(carSilent = true), ObdState.Problem(ObdProblem.NoVehicle)))
        // Head unit started with the car already off: never heard moving, so parked.
        assertEquals(listOf(0), gpsSpeeds(ObdState.Problem(ObdProblem.NoVehicle)))
    }

    @Test fun carGoingQuietWhileMovingHandsOverToGps() = runBlocking {
        assertEquals(listOf(50, null), gpsSpeeds(connected(50), ObdState.Connecting(carSilent = true), ObdState.Problem(ObdProblem.NoVehicle)))
    }

    @Test fun droppedLinkKeepsLastSpeedUntilItExpires() = runBlocking {
        val lost = ObdState.Problem(ObdProblem.Lost, ObdValues(speedKmh = 40), lastExpiresInMs = 100)
        val speeds = async { obdSpeedForGps(flow { emit(connected(40)); emit(lost); awaitCancellation() }).take(2).toList() }
        delay(50)
        assertTrue(speeds.isActive)
        assertEquals(listOf(40, null), withTimeout(1_000) { speeds.await() })
        // Nothing left to vouch for: straight to GPS.
        assertEquals(listOf(40, null), gpsSpeeds(connected(40), ObdState.Connecting(ObdValues(speedKmh = 40), lastExpiresInMs = 0)))
    }

    @Test fun reconnectingWithinTheWindowDoesNotWakeGps() = runBlocking {
        val hiccup = ObdState.Connecting(ObdValues(speedKmh = 30), lastExpiresInMs = 10_000)
        assertEquals(listOf(30, 32), gpsSpeeds(connected(30), hiccup, connected(32)))
    }

    private class Source {
        val intervals = CopyOnWriteArrayList<Long>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        fun updates(interval: Long): Flow<Location> = flow {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            intervals.add(interval)
            try { awaitCancellation() } finally { active.decrementAndGet() }
        }
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(3_000) { while (!condition()) delay(5) }
    }

    @Test fun clientsShareOneListenerAndLastClientReleasesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val hub = GpsHub(scope, MutableStateFlow<Int?>(null), source::updates)
        try {
            val home = launch { hub.readings(GpsUse.Speed).collect() }
            eventually { source.active.get() == 1 }
            val clock = launch { hub.readings(GpsUse.Speed).collect() }
            val recorder = launch { hub.readings(GpsUse.Recording).collect() }
            delay(50)
            assertEquals(1, source.intervals.size)
            home.cancelAndJoin()
            clock.cancelAndJoin()
            assertEquals(1, source.active.get())
            recorder.cancelAndJoin()
            eventually { source.active.get() == 0 }
            assertEquals(1, source.peak.get())
        } finally { scope.cancel() }
    }

    @Test fun obdArrivalStopsGpsAndDisconnectRestartsFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val obd = MutableStateFlow<Int?>(null)
        val hub = GpsHub(scope, obd, source::updates)
        val client = launch { hub.readings(GpsUse.Speed).collect() }
        try {
            eventually { source.active.get() == 1 }
            obd.value = 30
            eventually { source.active.get() == 0 }
            obd.value = null
            eventually { source.intervals.size == 2 && source.active.get() == 1 }
            assertEquals(listOf(1_000L, 1_000L), source.intervals.toList())
            assertEquals(1, source.peak.get())
        } finally { client.cancelAndJoin(); scope.cancel() }
    }

    @Test fun parkedRecorderSlowsThenMovementOrObdLossWakesGps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val obd = MutableStateFlow<Int?>(0)
        val hub = GpsHub(scope, obd, source::updates, parkedAfterMs = 100)
        val recorder = launch { hub.readings(GpsUse.Recording).collect() }
        try {
            eventually { source.intervals.lastOrNull() == 30_000L }
            obd.value = 1
            eventually { source.intervals.size == 3 }
            assertEquals(1_000L, source.intervals.last())
            obd.value = 0
            eventually { source.intervals.last() == 30_000L }
            obd.value = null
            eventually { source.intervals.size == 5 }
            assertEquals(1_000L, source.intervals.last())
            delay(150)
            assertEquals(5, source.intervals.size)
            assertEquals(1, source.peak.get())
        } finally { recorder.cancelAndJoin(); scope.cancel() }
    }

    @Test fun clockWithObdDoesNotKeepRecorderGpsAlive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val hub = GpsHub(scope, MutableStateFlow<Int?>(20), source::updates)
        val clock = launch { hub.readings(GpsUse.Speed).collect() }
        val recorder = launch { hub.readings(GpsUse.Recording).collect() }
        try {
            eventually { source.active.get() == 1 }
            recorder.cancelAndJoin()
            eventually { source.active.get() == 0 }
            assertTrue(clock.isActive)
        } finally { clock.cancelAndJoin(); recorder.cancelAndJoin(); scope.cancel() }
    }

    @Test fun reopeningHomeDoesNotWakeParkedRecorder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val hub = GpsHub(scope, MutableStateFlow<Int?>(0), source::updates, parkedAfterMs = 100)
        val recorder = launch { hub.readings(GpsUse.Recording).collect() }
        var home: kotlinx.coroutines.Job? = null
        try {
            eventually { source.intervals.lastOrNull() == 30_000L }
            home = launch { hub.readings(GpsUse.Speed).collect() }
            delay(50)
            assertEquals(listOf(1_000L, 30_000L), source.intervals.toList())
            home.cancelAndJoin()
            delay(50)
            assertEquals(listOf(1_000L, 30_000L), source.intervals.toList())
        } finally { home?.cancelAndJoin(); recorder.cancelAndJoin(); scope.cancel() }
    }
}
