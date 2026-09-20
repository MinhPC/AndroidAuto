package com.minhphan.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.AppRepository
import com.minhphan.launcher.data.FavoritesStore
import com.minhphan.launcher.diagnostics.DiagnosticLine
import com.minhphan.launcher.diagnostics.SplitScreenService
import com.minhphan.launcher.diagnostics.runConnectivityTest
import com.minhphan.launcher.update.UpdateInfo
import com.minhphan.launcher.update.UpdateManager
import com.minhphan.launcher.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    private val store = FavoritesStore(application)
    private val updates = UpdateManager(application, viewModelScope)

    val versionName: String = updates.currentVersionName
    val updateState: StateFlow<UpdateState> = updates.state

    val apps: StateFlow<List<AppInfo>> = repository.changes()
        .conflate()
        .map { repository.loadApps() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<AppInfo>> = combine(apps, store.keys) { apps, keys ->
        val byKey = apps.associateBy { it.key }
        keys.orEmpty().mapNotNull(byKey::get)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Installed map apps (Google Maps first), read from the launcher's own app list. */
    val mapApps: StateFlow<List<AppInfo>> = apps
        .map { list -> MAP_PACKAGES.mapNotNull { pkg -> list.firstOrNull { it.packageName == pkg } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _splitCommandSent = MutableStateFlow<Boolean?>(null)

    /** Result of the last "try split screen": true = command sent, false = accessibility service not enabled. */
    val splitCommandSent: StateFlow<Boolean?> = _splitCommandSent

    private val _connectivity = MutableStateFlow<List<DiagnosticLine>>(emptyList())

    /** Lines from the last network test (empty until it has run). */
    val connectivity: StateFlow<List<DiagnosticLine>> = _connectivity

    private val _connectivityRunning = MutableStateFlow(false)
    val connectivityRunning: StateFlow<Boolean> = _connectivityRunning

    private val _homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits each time the Home button is pressed while the launcher is already showing. */
    val homeEvents: SharedFlow<Unit> = _homeEvents

    init {
        updates.checkOnStart()

        // First run: pin the car's default maps / music / messaging / phone apps.
        viewModelScope.launch {
            val loaded = apps.first { it.isNotEmpty() }
            if (store.keys.value == null) store.set(defaultFavorites(loaded))
        }
    }

    fun onHomePressed() {
        _homeEvents.tryEmit(Unit)
    }

    fun launch(app: AppInfo) = repository.launch(app)

    fun checkForUpdate() = updates.check(manual = true)

    fun installUpdate(info: UpdateInfo) = updates.install(info)

    fun launchInBounds(app: AppInfo, bounds: Rect) = repository.launchInBounds(app, bounds)

    /**
     * Opens [app] full screen, then asks Android to split the screen: the standard split-screen action, sent
     * through the accessibility service. The launcher (the Home app) is expected to fill the other half.
     */
    fun trySplitScreen(app: AppInfo) {
        repository.launch(app)
        viewModelScope.launch {
            delay(SPLIT_DELAY_MS) // let the app reach the foreground first
            _splitCommandSent.value = SplitScreenService.toggleSplitScreen()
        }
    }

    fun testConnectivity() {
        if (_connectivityRunning.value) return
        viewModelScope.launch {
            _connectivityRunning.value = true
            _connectivity.value = runConnectivityTest()
            _connectivityRunning.value = false
        }
    }

    fun openAppInfo(app: AppInfo) = repository.openAppInfo(app)

    fun toggleFavorite(app: AppInfo) {
        val current = store.keys.value.orEmpty()
        when {
            app.key in current -> store.set(current - app.key)
            current.size < MAX_FAVORITES -> store.set(current + app.key)
        }
    }

    private fun defaultFavorites(apps: List<AppInfo>): List<String> {
        val pm = getApplication<Application>().packageManager
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_DIAL),
        )
        return intents
            .mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg }?.key }
            .distinct()
            .take(MAX_FAVORITES)
    }

    companion object {
        const val MAX_FAVORITES = 4
        private const val SPLIT_DELAY_MS = 2_000L
        private val MAP_PACKAGES = listOf("com.google.android.apps.maps", "com.waze")
    }
}
