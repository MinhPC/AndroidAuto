package com.minhphan.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.data.smartOrder
import com.minhphan.cloud.AccountState
import com.minhphan.launcher.sync.TripRecorderService
import com.minhphan.launcher.update.UpdateState

@Composable
fun LauncherApp(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val pinned by viewModel.favorites.collectAsStateWithLifecycle()
    val pinnedKeys = remember(pinned) { pinned.mapTo(HashSet()) { it.key } }
    val usage by viewModel.appUsage.collectAsStateWithLifecycle()
    // The dock's apps first, then the ones launched most, then the rest by name.
    val allApps = remember(apps, pinned, usage) { smartOrder(apps, { it.key }, pinned.map { it.key }, usage) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val connectivityRunning by viewModel.connectivityRunning.collectAsStateWithLifecycle()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAllApps by rememberSaveable { mutableStateOf(false) }
    var showRefuel by rememberSaveable { mutableStateOf(false) }
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val fuelBook by viewModel.fuelBook.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val pageOpen = showAllApps || showSettings || showDiagnostics
    val bluetooth = rememberBluetoothPermission()
    LaunchedEffect(bluetooth.granted) { viewModel.setBluetoothGranted(bluetooth.granted) }
    // Pressing Home while a page is open goes back to the home screen, like every launcher.
    LaunchedEffect(Unit) {
        viewModel.homeEvents.collect {
            showAllApps = false
            showSettings = false
            showDiagnostics = false
        }
    }
    // The left half is a dark photo in day and night alike, so the status bar icons stay light on Home.
    StatusBarIcons(dark = false)
    val context = LocalContext.current
    val account by viewModel.account.collectAsStateWithLifecycle()
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    LifecycleResumeEffect(Unit) {
        locationGranted = hasLocationPermission(context)
        onPauseOrDispose { }
    }
    // Trips are recorded, by a service that outlives this screen, whenever they can be sent to an account.
    val recordTrips = settings.syncTrips && account is AccountState.SignedIn && locationGranted
    // Android 13+ hides the recorder's notification, which tells the driver that data is being sent, until allowed.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recordTrips) {
        if (!recordTrips) {
            TripRecorderService.stop(context)
            return@LaunchedEffect
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedForNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TripRecorderService.start(context)
    }
    val homeStatus by rememberHomeStatus()
    val requestDefaultHome = rememberRequestDefaultHome()
    // The version line and the manual check live in Settings; Home only shows an update that needs the user.
    val updateNotice: @Composable () -> Unit = {
        if (updateState.needsAttention()) {
            UpdateBar(
                versionName = viewModel.versionName,
                state = updateState,
                onCheck = viewModel::checkForUpdate,
                onInstall = viewModel::installUpdate,
            )
        }
    }
    val homeBanner: @Composable () -> Unit = {
        if (!homeStatus.isDefault) {
            DefaultHomeBanner(
                otherHomePackage = homeStatus.otherHomePackage,
                onSetDefault = requestDefaultHome,
                onOpenCurrentLauncher = { openAppDetails(context, it) },
            )
        }
    }
    val obdPanel: @Composable (Modifier) -> Unit = { modifier ->
        ObdPanel(
            obd = viewModel.obd,
            trip = viewModel.tripMeter,
            totalKm = viewModel.totalKm,
            fuel = viewModel.fuelBook,
            fuelEstimate = viewModel.fuelEstimate,
            fields = settings.obdFields,
            recording = recording,
            bluetooth = bluetooth,
            onStartTrip = viewModel::startTrip,
            onStopTrip = viewModel::stopTrip,
            onRefuel = { showRefuel = true },
            onOpenSettings = { showSettings = true },
            modifier = modifier,
        )
    }
    val dock: @Composable () -> Unit = {
        AppDock(
            apps = pinned,
            onLaunch = viewModel::launch,
            onUnpin = viewModel::toggleFavorite,
            onAppInfo = viewModel::openAppInfo,
            onOpenAllApps = { showAllApps = true },
            onOpenSettings = { showSettings = true },
        )
    }

    // Surface also sets the default text colour, so unstyled text stays readable in day and night mode.
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth > maxHeight) {
                    // Landscape car display: the left half is the scene, edge to edge; the right half is the car's data.
                    Row(Modifier.fillMaxSize()) {
                        DrivingScene(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            covered = pageOpen,
                        )
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .systemBarsPadding()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                updateNotice()
                                homeBanner()
                                obdPanel(Modifier.weight(1f).fillMaxWidth())
                                dock()
                            }
                            StatusBarScrim(Modifier.align(Alignment.TopCenter))
                        }
                    }
                } else {
                    // Tall / portrait displays: the scene on top, the car's data below it.
                    Column(Modifier.fillMaxSize()) {
                        DrivingScene(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                            covered = pageOpen,
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            updateNotice()
                            homeBanner()
                            obdPanel(Modifier.weight(1f).fillMaxWidth())
                            dock()
                        }
                    }
                }
            }

            Overlay(showAllApps) {
                BackHandler { showAllApps = false }
                AllAppsScreen(
                    apps = allApps,
                    pinnedKeys = pinnedKeys,
                    onLaunch = { app -> showAllApps = false; viewModel.launch(app) },
                    onTogglePin = viewModel::toggleFavorite,
                    onAppInfo = viewModel::openAppInfo,
                    onClose = { showAllApps = false },
                )
            }

            Overlay(showSettings) {
                BackHandler { showSettings = false }
                SettingsScreen(
                    settings = settings,
                    apps = apps,
                    pinnedKeys = pinnedKeys,
                    viewModel = viewModel,
                    updateState = updateState,
                    bluetooth = bluetooth,
                    onOpenDiagnostics = { showDiagnostics = true },
                    onClose = { showSettings = false },
                )
            }

            if (showRefuel) {
                RefuelDialog(
                    book = fuelBook,
                    suggestedKm = remember { viewModel.suggestedRefuelKm() },
                    onSave = { liters, amountVnd, full, distanceKm ->
                        viewModel.recordRefuel(liters, amountVnd, full, distanceKm)
                        showRefuel = false
                    },
                    onDismiss = { showRefuel = false },
                )
            }

            Overlay(showDiagnostics) {
                BackHandler { showDiagnostics = false }
                DiagnosticsScreen(
                    obd = viewModel.obd,
                    connectivity = connectivity,
                    connectivityRunning = connectivityRunning,
                    onTestConnectivity = viewModel::testConnectivity,
                    onClose = { showDiagnostics = false },
                )
            }
        }
    }
}

private fun UpdateState.needsAttention() =
    this is UpdateState.Available || this is UpdateState.Downloading || this is UpdateState.Installing || this is UpdateState.Failed

/** A full-screen page that fades in with a short rise, and out again; nothing is composed while it is hidden. */
@Composable
private fun Overlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 14 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 14 },
    ) {
        content()
    }
}

/**
 * A dark fade over the top of the light right half of Home. The status bar icons stay light because the scene on
 * the left is a dark photo, and light icons would vanish on the light background; this puts them on a dark ground
 * that melts into the page below. Not needed at night, when the whole page is dark.
 */
@Composable
private fun StatusBarScrim(modifier: Modifier = Modifier) {
    if (LocalDarkTheme.current) return
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fade = 20.dp
    val ink = Color(0xFF0D1117)
    Box(
        modifier
            .fillMaxWidth()
            .height(inset + fade)
            .background(
                Brush.verticalGradient(
                    0f to ink.copy(alpha = 0.78f),
                    inset.value / (inset + fade).value to ink.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                ),
            ),
    )
}
