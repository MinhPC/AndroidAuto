package com.minhphan.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlin.math.abs

/** About 11 km: sunrise and sunset move by well under a minute over that distance, so a closer position is not worth a write. */
private const val SAVE_MOVE_DEGREES = 0.1

/** Whether [to] is far enough from the saved position [from] to be saved over it. */
internal fun movedEnoughToSave(from: Coordinates, to: Coordinates): Boolean =
    abs(from.latitude - to.latitude) >= SAVE_MOVE_DEGREES || abs(from.longitude - to.longitude) >= SAVE_MOVE_DEGREES

/**
 * The car's last known position, for the sunrise and sunset that decide day or night. It is read from the
 * system's last GPS / passive fix, and remembered across restarts so the theme is right straight after boot,
 * before the GPS has a fix. Returns null until a position has ever been seen.
 */
class LastLocationStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    fun current(): Coordinates? {
        val manager = appContext.getSystemService(LocationManager::class.java)
        val fix = LAST_KNOWN_PROVIDERS.firstNotNullOfOrNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) { // no location permission yet
                null
            } catch (_: IllegalArgumentException) { // this device has no such provider
                null
            }
        }
        val saved = saved()
        if (fix != null) {
            val coordinates = Coordinates(fix.latitude, fix.longitude)
            // Read once a minute while driving: rewriting the file every time would wear the head unit's flash.
            if (saved == null || movedEnoughToSave(saved, coordinates)) {
                prefs.edit().putString(KEY_LAT, coordinates.latitude.toString()).putString(KEY_LON, coordinates.longitude.toString()).apply()
            }
            return coordinates
        }
        return saved
    }

    private fun saved(): Coordinates? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        return Coordinates(lat, lon)
    }

    private companion object {
        val LAST_KNOWN_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        const val KEY_LAT = "last_lat"
        const val KEY_LON = "last_lon"
    }
}
