package com.minhphan.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.launcher.data.DriveState
import com.minhphan.launcher.data.driveStates
import com.minhphan.launcher.data.nextMoving
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

// Android 12+ requires asking for both; only FINE gives the GPS speed we need.
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private val OverlayShadow =Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 2f), blurRadius = 8f)

/**
 * The car on the road: it drives (the road scrolls with the GPS speed) while the car is moving and stands
 * still, brake lights on, otherwise. Needs the location permission, which it asks for once on first use.
 */
@Composable
fun DrivingScene(onOpenDiagnostics: () -> Unit, modifier: Modifier = Modifier) {
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
    val dark = isSystemInDarkTheme()

    Box(modifier.clip(RoundedCornerShape(28.dp))) {
        RoadLayer(speedKmh = { roadSpeed }, active = roadSpeed > 0.2f, dark = dark, modifier = Modifier.fillMaxSize())
        CivicCarLayer(braking = !moving, lightsOn = dark, modifier = Modifier.fillMaxSize())

        Clock(
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
            color = Color.White,
            shadow = OverlayShadow,
        )

        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = (if (moving) gpsSpeed.roundToInt() else 0).toString(),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Medium, color = Color.White, shadow = OverlayShadow,
                    ),
                )
                Text(
                    text = stringResource(R.string.speed_unit),
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, shadow = OverlayShadow),
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
            }
            Text(
                text = stringResource(
                    when {
                        drive is DriveState.NoPermission -> R.string.drive_no_permission
                        drive is DriveState.NoFix -> R.string.drive_searching
                        moving -> R.string.drive_moving
                        else -> R.string.drive_stopped
                    },
                ),
                style = MaterialTheme.typography.titleMedium.copy(color = Color.White, shadow = OverlayShadow),
            )
            if (drive is DriveState.NoPermission) {
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

        TextButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.diagnostics_button), color = Color.White.copy(alpha = 0.7f))
        }
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
