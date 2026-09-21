package com.minhphan.launcher.ui

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.R
import com.minhphan.launcher.data.NavResult
import com.minhphan.launcher.data.Place
import com.minhphan.launcher.data.resolveNavigator
import com.minhphan.launcher.data.startNavigation

private fun androidx.compose.ui.geometry.Rect.toAndroidRect() =
    Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

@Composable
fun LauncherApp(viewModel: LauncherViewModel) {
    val mapApps by viewModel.mapApps.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val pinned by viewModel.favorites.collectAsStateWithLifecycle()
    val pinnedKeys = remember(pinned) { pinned.mapTo(HashSet()) { it.key } }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val connectivityRunning by viewModel.connectivityRunning.collectAsStateWithLifecycle()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateBar: @Composable () -> Unit = {
        UpdateBar(
            versionName = viewModel.versionName,
            state = updateState,
            onCheck = viewModel::checkForUpdate,
            onInstall = viewModel::installUpdate,
        )
    }
    var mapBounds by remember { mutableStateOf<Rect?>(null) }
    val context = LocalContext.current
    val homeStatus by rememberHomeStatus()
    val requestDefaultHome = rememberRequestDefaultHome()
    val homeBanner: @Composable () -> Unit = {
        if (!homeStatus.isDefault) {
            DefaultHomeBanner(
                otherHomePackage = homeStatus.otherHomePackage,
                onSetDefault = requestDefaultHome,
                onOpenCurrentLauncher = { openAppDetails(context, it) },
            )
        }
    }
    val navigate: (Place) -> Unit = { place ->
        val navigator = resolveNavigator(settings.navigator, mapApps.map { it.packageName })
        when (startNavigation(context, navigator, place, settings.addressOf(place))) {
            NavResult.Started -> Unit
            NavResult.NeedsAddress -> {
                Toast.makeText(context, R.string.nav_needs_address, Toast.LENGTH_LONG).show()
                showSettings = true
            }
            NavResult.NotInstalled -> Toast.makeText(context, R.string.nav_not_installed, Toast.LENGTH_LONG).show()
        }
    }
    val mapPanel: @Composable (Modifier) -> Unit = { modifier ->
        MapPanel(
            mapApps = mapApps,
            onOpenMap = { app -> mapBounds?.let { viewModel.launchInBounds(app, it) } ?: viewModel.launch(app) },
            onNavigate = navigate,
            modifier = modifier.onGloballyPositioned { mapBounds = it.boundsInWindow().toAndroidRect() },
        )
    }
    val tools: @Composable () -> Unit = {
        ToolsRow(onOpenSettings = { showSettings = true }, onOpenDiagnostics = { showDiagnostics = true })
    }
    val dock: @Composable () -> Unit = {
        if (pinned.isNotEmpty()) {
            AppDock(
                apps = pinned,
                onLaunch = viewModel::launch,
                onUnpin = viewModel::toggleFavorite,
                onAppInfo = viewModel::openAppInfo,
            )
        }
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
                    // Landscape car display: the left half is the scene, edge to edge; the right half is the map,
                    // where a map app is opened in a window of exactly the panel's size.
                    Row(Modifier.fillMaxSize()) {
                        DrivingScene(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .systemBarsPadding()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            updateBar()
                            homeBanner()
                            mapPanel(Modifier.weight(1f).fillMaxWidth())
                            dock()
                            tools()
                        }
                    }
                } else {
                    // Tall / portrait displays: the scene on top, the map below it.
                    Column(Modifier.fillMaxSize()) {
                        DrivingScene(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            updateBar()
                            homeBanner()
                            mapPanel(Modifier.weight(1f).fillMaxWidth())
                            dock()
                            tools()
                        }
                    }
                }
            }

            if (showSettings) {
                BackHandler { showSettings = false }
                SettingsScreen(
                    settings = settings,
                    apps = apps,
                    pinnedKeys = pinnedKeys,
                    viewModel = viewModel,
                    onClose = { showSettings = false },
                )
            }

            if (showDiagnostics) {
                BackHandler { showDiagnostics = false }
                DiagnosticsScreen(
                    connectivity = connectivity,
                    connectivityRunning = connectivityRunning,
                    onTestConnectivity = viewModel::testConnectivity,
                    onClose = { showDiagnostics = false },
                )
            }
        }
    }
}

/** Settings and the window diagnostics, small and out of the way at the bottom of the map side. */
@Composable
private fun ToolsRow(onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.settings_button), color = muted)
        }
        TextButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.diagnostics_button), color = muted)
        }
    }
}
