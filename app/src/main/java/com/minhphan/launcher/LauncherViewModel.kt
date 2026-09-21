package com.minhphan.launcher

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.AppRepository
import com.minhphan.launcher.data.FavoritesStore
import com.minhphan.launcher.data.LauncherSettings
import com.minhphan.launcher.data.ThemeMode
import com.minhphan.launcher.diagnostics.DiagnosticLine
import com.minhphan.launcher.diagnostics.runConnectivityTest
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.data.LastLocationStore
import com.minhphan.launcher.sync.SyncState
import com.minhphan.launcher.sync.TestResult
import com.minhphan.launcher.sync.toEngine
import com.minhphan.trip.EngineData
import com.minhphan.trip.LatLon
import com.minhphan.trip.LiveStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.minhphan.cloud.AccountState
import com.minhphan.launcher.update.UpdateInfo
import com.minhphan.launcher.update.UpdateManager
import com.minhphan.launcher.update.UpdateState
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
    private val launcher = application as LauncherApplication
    private val settingsStore = launcher.settingsStore
    private val updates = UpdateManager(application, viewModelScope)

    val versionName: String = updates.currentVersionName

    val settings: StateFlow<LauncherSettings> = settingsStore.settings
    val updateState: StateFlow<UpdateState> = updates.state

    val apps: StateFlow<List<AppInfo>> = repository.changes()
        .conflate()
        .map { repository.loadApps() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<AppInfo>> = combine(apps, store.keys) { apps, keys ->
        val byKey = apps.associateBy { it.key }
        keys.orEmpty().mapNotNull(byKey::get)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the OBD adapter reports; see [com.minhphan.launcher.obd.ObdHub]. */
    val obd: StateFlow<ObdState> = launcher.obdHub.states

    val account: StateFlow<AccountState> = launcher.cloud.state

    /** What the trip recorder is doing, shown in Settings. */
    val syncState: StateFlow<SyncState> = launcher.syncStatus.state

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

    fun setBluetoothGranted(granted: Boolean) = launcher.obdHub.setBluetoothGranted(granted)

    suspend fun signInWithGoogle(activity: Activity): Result<Unit> = launcher.cloud.signInWithGoogle(activity)

    fun signOut() = launcher.cloud.signOut()

    /** What a test send did, and whether it used the real engine data of the car or a made-up sample. */
    data class TestOutcome(val result: TestResult, val realEngine: Boolean)

    /**
     * Sends the car's current engine data (or a sample if the OBD adapter is not connected) to Firebase, at the last
     * known position, and reads it back: the way to check the connection before the car drives anywhere.
     */
    suspend fun sendTest(): TestOutcome {
        val real = (launcher.obdHub.states.value as? ObdState.Connected)?.values?.toEngine()
        val position = withContext(Dispatchers.IO) { LastLocationStore(getApplication()).current() }
        val live = LiveStatus(
            position = position?.let { LatLon(it.latitude, it.longitude) } ?: SAMPLE_POSITION,
            speedKmh = 0f,
            moving = false,
            updatedAt = System.currentTimeMillis(),
            engine = real ?: SAMPLE_ENGINE,
        )
        return TestOutcome(launcher.tripUploader.sendTest(live), realEngine = real != null)
    }

    fun setSyncTrips(value: Boolean) = settingsStore.setSyncTrips(value)

    fun onHomePressed() {
        _homeEvents.tryEmit(Unit)
    }

    fun launch(app: AppInfo) = repository.launch(app)

    fun checkForUpdate() = updates.check(manual = true)

    fun installUpdate(info: UpdateInfo) = updates.install(info)

    fun testConnectivity() {
        if (_connectivityRunning.value) return
        viewModelScope.launch {
            _connectivityRunning.value = true
            _connectivity.value = runConnectivityTest()
            _connectivityRunning.value = false
        }
    }

    fun openAppInfo(app: AppInfo) = repository.openAppInfo(app)

    fun setTheme(value: ThemeMode) = settingsStore.setTheme(value)

    fun setObdAddress(value: String) = settingsStore.setObdAddress(value)

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
        const val MAX_FAVORITES = 5

        private val SAMPLE_POSITION = LatLon(21.0285, 105.8542) // Hoan Kiem, Hanoi
        private val SAMPLE_ENGINE = EngineData(
            rpm = 2100, coolantC = 88, oilC = 95, loadPercent = 35, throttlePercent = 18, fuelPercent = 55, voltage = 14.1f,
        )
    }
}
