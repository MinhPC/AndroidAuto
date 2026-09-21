package com.minhphan.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.minhphan.launcher.obd.BLUETOOTH_PERMISSIONS
import com.minhphan.launcher.obd.hasBluetoothPermission

/** Whether the app may talk to paired Bluetooth devices, and how to ask (or, after a refusal, where to go). */
@Stable
class BluetoothPermission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberBluetoothPermission(): BluetoothPermission {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasBluetoothPermission(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBluetoothPermission(context)
        if (!granted) denied = true
    }
    // The user may also grant it from the app settings and come back.
    LifecycleResumeEffect(Unit) {
        granted = hasBluetoothPermission(context)
        onPauseOrDispose { }
    }
    return remember(granted, denied) {
        BluetoothPermission(granted) {
            // After a refusal Android will not ask again, so send the user to the app settings.
            if (denied) openAppDetails(context, context.packageName) else launcher.launch(BLUETOOTH_PERMISSIONS)
        }
    }
}
