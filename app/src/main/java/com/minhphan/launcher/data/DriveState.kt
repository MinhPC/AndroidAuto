package com.minhphan.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transformLatest

/** What the GPS says about the car right now. */
sealed interface DriveState {
    data object NoPermission : DriveState

    /** GPS is intentionally idle because no consumer currently needs it. */
    data object NotNeeded : DriveState

    /** Permission granted, but no recent fix (GPS still searching, or lost). */
    data object NoFix : DriveState

    data class Fix(val speedKmh: Float) : DriveState
}

private const val MOVING_START_KMH = 4f
private const val MOVING_STOP_KMH = 2f
private const val MPS_TO_KMH = 3.6f
private const val STALE_FIX_MS = 5_000L

/**
 * Whether the car counts as moving. Two thresholds (hysteresis) so GPS jitter while parked, which is
 * often a couple of km/h, does not make the car twitch between moving and stopped.
 */
fun nextMoving(wasMoving: Boolean, speedKmh: Float): Boolean =
    if (wasMoving) speedKmh > MOVING_STOP_KMH else speedKmh >= MOVING_START_KMH

/** Where the car's speed on Home comes from. */
enum class SpeedSource { Obd, Gps, None }

/** The car's speed as Home shows it, and where it came from. */
data class CarSpeed(val kmh: Float, val source: SpeedSource)

/**
 * The car's speed for Home: the car's own, from the OBD adapter, whenever it is connected and says it ([obdKmh]):
 * that is the speedometer's figure, several times a second, and it does not drop out in a tunnel or lag a second
 * behind as the GPS does. The GPS speed otherwise, and nothing without either.
 */
fun carSpeed(obdKmh: Int?, gps: DriveState): CarSpeed = when {
    obdKmh != null -> CarSpeed(obdKmh.coerceAtLeast(0).toFloat(), SpeedSource.Obd)
    gps is DriveState.Fix -> CarSpeed(gps.speedKmh, SpeedSource.Gps)
    else -> CarSpeed(0f, SpeedSource.None)
}

/**
 * Whether the car counts as moving at [speed]. The car's own speedometer reads a clean 0 standing still, so from the
 * OBD adapter any speed is moving; the GPS needs [nextMoving]'s two thresholds against its jitter.
 */
fun nextMoving(wasMoving: Boolean, speed: CarSpeed): Boolean = when (speed.source) {
    SpeedSource.Obd -> speed.kmh >= OBD_MOVING_KMH
    SpeedSource.Gps -> nextMoving(wasMoving, speed.kmh)
    SpeedSource.None -> false
}

private const val OBD_MOVING_KMH = 1f

/** GPS at the requested interval. Cancelling the sole upstream collector unregisters its listener. */
@SuppressLint("MissingPermission")
internal fun gpsLocations(context: Context, intervalMs: Long): Flow<Location> = callbackFlow {
    val manager = context.getSystemService(LocationManager::class.java)
    val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            trySend(location)
        }
    }
    try {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
    } catch (_: SecurityException) {
        close()
        return@callbackFlow
    } catch (_: IllegalArgumentException) { // this device has no GPS provider
        close()
        return@callbackFlow
    }
    awaitClose { manager.removeUpdates(listener) }
}

/** Speed from GPS, and back to [DriveState.NoFix] when no new fix arrives for a few seconds. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun driveStates(gps: GpsHub): Flow<DriveState> =
    gps.readings(GpsUse.Speed)
        .transformLatest { reading ->
            val fix = reading.location
            val state = when {
                !reading.requested -> DriveState.NotNeeded
                fix == null -> DriveState.NoFix
                else -> DriveState.Fix(if (fix.hasSpeed()) fix.speed * MPS_TO_KMH else 0f)
            }
            emit(state)
            if (state is DriveState.Fix) {
                delay(maxOf(STALE_FIX_MS, reading.intervalMs * 2))
                emit(DriveState.NoFix)
            }
        }
