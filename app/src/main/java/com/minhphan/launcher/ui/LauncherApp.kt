package com.minhphan.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.MAX_DOCK_APPS
import com.minhphan.launcher.data.resolveDock
import com.minhphan.launcher.data.CarSpeed
import com.minhphan.launcher.data.HomeLayout
import com.minhphan.launcher.data.HomePanel
import com.minhphan.launcher.data.SpeedSource
import com.minhphan.cloud.AccountState
import com.minhphan.launcher.sync.TripRecorderService
import com.minhphan.launcher.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun LauncherApp(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val usage by viewModel.appUsage.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val connectivityRunning by viewModel.connectivityRunning.collectAsStateWithLifecycle()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAllApps by rememberSaveable { mutableStateOf(false) }
    var showTrip by rememberSaveable { mutableStateOf(false) }
    val tripRunningNow = remember { viewModel.drives.value.current != null }
    val tripRunning by remember { viewModel.drives.map { it.current != null }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = tripRunningNow)
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val pageOpen = showAllApps || showSettings || showDiagnostics
    // Once a full-screen page has slid all the way in, Home under it is not drawn at all (a layer with no alpha is
    // skipped), so the page does not pay for drawing both; it shows again the moment the page starts to close.
    var homeHidden by remember { mutableStateOf(false) }
    LaunchedEffect(pageOpen) {
        if (pageOpen) {
            delay(PAGE_SETTLED_MS)
            homeHidden = true
        } else {
            homeHidden = false
        }
    }
    val bluetooth = rememberBluetoothPermission()
    LaunchedEffect(bluetooth.granted) { viewModel.setBluetoothGranted(bluetooth.granted) }
    // Pressing Home while a page is open goes back to the home screen, like every launcher.
    LaunchedEffect(Unit) {
        viewModel.homeEvents.collect {
            showAllApps = false
            showSettings = false
            showDiagnostics = false
            showTrip = false
        }
    }
    // Home draws its own bar along the top and hides Android's (see MainActivity); a swipe down still shows it, over
    // the page's own colour.
    StatusBarIcons(dark = !LocalDarkTheme.current)
    val context = LocalContext.current
    val account by viewModel.account.collectAsStateWithLifecycle()
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    LifecycleResumeEffect(Unit) {
        locationGranted = hasLocationPermission(context)
        onPauseOrDispose { }
    }
    // A service that outlives this screen counts the journey's time on the move while one runs, and sends trips to the
    // account when they can be; it runs only while there is either to do, and is started again when that changes, so it
    // takes up or drops each.
    val sendTrips = settings.syncTrips && account is AccountState.SignedIn
    // Android 13+ hides the recorder's notification, which tells the driver what it does, until allowed.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(locationGranted, sendTrips, tripRunning) {
        if (!locationGranted || !(sendTrips || tripRunning)) {
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
    // The car's speed, worked out by the scene (the adapter's, or the GPS's) and shown on the panel; read only there.
    val carSpeed = remember { mutableStateOf(CarSpeed(0f, SpeedSource.None)) }
    // The right half: a map that follows the car, or the car's data, as the driver picked in Settings.
    val sidePanel: @Composable (Modifier, Boolean) -> Unit = { modifier, compact ->
        if (settings.homePanel == HomePanel.Map) {
            MapPanel(
                speed = { carSpeed.value },
                locationGranted = locationGranted,
                covered = pageOpen,
                headingUp = settings.mapHeadingUp,
                onHeadingUpChange = viewModel::setMapHeadingUp,
                modifier = modifier,
                compact = compact,
                energySaving = settings.energySaving,
            )
        } else {
            ObdPanel(
                obd = viewModel.obd,
                speed = { carSpeed.value },
                fields = settings.shownObdFields,
                bluetooth = bluetooth,
                onOpenSettings = { showSettings = true },
                modifier = modifier,
                compact = compact,
            )
        }
    }
    val mapHome = settings.homeLayout == HomeLayout.Map
    val scene: @Composable (Modifier, Shape, Float) -> Unit = { modifier, shape, focusFraction ->
        DrivingScene(
            obd = viewModel.obd,
            tripRunning = tripRunning,
            onOpenTrip = { showTrip = true },
            onSpeed = { carSpeed.value = it },
            modifier = modifier,
            shape = shape,
            covered = pageOpen,
            focusFraction = focusFraction,
            energySaving = settings.energySaving,
            road = !mapHome,
        )
    }
    // Worked out again only when the driver's choice or the installed apps change.
    val dockEntries = remember(settings.dockApps, apps) { resolveDock(settings.dockApps, apps) }
    val dock: @Composable (Boolean) -> Unit = { compact ->
        HomeDock(
            entries = dockEntries,
            onLaunch = viewModel::launch,
            compact = compact,
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
            // Under Android's status bar, the scene from edge to edge and down to the bottom of the screen, the car in its
            // left half. Over its right half, the car's data as blocks
            // of glass inside the page's margin, with the dock under them, so the road runs on under and between them.
            // The dock keeps clear of the navigation bar itself, so its bar can run on under it.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (homeHidden) 0f else 1f }
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                // A head unit at 240 dpi (1024 x 600, 1280 x 720) leaves only 400 to 480 dp under the status bar: the
                // panel and the dock then give up their spare height, so the figures keep theirs. What is left is also
                // above the navigation bar, if the head unit shows one along the bottom: the dock keeps clear of it.
                val navigationBar = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
                val compact = maxHeight - navigationBar < CompactBelow
                val margin = if (compact) CompactPageMargin else PageMargin
                if (mapHome) {
                    // The map over the whole screen. The scene, here without its road, still works out the speed and
                    // asks for the location; it only adds the time, the GPS dot and the trip button over the map.
                    val density = LocalDensity.current
                    var dockHeight by remember { mutableStateOf(0.dp) }
                    MapPanel(
                        speed = { carSpeed.value },
                        locationGranted = locationGranted,
                        covered = pageOpen,
                        headingUp = settings.mapHeadingUp,
                        onHeadingUpChange = viewModel::setMapHeadingUp,
                        modifier = Modifier.fillMaxSize(),
                        compact = compact,
                        fullScreen = true,
                        clearOf = PaddingValues(bottom = dockHeight),
                        energySaving = settings.energySaving,
                    )
                    scene(Modifier.fillMaxSize(), RectangleShape, 1f)
                    val side = if (maxWidth > maxHeight) 1f - SCENE_SHARE else 1f
                    Column(
                        Modifier.align(Alignment.TopEnd).fillMaxWidth(side).padding(top = 64.dp).padding(margin),
                        verticalArrangement = Arrangement.spacedBy(margin),
                    ) {
                        updateNotice()
                        homeBanner()
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxWidth(side)
                            .onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
                    ) {
                        dock(compact)
                    }
                } else if (maxWidth > maxHeight) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            scene(Modifier.fillMaxSize(), RectangleShape, SCENE_SHARE)
                            Row(Modifier.fillMaxSize()) {
                                Spacer(Modifier.weight(SCENE_SHARE))
                                Column(Modifier.weight(1f - SCENE_SHARE).fillMaxHeight()) {
                                    Column(
                                        Modifier.weight(1f).fillMaxWidth().padding(margin),
                                        verticalArrangement = Arrangement.spacedBy(margin),
                                    ) {
                                        updateNotice()
                                        homeBanner()
                                        sidePanel(Modifier.weight(1f).fillMaxWidth(), compact)
                                    }
                                    dock(compact)
                                }
                            }
                        }
                    }
                } else {
                    // Tall / portrait displays: the scene on top, the car's data below it, the dock under all.
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(margin),
                            verticalArrangement = Arrangement.spacedBy(margin),
                        ) {
                            scene(Modifier.fillMaxWidth().height(340.dp), RoundedCornerShape(24.dp), 1f)
                            updateNotice()
                            homeBanner()
                            sidePanel(Modifier.weight(1f).fillMaxWidth(), compact)
                        }
                        dock(compact)
                    }
                }
            }

            Overlay(showAllApps) {
                BackHandler { showAllApps = false }
                AllAppsScreen(
                    apps = apps,
                    usage = usage,
                    onLaunch = { app -> showAllApps = false; viewModel.launch(app) },
                    onAppInfo = viewModel::openAppInfo,
                    dockApps = settings.dockApps,
                    onDockApp = { app, on -> if (!viewModel.setDockApp(app.key, on)) sayDockFull(context) },
                    onClose = { showAllApps = false },
                )
            }

            Overlay(showSettings) {
                BackHandler { showSettings = false }
                SettingsScreen(
                    settings = settings,
                    viewModel = viewModel,
                    updateState = updateState,
                    bluetooth = bluetooth,
                    onOpenDiagnostics = { showDiagnostics = true },
                    onClose = { showSettings = false },
                )
            }

            if (showTrip) {
                TripSheet(
                    drives = viewModel.drives,
                    locationGranted = locationGranted,
                    onStartDrive = viewModel::startDrive,
                    onEndDrive = viewModel::endDrive,
                    onDismiss = { showTrip = false },
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

/** The dock already has as many apps as it takes. */
internal fun sayDockFull(context: Context) {
    Toast.makeText(context, context.getString(R.string.settings_dock_full, MAX_DOCK_APPS), Toast.LENGTH_LONG).show()
}

private fun UpdateState.needsAttention() =
    this is UpdateState.Available || this is UpdateState.Downloading || this is UpdateState.Installing || this is UpdateState.Failed

/** How long a full-screen page takes to slide in (see [Overlay]), with a little to spare. */
private const val PAGE_SETTLED_MS = 320L

/** The margin round the cards on Home, and between them. */
private val PageMargin = 12.dp
private val CompactPageMargin = 8.dp

/** Home under this height (between the status bar and the navigation bar) is laid out compact; see [HomeDock] and [ObdPanel]. */
private val CompactBelow = 520.dp

/** How much of the width, on a landscape screen, is the scene's own: the car's half, left of the card and the dock. */
private const val SCENE_SHARE = 0.52f

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
