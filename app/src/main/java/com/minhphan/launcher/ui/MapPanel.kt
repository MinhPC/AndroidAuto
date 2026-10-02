package com.minhphan.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
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
 * The right half of Home as a Google map that follows the car, light by day and dark by night, with the traffic on it;
 * the car an arrow pointing where it heads (a dot while it stands). Dragging or pinching the map lets go of the car;
 * the button brings it back, and so does leaving the map alone for a while. The car's [speed] sits in a corner, as the
 * OBD panel's large figure is gone; the other button hands the position to the navigation app.
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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val playServices = remember(context) {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
    ) {
        when {
            !BuildConfig.HAS_MAPS_KEY -> MapUnavailable(stringResource(R.string.map_no_key))
            !playServices -> MapUnavailable(stringResource(R.string.map_no_play_services))
            else -> CarMap(speed, locationGranted, covered, compact)
        }
    }
}

@Composable
private fun CarMap(speed: () -> CarSpeed, locationGranted: Boolean, covered: Boolean, compact: Boolean) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
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

    /** Moves the camera to [at] smoothly; a gesture or the next fix may cut it short, which is fine. */
    fun moveTo(at: LatLng, zoom: Float? = null) {
        glide?.cancel()
        glide = scope.launch {
            val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(at, zoom) else CameraUpdateFactory.newLatLng(at)
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
    LaunchedEffect(following) {
        val at = location
        if (following && at != null) moveTo(at, FOLLOW_ZOOM.takeIf { camera.position.zoom < FOLLOW_ZOOM - 3 })
    }
    LaunchedEffect(locationGranted, covered) {
        if (!locationGranted || covered) return@LaunchedEffect
        val gps = (context.applicationContext as LauncherApplication).gpsHub
        gps.readings(GpsUse.Map).collect { reading ->
            val fix = reading.location ?: return@collect
            val at = LatLng(fix.latitude, fix.longitude)
            val first = location == null
            val moving = fix.hasSpeed() && fix.speed >= HEADING_MIN_MPS
            bearing = when {
                !moving -> null
                fix.hasBearing() -> fix.bearing
                else -> bearing
            }
            location = at
            car.position = at
            if (following) moveTo(at, FOLLOW_ZOOM.takeIf { first })
        }
    }

    val pad = if (compact) 8.dp else 12.dp
    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = remember { MapProperties(isTrafficEnabled = true, minZoomPreference = MIN_ZOOM) },
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
            mapColorScheme = if (dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        ) {
            if (location != null) {
                val heading = bearing
                val icon = remember(heading != null, accent) { carIcon(context, accent, arrow = heading != null) }
                Marker(
                    state = car,
                    icon = icon,
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    rotation = heading ?: 0f,
                    zIndex = 1f,
                )
            }
        }

        MapSpeed(speed, Modifier.align(Alignment.TopStart).padding(pad), compact)
        if (locationGranted && location == null) {
            MapChip(stringResource(R.string.map_waiting_gps), Modifier.align(Alignment.TopEnd).padding(pad))
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(pad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!following && location != null) RecenterButton { following = true }
            MapChip(stringResource(R.string.map_navigate), onClick = { openNavigation(context, location) })
        }
    }
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

/** The car's marker: an arrow pointing up (the map turns it to the heading) or a dot, in [color] with a white edge. */
private fun carIcon(context: Context, color: Int, arrow: Boolean): BitmapDescriptor {
    val unit = context.resources.displayMetrics.density
    val size = (40 * unit).roundToInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * unit; this.color = 0xFFFFFFFF.toInt() }
    val c = size / 2f
    if (arrow) {
        val r = 16 * unit
        val path = Path().apply {
            moveTo(c, c - r)
            lineTo(c + r * 0.75f, c + r)
            lineTo(c, c + r * 0.5f)
            lineTo(c - r * 0.75f, c + r)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)
    } else {
        canvas.drawCircle(c, c, 9 * unit, fill)
        canvas.drawCircle(c, c, 9 * unit, edge)
    }
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private const val MIN_ZOOM = 4f
private const val FOLLOW_ZOOM = 16.5f
private const val COUNTRY_ZOOM = 6f
private val VIETNAM = LatLng(16.0, 106.0)

/** How long a step of the camera after the car takes; a little under the GPS's second between fixes. */
private const val GLIDE_MS = 800

/** How long the map stays where the driver moved it before it goes back to the car. */
private const val RECENTER_AFTER_MS = 15_000L

/** Below this (about 5 km/h) the GPS's heading wanders, so the car shows as a dot. */
private const val HEADING_MIN_MPS = 1.4f
