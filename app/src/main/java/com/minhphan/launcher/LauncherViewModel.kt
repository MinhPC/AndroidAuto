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
import com.minhphan.launcher.data.UsageStore
import com.minhphan.launcher.data.ThemeMode
import com.minhphan.launcher.diagnostics.DiagnosticLine
import com.minhphan.launcher.diagnostics.runConnectivityTest
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.VoltageCalibration
import com.minhphan.launcher.sync.SyncState
import com.minhphan.trip.FuelBook
import com.minhphan.trip.FuelEstimate
import com.minhphan.trip.TripMeter
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    private val store = FavoritesStore(application)
    private val usage = UsageStore(application)
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

    /** Whether the trip recorder is running. Apart from [syncState], which changes with every GPS fix and every write. */
    val recording: StateFlow<Boolean> = syncState.map { it.recording }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, syncState.value.recording)

    /** The trip the driver started by hand, and what the last one came to. */
    val tripMeter: StateFlow<TripMeter> = launcher.driveLog.trip

    /** Kilometres driven so far, as the GPS saw; the trip computer counts from where this stood. */
    val totalKm: StateFlow<Double> = launcher.driveLog.totalKm

    /** The last fill-up and the average fuel economy. */
    val fuelBook: StateFlow<FuelBook> = launcher.driveLog.fuel

    /** How much fuel is thought to be left, and so how far the car can go; null before the first full fill-up. */
    val fuelEstimate: StateFlow<FuelEstimate?> = combine(launcher.driveLog.totalKm, fuelBook, settings) { _, _, current ->
        launcher.driveLog.fuelEstimate(current.tankLiters)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, launcher.driveLog.fuelEstimate(settings.value.tankLiters))

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

    fun startTrip() = launcher.driveLog.startTrip(System.currentTimeMillis())

    fun stopTrip() = launcher.driveLog.stopTrip(System.currentTimeMillis())

    /** The kilometres since the last full fill-up, to offer as the default in the fill-up form. */
    fun suggestedRefuelKm(): Double? = launcher.driveLog.suggestedKm()

    /** Records a fill-up on the car and sends it to the signed-in account. [distanceKm] is the driver's own figure, if any. */
    fun recordRefuel(liters: Double, amountVnd: Long, full: Boolean, distanceKm: Double?) {
        launcher.driveLog.recordRefuel(System.currentTimeMillis(), liters, amountVnd, full, distanceKm)
        launcher.uploadPendingRefuels()
        launcher.tripUploader.saveFuel(launcher.driveLog.fuelEstimate(settings.value.tankLiters))
    }

    fun setTankLiters(value: Int) = settingsStore.setTankLiters(value)

    fun setObdField(field: ObdField, on: Boolean) = settingsStore.setObdField(field, on)

    fun setVoltageCalibration(value: VoltageCalibration) = settingsStore.setVoltageCalibration(value)

    fun setSyncTrips(value: Boolean) = settingsStore.setSyncTrips(value)

    fun onHomePressed() {
        _homeEvents.tryEmit(Unit)
    }

    /** How many times each app has been launched from here; the All apps list puts the most used first. */
    val appUsage: StateFlow<Map<String, Int>> = usage.counts

    fun launch(app: AppInfo) {
        usage.record(app.key)
        repository.launch(app)
    }

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
    }
}
