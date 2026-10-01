package com.minhphan.launcher

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.AppRepository
import com.minhphan.launcher.data.LauncherSettings
import com.minhphan.launcher.data.ScreenLight
import com.minhphan.launcher.data.UsageStore
import com.minhphan.launcher.data.ThemeMode
import com.minhphan.launcher.diagnostics.DiagnosticLine
import com.minhphan.launcher.diagnostics.runConnectivityTest
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.VoltageCalibration
import com.minhphan.launcher.sync.SyncState
import com.minhphan.trip.DriveClock
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    private val usage = UsageStore(application)
    private val launcher = application as LauncherApplication
    private val settingsStore = launcher.settingsStore
    private val updates = UpdateManager(application, viewModelScope)

    val versionName: String = updates.currentVersionName

    val settings: StateFlow<LauncherSettings> = settingsStore.settings
    val screenLight: StateFlow<ScreenLight> = launcher.headlights.state
    val updateState: StateFlow<UpdateState> = updates.state

    val apps: StateFlow<List<AppInfo>> = repository.changes()
        .conflate()
        .map { repository.loadApps() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the OBD adapter reports; see [com.minhphan.launcher.obd.ObdHub]. */
    val obd: StateFlow<ObdState> = launcher.obdHub.states

    val account: StateFlow<AccountState> = launcher.cloud.state

    /** What the trip recorder is doing, shown in Settings. */
    val syncState: StateFlow<SyncState> = launcher.syncStatus.state

    /** Whether the trip recorder is running. Apart from [syncState], which changes with every GPS fix and every write. */
    val recording: StateFlow<Boolean> = syncState.map { it.recording }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, syncState.value.recording)

    /** The journey in progress and the ones before it: the time on the move, journey by journey. */
    val drives: StateFlow<DriveClock> = launcher.driveLog.drives

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
    }

    fun setBluetoothGranted(granted: Boolean) = launcher.obdHub.setBluetoothGranted(granted)

    suspend fun signInWithGoogle(activity: Activity): Result<Unit> = launcher.cloud.signInWithGoogle(activity)

    fun signOut() = launcher.cloud.signOut()

    fun startDrive() = launcher.driveLog.startDrive(System.currentTimeMillis())

    fun endDrive() = launcher.driveLog.endDrive(System.currentTimeMillis())

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

    /** Takes the screen's brightness now as its day brightness, for [ThemeMode.Headlights]. */
    fun relearnDayBrightness() = launcher.headlights.relearn()

    fun setObdAddress(value: String) = settingsStore.setObdAddress(value)
}
