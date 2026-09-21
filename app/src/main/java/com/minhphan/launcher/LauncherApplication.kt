package com.minhphan.launcher

import android.app.Application
import com.minhphan.launcher.data.SettingsStore
import com.minhphan.launcher.obd.ObdHub
import com.minhphan.cloud.CloudAccount
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
    val obdHub by lazy { ObdHub(this, settingsStore.settings, appScope) }
    val cloud by lazy { CloudAccount(this) }
    val tripUploader by lazy { TripUploader(this, cloud) }
}
