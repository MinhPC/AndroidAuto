package com.minhphan.launcher.ui

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.LauncherViewModel
import com.minhphan.launcher.data.AppInfo

private fun androidx.compose.ui.geometry.Rect.toAndroidRect() =
    Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

@Composable
fun LauncherApp(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val pinned by viewModel.favorites.collectAsStateWithLifecycle()
    val pinnedKeys = remember(pinned) { pinned.mapTo(HashSet()) { it.key } }
    val canPinMore = pinned.size < LauncherViewModel.MAX_FAVORITES
    val gridState = rememberLazyGridState()
    val mapApps by viewModel.mapApps.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val connectivityRunning by viewModel.connectivityRunning.collectAsStateWithLifecycle()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
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

    // Pressing Home while already home scrolls the grid back to the top.
    LaunchedEffect(viewModel, gridState) {
        viewModel.homeEvents.collect { gridState.animateScrollToItem(0) }
    }

    val tile: @Composable (AppInfo, Boolean, Modifier) -> Unit = { app, horizontal, modifier ->
        AppTile(
            app = app,
            isPinned = app.key in pinnedKeys,
            canPin = canPinMore,
            horizontal = horizontal,
            onLaunch = { viewModel.launch(app) },
            onTogglePin = { viewModel.toggleFavorite(app) },
            onAppInfo = { viewModel.openAppInfo(app) },
            modifier = modifier,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
        ) {
            if (maxWidth > maxHeight) {
                // Landscape car display: left half = clock, pinned apps and all apps;
                // right half = the map, where a map app is opened in a window of exactly this size.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Clock()
                        updateBar()
                        homeBanner()
                        if (pinned.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                pinned.forEach { tile(it, false, Modifier.weight(1f)) }
                            }
                        }
                        AppGrid(apps, gridState, tile, Modifier.weight(1f).fillMaxWidth())
                    }
                    MapPanel(
                        mapApps = mapApps,
                        onOpenMap = { app -> mapBounds?.let { viewModel.launchInBounds(app, it) } ?: viewModel.launch(app) },
                        onOpenDiagnostics = { showDiagnostics = true },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { mapBounds = it.boundsInWindow().toAndroidRect() },
                    )
                }
            } else {
                // Tall / portrait displays: stack everything vertically.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Clock()
                    updateBar()
                    homeBanner()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        pinned.forEach { tile(it, false, Modifier.weight(1f)) }
                    }
                    AppGrid(apps, gridState, tile, Modifier.weight(1f).fillMaxWidth())
                }
            }
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

@Composable
private fun AppGrid(
    apps: List<AppInfo>,
    state: LazyGridState,
    tile: @Composable (AppInfo, Boolean, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        state = state,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(apps, key = { it.key }) { app -> tile(app, false, Modifier) }
    }
}
