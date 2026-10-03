package com.minhphan.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.launcher.LauncherApplication
import com.minhphan.launcher.data.CarSpeed
import com.minhphan.launcher.data.DriveState
import com.minhphan.launcher.data.SpeedSource
import com.minhphan.launcher.data.carSpeed
import com.minhphan.launcher.data.driveStates
import com.minhphan.launcher.data.nextMoving
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.ObdState
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Android 12+ requires asking for both; only FINE gives the GPS speed we need.
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private val OverlayShadow = Shadow(color = Color.Black.copy(alpha = 0.45f), offset = Offset(0f, 1f), blurRadius = 3f)

/**
 * The Civic seen from behind on an empty road, with the time large in the sky and the date under it, a
 * round button at the bottom right that opens the trip computer ([onOpenTrip]; a red dot on it while a trip is being
 * counted), and at the bottom left a dot that is green while the GPS has a fix. The speed is the car's
 * own from [obd] while the adapter is connected, the GPS speed otherwise ([carSpeed]); it goes to [onSpeed], for the
 * panel of the car's data to show, and the car rides the road at it, eased by the scene so the steps between readings
 * do not show, and when it is stopped everything stands still.
 * Needs the location permission, which it asks for once on first use. While a full-screen page is [covered] over it
 * the scene stops drawing, since nobody can see it and a moving car would otherwise animate every frame for nothing.
 *
 * Without the [road] (over the full-screen map) there is no scene at all, so nothing animates: the time and the date
 * sit small on a card at the top, and the trip button by the GPS dot in the bottom left corner.
 */
@Composable
fun DrivingScene(
    obd: StateFlow<ObdState>,
    tripRunning: Boolean,
    onOpenTrip: () -> Unit,
    onSpeed: (CarSpeed) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    covered: Boolean = false,
    focusFraction: Float = 1f,
    energySaving: Boolean = false,
    road: Boolean = true,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocationPermission(context)
        if (!granted) denied = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }
    // The user may also grant it from Settings and come back.
    LifecycleResumeEffect(Unit) {
        granted = hasLocationPermission(context)
        onPauseOrDispose { }
    }

    val gps = remember(context) { (context.applicationContext as LauncherApplication).gpsHub }
    val driveFlow = remember(granted, covered, gps) {
        when {
            !granted -> flowOf<DriveState>(DriveState.NoPermission)
            covered -> flowOf<DriveState>(DriveState.NotNeeded)
            else -> driveStates(gps)
        }
    }
    val drive by driveFlow.collectAsStateWithLifecycle(initialValue = DriveState.NoFix)

    // Only the speed is taken from the adapter here, so its other readings do not redraw the scene.
    // Starting from the reading already there, taken once and not on every recomposition.
    val obdSpeedNow = remember(obd) { (obd.value as? ObdState.Connected)?.values?.speedKmh }
    val obdSpeed by remember(obd) { obd.map { (it as? ObdState.Connected)?.values?.speedKmh }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = obdSpeedNow)
    val speed = carSpeed(obdSpeed, drive)
    var moving by remember { mutableStateOf(false) }
    // Read by the scene frame by frame, through a lambda that stays the same, so a new reading does not recompose it.
    val currentKmh = rememberUpdatedState(speed.kmh)
    val targetKmh = remember { { currentKmh.value } }
    val hasFix = drive is DriveState.Fix
    // Whether the car moves, and the speed for the panel of the car's data, beside the engine speed. Both are the same
    // for the same speed, so they are simply brought up to date after each composition, with no coroutine to start
    // for every reading.
    SideEffect {
        moving = nextMoving(moving, speed)
        onSpeed(speed)
    }
    val now by rememberNow()

    // The type is sized from the scene height, so the layout matches the mock-up on any display.
    // No background of its own: the road covers it all, and filling it first would only cost the GPU another pass.
    BoxWithConstraints(modifier.clip(shape)) {
        val h = maxHeight
        val w = maxWidth * focusFraction
        val density = LocalDensity.current
        fun size(fraction: Float) = with(density) { (h * fraction).toSp() }
        val textColor = Color.White
        val softColor = Color.White.copy(alpha = 0.96f)

        if (road) {
            CivicScene(
                targetSpeedKmh = targetKmh,
                quickSpeed = speed.source == SpeedSource.Obd,
                moving = moving,
                covered = covered,
                modifier = Modifier.fillMaxSize(),
                focusFraction = focusFraction,
                energySaving = energySaving,
            )
        }

        // The time, the GPS dot and the trip button keep to the part of the scene where the car is. The scene runs on
        // under the navigation bar, but they keep above it, level with the bottom of the dock beside them.
        Box(Modifier.fillMaxHeight().width(w)) {
            val bottomBar = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            if (road) {
                // The time, large, and the date under it, in the sky over the road.
                Clock(
                    now = now,
                    timeSize = size(0.13f),
                    dateSize = size(0.045f),
                    lunarSize = size(0.032f),
                    color = textColor,
                    dateColor = softColor,
                    shadow = OverlayShadow,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.035f),
                )
            } else {
                // Over the map, small on a card of its own, so the streets under it show.
                Clock(
                    now = now,
                    timeSize = 34.sp,
                    dateSize = 14.sp,
                    lunarSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    dateColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }

            // In the corner, clear of the car, which sits low in the scene.
            Column(
                Modifier.align(Alignment.BottomStart).then(bottomBar).padding(start = 16.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Without the permission there will never be a fix, so say why and offer the way out.
                if (drive is DriveState.NoPermission) {
                    Column(
                        Modifier
                            .widthIn(max = w * 0.8f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.drive_no_permission),
                            style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                // After a refusal Android will not ask again, so send the user to the app settings.
                                if (denied) openAppDetails(context, context.packageName)
                                else permissionLauncher.launch(LOCATION_PERMISSIONS)
                            },
                            modifier = Modifier.padding(top = 8.dp).heightIn(min = 56.dp),
                        ) {
                            Text(stringResource(if (denied) R.string.open_app_settings else R.string.grant_location))
                        }
                    }
                }
                GpsDot(hasFix, idle = drive == DriveState.NotNeeded)
                // Over the map its corner on the right holds the map's own buttons.
                if (!road) TripButton(running = tripRunning, onClick = onOpenTrip)
            }

            if (road) {
                TripButton(
                    running = tripRunning,
                    onClick = onOpenTrip,
                    modifier = Modifier.align(Alignment.BottomEnd).then(bottomBar).padding(end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

private val GpsRed = Color(0xFFFF4545)
private val GpsGreen = Color(0xFF34C759)

/**
 * Whether the GPS has a fix: a green dot when it has, red when not, ringed dark so it reads on the road. Without a fix
 * the dot blinks for a while ([blinkWhile]), the ring stays.
 */
@Composable
private fun GpsDot(hasFix: Boolean, idle: Boolean) {
    val description = stringResource(when {
        idle -> R.string.gps_idle
        hasFix -> R.string.gps_signal_ok
        else -> R.string.gps_signal_none
    })
    Box(
        Modifier
            .semantics { contentDescription = description }
            .size(14.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(2.dp),
    ) {
        Box(Modifier.fillMaxSize().blinkWhile(!hasFix && !idle).clip(CircleShape)
            .background(if (idle) Color(0xFF8E9BAE) else if (hasFix) GpsGreen else GpsRed))
    }
}

/**
 * A round dark glass button over the road with the route sign; while a trip is counted a red dot sits on its corner.
 * It says which in words only to accessibility services.
 */
@Composable
private fun TripButton(running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(if (running) R.string.trip_button_running else R.string.trip_button)
    Box(modifier) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D1117).copy(alpha = 0.62f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_route), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        if (running) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(12.dp).clip(CircleShape).background(GpsRed))
        }
    }
}

internal fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
