package com.minhphan.launcher

import android.app.Application
import com.minhphan.launcher.data.DriveLog
import com.minhphan.launcher.data.SettingsStore
import com.minhphan.launcher.obd.ObdHub
import com.minhphan.cloud.CloudAccount
import com.minhphan.launcher.sync.SyncStatus
import com.minhphan.launcher.sync.TripUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * The things the home screen and the trip recorder both use, so there is one of each: the recorder runs in a
 * service that outlives the screen, and two OBD connections or two copies of the settings would fight.
 */
class LauncherApplication : Application() {
    /** Lives as long as the process; for work that must not stop when the home screen is closed. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The one thread the trip recorder works on: its tracker and uploader are not thread safe, and a service that
     * is stopped and started again must not overlap with its predecessor.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recorderDispatcher = Dispatchers.Default.limitedParallelism(1)

    val settingsStore by lazy { SettingsStore(this) }
    val obdHub by lazy { ObdHub(this, settingsStore.settings, appScope, settingsStore::setCarPids) }
    val cloud by lazy { CloudAccount(this) }
    val driveLog by lazy { DriveLog(this) }
    val syncStatus = SyncStatus()
    /** Hands every fill-up Firebase has not been given yet over to it: all of them for a driver who was signed out when they were logged. */
    fun uploadPendingRefuels() {
        val sent = driveLog.pendingRefuels().filter { tripUploader.saveRefuel(it) }.map { it.id }
        if (sent.isNotEmpty()) driveLog.refuelsSent(sent)
    }

    val tripUploader by lazy {
        TripUploader(this, cloud, syncStatus) { driveLog.fuelEstimate(settingsStore.settings.value.tankLiters) }
    }
}
