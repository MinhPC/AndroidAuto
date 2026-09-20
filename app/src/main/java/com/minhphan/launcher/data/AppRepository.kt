package com.minhphan.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.roundToInt

class AppRepository(context: Context) {
    private val context = context.applicationContext
    private val launcherApps = this.context.getSystemService(LauncherApps::class.java)
    private val userManager = this.context.getSystemService(UserManager::class.java)
    private val iconSizePx = (ICON_SIZE_DP * this.context.resources.displayMetrics.density).roundToInt()

    /** Emits once immediately, then whenever an app is installed, updated, removed or (un)suspended. */
    fun changes(): Flow<Unit> = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackageAdded(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackageChanged(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        trySend(Unit)
        awaitClose { launcherApps.unregisterCallback(callback) }
    }

    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val collator = Collator.getInstance()
        userManager.userProfiles
            .flatMap { user ->
                launcherApps.getActivityList(null, user).map { info ->
                    val component = info.componentName
                    AppInfo(
                        key = "${component.flattenToShortString()}@${userManager.getSerialNumberForUser(user)}",
                        label = info.label.toString(),
                        component = component,
                        user = user,
                        icon = info.getIcon(0).toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
                    )
                }
            }
            .filter { it.packageName != context.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun launch(app: AppInfo) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
    }

    fun openAppInfo(app: AppInfo) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    private companion object {
        const val ICON_SIZE_DP = 72
    }
}
