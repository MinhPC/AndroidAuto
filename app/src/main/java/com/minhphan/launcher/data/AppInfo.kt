package com.minhphan.launcher.data

import android.content.ComponentName
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

@Immutable
data class AppInfo(
    /** Stable id: component + user profile serial (an app can exist in more than one profile). */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
) {
    val packageName: String get() = component.packageName
}
