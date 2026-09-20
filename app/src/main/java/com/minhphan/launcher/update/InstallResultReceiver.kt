package com.minhphan.launcher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow

/** Carries PackageInstaller status codes from the receiver to [UpdateManager]. */
object InstallEvents {
    val results = MutableSharedFlow<Int>(extraBufferCapacity = 4)
}

/** Receives the outcome of a PackageInstaller session started by [UpdateManager]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system wants the user to confirm the install; show its dialog.
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            InstallEvents.results.tryEmit(status)
        }
    }

    companion object {
        const val ACTION = "com.minhphan.launcher.INSTALL_RESULT"
    }
}
