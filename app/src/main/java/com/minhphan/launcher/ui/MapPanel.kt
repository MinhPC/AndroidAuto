package com.minhphan.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.minhphan.launcher.BuildConfig
import com.minhphan.launcher.LauncherApplication
import com.minhphan.launcher.R
import com.minhphan.launcher.data.CarSpeed
import com.minhphan.launcher.data.GpsUse
import com.minhphan.launcher.data.LastLocationStore
import com.minhphan.launcher.data.SpeedSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The right half of Home as a Google map that follows the car, drawn as the Google Maps app draws it, light by day and
 * dark by night, with the traffic on it;
 * the car drawn from above, turned where it heads (and where it last headed while it stands). With [headingUp] the map
 * turns and tilts with the car and keeps it low on the screen, as Google Maps does while navigating; otherwise north
 * stays up. The compass button switches between the two ([onHeadingUpChange]). Dragging or pinching the map lets go of
 * the car; the button brings it back, and so does leaving the map alone for a while. The car's [speed] sits in a corner, as the
 * OBD panel's large figure is gone; the other button hands the position to the navigation app.
 *
 * [fullScreen] is the map as the whole of Home: no card round it, and its buttons kept [clearOf] what Home lays over
 * its bottom. [energySaving] leaves the buildings flat and
 * the camera stepping after the car rather than gliding.
 *
 * The map asks the GPS for every fix only while it is on screen ([covered] is false) and [locationGranted]; the scene
 * asks for the permission. It needs Google Play services and a Maps key in the build (see app/build.gradle.kts), and
 * says so in its place when either is missing.
 */
@Composable
fun MapPanel(
    speed: () -> CarSpeed,
    locationGranted: Boolean,
    covered: Boolean,
    headingUp: Boolean,
    onHeadingUpChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fullScreen: Boolean = false,
    clearOf: PaddingValues = PaddingValues(0.dp),
    energySaving: Boolean = false,
) {
    val context = LocalContext.current
    val playServices = remember(context) {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        if (fullScreen) {
            modifier.background(MaterialTheme.colorScheme.surface)
        } else {
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
        },
    ) {
        when {
            !BuildConfig.HAS_MAPS_KEY -> MapUnavailable(stringResource(R.string.map_no_key))
            !playServices -> MapUnavailable(stringResource(R.string.map_no_play_services))
            else -> {
                LaunchedEffect(Unit) { requestLatestRenderer(context) }
                // The renderer has to be settled before the first map, or that map comes up in the legacy one.
                val renderer = mapsRenderer.value
                if (renderer != null) {
                    CarMap(
                        speed, locationGranted, covered, headingUp, onHeadingUpChange, compact,
                        legacy = renderer == MapsInitializer.Renderer.LEGACY,
                        clearOf = clearOf,
                        energySaving = energySaving,
                    )
                }
            }
        }
    }
}

/** The renderer the Maps SDK settled on for this process; null until it says. */
private val mapsRenderer = mutableStateOf<MapsInitializer.Renderer?>(null)
private var rendererRequested = false

/**
 * Asks for the renderer the Google Maps app draws with (vector, with Google's own night colours); the SDK only
 * honours this before the first map and falls back to the legacy one where Play services cannot do better.
 */
private fun requestLatestRenderer(context: Context) {
    if (rendererRequested) return
    rendererRequested = true
    MapsInitializer.initialize(context.applicationContext, MapsInitializer.Renderer.LATEST) { mapsRenderer.value = it }
}

@Composable
private fun CarMap(
    speed: () -> CarSpeed,
    locationGranted: Boolean,
    covered: Boolean,
    headingUp: Boolean,
    onHeadingUpChange: (Boolean) -> Unit,
    compact: Boolean,
    legacy: Boolean,
    clearOf: PaddingValues,
    energySaving: Boolean,
) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val camera = rememberCameraPositionState {
        val last = LastLocationStore(context).current()
        position = if (last != null) {
            CameraPosition.fromLatLngZoom(LatLng(last.latitude, last.longitude), FOLLOW_ZOOM)
        } else {
            CameraPosition.fromLatLngZoom(VIETNAM, COUNTRY_ZOOM)
        }
    }
    val car = remember { MarkerState() }
    var location by remember { mutableStateOf<LatLng?>(null) }
    var bearing by remember { mutableStateOf<Float?>(null) }
    var following by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var glide by remember { mutableStateOf<Job?>(null) }
    // Read by the GPS collector below, which outlives the composition that started it.
    val turning by rememberUpdatedState(headingUp)
    val saving by rememberUpdatedState(energySaving)

    /**
     * Moves the camera to [at] smoothly, turned to the car's heading and tilted when heading up, flat with north up
     * otherwise; a gesture or the next fix may cut it short, which is fine. To save energy it jumps there instead:
     * one frame drawn for each fix, rather than the map redrawn all the time the car moves.
     */
    fun moveTo(at: LatLng, zoom: Float? = null) {
        glide?.cancel()
        glide = scope.launch {
            val position = CameraPosition.Builder()
                .target(at)
                .zoom(zoom ?: camera.position.zoom)
                .bearing(if (turning) bearing ?: camera.position.bearing else 0f)
                .tilt(if (turning) NAV_TILT else 0f)
                .build()
            val update = CameraUpdateFactory.newCameraPosition(position)
            if (saving) {
                camera.move(update)
                return@launch
            }
            try {
                camera.animate(update, GLIDE_MS)
            } catch (_: CancellationException) {
            }
        }
    }

    // A drag or a pinch lets go of the car; it is followed again once the map has been left alone for a while.
    LaunchedEffect(camera.isMoving) {
        if (camera.isMoving && camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) following = false
    }
    LaunchedEffect(following, camera.isMoving) {
        if (!following && !camera.isMoving) {
            delay(RECENTER_AFTER_MS)
            following = true
        }
    }
    LaunchedEffect(following, headingUp) {
        val at = location
        if (following && at != null) moveTo(at, FOLLOW_ZOOM.takeIf { camera.position.zoom < FOLLOW_ZOOM - 3 })
    }
    LaunchedEffect(locationGranted, covered) {
        if (!locationGranted || covered) return@LaunchedEffect
        val gps = (context.applicationContext as LauncherApplication).gpsHub
        gps.readings(GpsUse.Map).collect { reading ->
            val fix = reading.location ?: return@collect
            val at = LatLng(fix.latitude, fix.longitude)
            val previous = location
            val first = previous == null
            // A car standing still still gets a fix every second, a metre or so off: the camera stays, so the map is
            // not drawn again for nothing.
            val shifted = previous == null || metresBetween(previous, at) >= CAMERA_STEP_M
            val moving = fix.hasSpeed() && fix.speed >= HEADING_MIN_MPS
            if (moving && fix.hasBearing()) bearing = fix.bearing
            location = at
            car.position = at
            if (following && shifted) moveTo(at, FOLLOW_ZOOM.takeIf { first })
        }
    }

    val pad = if (compact) 8.dp else 12.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val clearBottom = clearOf.calculateBottomPadding()
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            // Heading up, the car sits low, as in Google Maps, to show more of the road ahead than behind.
            contentPadding = PaddingValues(
                top = if (headingUp) (maxHeight - clearBottom) * NAV_LIFT else 0.dp,
                bottom = clearBottom,
            ),
            // Google's own night colours, as in its Maps app; the legacy renderer, still on many head units, ignores
            // them, so it gets a navy style of ours instead.
            properties = remember(dark, legacy, energySaving) {
                MapProperties(
                    isBuildingEnabled = !energySaving,
                    isTrafficEnabled = true,
                    minZoomPreference = MIN_ZOOM,
                    mapStyleOptions = if (dark && legacy) MapStyleOptions.loadRawResourceStyle(context, R.raw.map_night) else null,
                )
            },
            mapColorScheme = if (dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
            uiSettings = remember {
                MapUiSettings(
                    compassEnabled = false,
                    indoorLevelPickerEnabled = false,
                    mapToolbarEnabled = false,
                    myLocationButtonEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                    zoomControlsEnabled = false,
                )
            },
        ) {
            if (location != null) {
                val icon = remember(context) { carIcon(context) }
                Marker(
                    state = car,
                    icon = icon,
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    rotation = bearing ?: 0f,
                    zIndex = 1f,
                )
            }
        }

        MapSpeed(speed, Modifier.align(Alignment.TopStart).padding(pad), compact)
        if (locationGranted && location == null) {
            MapChip(stringResource(R.string.map_waiting_gps), Modifier.align(Alignment.TopEnd).padding(pad))
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(bottom = clearBottom).padding(pad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompassButton(headingUp, { camera.position.bearing }) { onHeadingUpChange(!headingUp) }
            if (!following && location != null) RecenterButton { following = true }
            MapChip(stringResource(R.string.map_navigate), onClick = { openNavigation(context, location) })
        }
    }
}

private fun metresBetween(a: LatLng, b: LatLng): Float {
    val result = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
    return result[0]
}

/** In the map's place, why there is none. */
@Composable
private fun MapUnavailable(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The car's speed in a corner of the map; read here only, so a new speed redraws this and nothing else. */
@Composable
private fun MapSpeed(speed: () -> CarSpeed, modifier: Modifier, compact: Boolean) {
    val current = speed()
    val known = current.source != SpeedSource.None
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (known) current.kmh.roundToInt().toString() else "--",
            fontSize = if (compact) 30.sp else 40.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (current.source == SpeedSource.Gps) "km/h · GPS" else "km/h",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapChip(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A round button with a compass needle that points north on the map ([mapBearing] read only here, as it changes with
 * every step of the camera): switches between the map turning with the car and north staying up.
 */
@Composable
private fun CompassButton(headingUp: Boolean, mapBearing: () -> Float, onClick: () -> Unit) {
    val description = stringResource(if (headingUp) R.string.map_heading_up else R.string.map_north_up)
    val north = Color(0xFFE53935)
    val south = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ComposeCanvas(Modifier.size(28.dp)) {
            rotate(-mapBearing()) {
                val c = center
                val half = size.minDimension * 0.42f
                val width = size.minDimension * 0.16f
                fun needle(tipY: Float, color: Color) = drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, tipY)
                        lineTo(c.x + width, c.y)
                        lineTo(c.x - width, c.y)
                        close()
                    },
                    color,
                )
                needle(c.y - half, north)
                needle(c.y + half, south)
            }
        }
    }
}

/** A round button with a crosshair: back to following the car. */
@Composable
private fun RecenterButton(onClick: () -> Unit) {
    val description = stringResource(R.string.map_recenter)
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ComposeCanvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2
            val stroke = 2.dp.toPx()
            drawCircle(color, radius = r * 0.6f, style = Stroke(stroke))
            drawCircle(color, radius = r * 0.22f)
            val c = center
            drawLine(color, Offset(c.x, 0f), Offset(c.x, r * 0.4f), stroke)
            drawLine(color, Offset(c.x, size.height), Offset(c.x, size.height - r * 0.4f), stroke)
            drawLine(color, Offset(0f, c.y), Offset(r * 0.4f, c.y), stroke)
            drawLine(color, Offset(size.width, c.y), Offset(size.width - r * 0.4f, c.y), stroke)
        }
    }
}

/** Hands the car's position to the navigation app (Google Maps, Waze, ...), to search and route from there. */
private fun openNavigation(context: Context, at: LatLng?) {
    val uri = if (at != null) Uri.parse("geo:${at.latitude},${at.longitude}") else Uri.parse("geo:0,0")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** The car's marker: [R.drawable.map_car], nose up; the map turns it to the heading. */
private fun carIcon(context: Context): BitmapDescriptor =
    BitmapDescriptorFactory.fromBitmap(ContextCompat.getDrawable(context, R.drawable.map_car)!!.toBitmap())

private const val MIN_ZOOM = 4f
private const val FOLLOW_ZOOM = 16.5f

/** How far the camera leans back while heading up, in degrees; Google Maps navigates at about this. */
private const val NAV_TILT = 45f

/** While heading up, the share of the map's height kept above the car's centre line, pushing the car down. */
private const val NAV_LIFT = 0.4f
private const val COUNTRY_ZOOM = 6f
private val VIETNAM = LatLng(16.0, 106.0)

/** Below this the camera does not follow the car's fix: the GPS's wander at a standstill. */
private const val CAMERA_STEP_M = 2f

/** How long a step of the camera after the car takes; a little under the GPS's second between fixes. */
private const val GLIDE_MS = 800

/** How long the map stays where the driver moved it before it goes back to the car. */
private const val RECENTER_AFTER_MS = 15_000L

/** Below this (about 5 km/h) the GPS's heading wanders, so the car keeps the one it had. */
private const val HEADING_MIN_MPS = 1.4f
