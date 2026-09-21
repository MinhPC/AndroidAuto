package com.minhphan.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.launcher.data.DriveState
import com.minhphan.launcher.data.driveStates
import com.minhphan.launcher.data.nextMoving
import kotlinx.coroutines.flow.flowOf

// Android 12+ requires asking for both; only FINE gives the GPS speed we need.
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private val OverlayShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 2f), blurRadius = 8f)

/**
 * The car seen from behind on a mountain road, with the clock and date over it. The road streams
 * towards the viewer at the GPS speed while the car is moving; when it is stopped everything stands still, brake
 * lights on. A dot in the corner is green while the GPS has a fix and red while it has none. Needs the
 * location permission, which it asks for once on first use.
 */
@Composable
fun DrivingScene(modifier: Modifier = Modifier) {
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

    val driveFlow = remember(granted) { if (granted) driveStates(context) else flowOf<DriveState>(DriveState.NoPermission) }
    val drive by driveFlow.collectAsStateWithLifecycle(initialValue = DriveState.NoFix)

    val gpsSpeed = (drive as? DriveState.Fix)?.speedKmh ?: 0f
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(gpsSpeed) { moving = nextMoving(moving, gpsSpeed) }
    // Ease the road speed up and down so the car does not lurch when the GPS speed jumps.
    val roadSpeed by animateFloatAsState(if (moving) gpsSpeed else 0f, tween(durationMillis = 700), label = "roadSpeed")
    val dark = LocalDarkTheme.current
    val now by rememberNow()

    // Everything is sized from the scene height, so the layout matches the mock-up on any display. The photo is
    // scaled to cover the scene and is bigger than it in one direction, hence the clip.
    BoxWithConstraints(modifier.clipToBounds()) {
        val h = maxHeight
        val w = maxWidth
        // The scene runs under the system bars; only the text has to stay clear of them.
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val density = LocalDensity.current
        fun size(fraction: Float) = with(density) { (h * fraction).toSp() }
        val textColor = Color.White
        val softColor = Color.White.copy(alpha = 0.75f)

        RoadLayer(speedKmh = { roadSpeed }, active = roadSpeed > 0.2f, dark = dark, modifier = Modifier.fillMaxSize())
        AudiCarLayer(braking = !moving, dark = dark, modifier = Modifier.fillMaxSize())

        // The clock and date, centred at the top.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = maxOf(topInset, h * 0.03f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock(
                now = now,
                timeSize = size(0.12f),
                dateSize = size(0.045f),
                color = textColor,
                dateColor = softColor,
                shadow = OverlayShadow,
            )
        }

        GpsDot(
            hasSignal = drive is DriveState.Fix,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
        )

        // Without the permission there will never be a fix, so say why and offer the way out, above the dot.
        if (drive is DriveState.NoPermission) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .widthIn(max = w * 0.34f)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + GpsDotSize + 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.drive_no_permission),
                    style = MaterialTheme.typography.bodyMedium.copy(color = textColor, shadow = OverlayShadow),
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
    }
}

private val GpsDotSize = 20.dp
private val GpsGreen = Color(0xFF3DDC84)
private val GpsRed = Color(0xFFFF4545)

/** Green while the GPS has a recent fix, red while it has none; the white ring keeps it visible on any photo. */
@Composable
private fun GpsDot(hasSignal: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(if (hasSignal) R.string.gps_signal_ok else R.string.gps_signal_none)
    Box(
        modifier
            .size(GpsDotSize)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(if (hasSignal) GpsGreen else GpsRed)
            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
    )
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
