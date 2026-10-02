package com.minhphan.launcher.data

import android.content.Context
import android.location.Location
import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

internal enum class GpsUse { Speed, Recording, Map }
internal enum class GpsMode(val intervalMs: Long) { Off(0), Fast(1_000), Parked(30_000) }
internal data class GpsReading(val location: Location?, val requested: Boolean, val intervalMs: Long = 1_000)

/** [map]: a map on screen follows the car, so it needs every fix whatever OBD knows. */
internal fun gpsMode(needsSpeed: Boolean, recording: Boolean, obdKmh: Int?, parkedLong: Boolean, map: Boolean = false): GpsMode = when {
    map -> GpsMode.Fast
    !recording && (!needsSpeed || obdKmh != null) -> GpsMode.Off
    recording && obdKmh == 0 && parkedLong -> GpsMode.Parked
    else -> GpsMode.Fast
}

/**
 * The car's speed as far as the GPS needs to know it; null when OBD cannot say, so the GPS takes over.
 *
 * A car that has gone quiet (ignition off) stands at 0 - unless it was last heard moving: an ECU that stops answering
 * on the road is not a parked car, and slowing the GPS then would lose the route. A link that drops keeps the last
 * speed for as long as the adapter still vouches for it ([ObdState.Connecting.lastExpiresInMs]), so a Bluetooth
 * hiccup does not cold-start the GPS; after that it is null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun obdSpeedForGps(states: Flow<ObdState>): Flow<Int?> = flow {
    var lastHeard: Int? = null
    suspend fun FlowCollector<Int?>.recent(last: ObdValues?, expiresInMs: Long) {
        val kmh = last?.speedKmh?.coerceAtLeast(0)
        if (kmh != null && expiresInMs > 0) {
            emit(kmh)
            delay(expiresInMs)
        }
        emit(null)
    }
    emitAll(states.transformLatest { state ->
        when (state) {
            is ObdState.Connected -> {
                val kmh = state.values.speedKmh?.coerceAtLeast(0)
                if (kmh != null) lastHeard = kmh
                emit(kmh)
            }
            is ObdState.Connecting ->
                if (state.carSilent) emit(silentSpeed(lastHeard)) else recent(state.last, state.lastExpiresInMs)
            is ObdState.Problem ->
                if (state.problem == ObdProblem.NoVehicle) emit(silentSpeed(lastHeard)) else recent(state.last, state.lastExpiresInMs)
        }
    })
}.distinctUntilChanged()

private fun silentSpeed(lastHeard: Int?): Int? = if ((lastHeard ?: 0) == 0) 0 else null

/** One location listener for all consumers, released immediately when its last client leaves. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GpsHub(
    scope: CoroutineScope,
    obdSpeed: Flow<Int?>,
    source: (Long) -> Flow<Location>,
    parkedAfterMs: Long = 60_000,
) {
    constructor(context: Context, scope: CoroutineScope, obd: StateFlow<ObdState>) : this(
        scope,
        obdSpeedForGps(obd),
        { interval -> gpsLocations(context.applicationContext, interval) },
    )

    private val ids = AtomicInteger()
    private val clients = MutableStateFlow<Map<Int, GpsUse>>(emptyMap())
    private data class Demand(val speed: Boolean, val recording: Boolean, val obdKmh: Int?, val map: Boolean)

    private val readings = combine(clients, obdSpeed) { uses, kmh ->
        val recording = GpsUse.Recording in uses.values
        // Joining/leaving the Home screen must not restart the parked timer during recording.
        Demand(!recording && GpsUse.Speed in uses.values, recording, kmh, GpsUse.Map in uses.values)
    }.distinctUntilChanged().transformLatest { demand ->
        emit(gpsMode(demand.speed, demand.recording, demand.obdKmh, parkedLong = false, map = demand.map))
        // Only OBD can reliably wake GPS as soon as the parked car moves again.
        // Without OBD we retain fast GPS even at a standstill, avoiding a delayed speed reading.
        if (demand.recording && !demand.map && demand.obdKmh == 0) {
            delay(parkedAfterMs)
            emit(GpsMode.Parked)
        }
    }.distinctUntilChanged().flatMapLatest { mode ->
        if (mode == GpsMode.Off) flowOf(GpsReading(null, requested = false))
        else source(mode.intervalMs)
            .map { GpsReading(it, requested = true, intervalMs = mode.intervalMs) }
            .onStart { emit(GpsReading(null, requested = true, intervalMs = mode.intervalMs)) }
    }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 1)

    fun readings(use: GpsUse): Flow<GpsReading> = flow {
        val id = ids.incrementAndGet()
        clients.update { it + (id to use) }
        try {
            emitAll(readings)
        } finally {
            clients.update { it - id }
        }
    }
}
