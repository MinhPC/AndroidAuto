package com.minhphan.launcher.diagnostics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import com.minhphan.launcher.ui.queryHomeStatus

data class DiagnosticLine(val label: String, val value: String)

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What this device's firmware offers for showing a map beside the launcher. Read-only and local:
 * nothing is sent anywhere. [splitCommandSent] is the result of the last "try split screen" press.
 */
fun collectDiagnostics(context: Context, splitCommandSent: Boolean?): List<DiagnosticLine> {
    val pm = context.packageManager
    val metrics = context.resources.displayMetrics
    val activity = context.findActivity()

    fun yesNo(value: Boolean) = if (value) "có" else "không"
    fun feature(name: String) = yesNo(pm.hasSystemFeature(name))
    fun global(key: String) = Settings.Global.getInt(context.contentResolver, key, -1).let { if (it < 0) "chưa đặt" else it.toString() }
    fun version(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "chưa cài"

    val home = queryHomeStatus(context)
    val homeIsSystem = home.otherHomePackage?.let { pkg ->
        runCatching { pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrNull()
    }

    return listOf(
        DiagnosticLine("Thiết bị", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"),
        DiagnosticLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
        DiagnosticLine("Bản firmware", Build.DISPLAY),
        DiagnosticLine("Màn hình", "${metrics.widthPixels}x${metrics.heightPixels} px, ${metrics.densityDpi} dpi"),
        DiagnosticLine("Số màn hình", context.getSystemService(DisplayManager::class.java).displays.size.toString()),
        DiagnosticLine("Launcher gốc", home.otherHomePackage ?: "(đang là Car Launcher)"),
        DiagnosticLine("Launcher gốc là app hệ thống", homeIsSystem?.let(::yesNo) ?: "không rõ"),
        DiagnosticLine("Hỗ trợ cửa sổ tự do (freeform)", feature("android.software.freeform_window_management")),
        DiagnosticLine("Hỗ trợ cửa sổ thu nhỏ (PiP)", feature("android.software.picture_in_picture")),
        DiagnosticLine("Hỗ trợ app trên màn hình phụ", feature("android.software.activities_on_secondary_displays")),
        DiagnosticLine("Bật freeform (cài đặt nhà phát triển)", global("enable_freeform_support")),
        DiagnosticLine("Buộc app đổi kích thước được", global("force_resizable_activities")),
        DiagnosticLine("Launcher đang ở chế độ nhiều cửa sổ", activity?.isInMultiWindowMode?.let(::yesNo) ?: "không rõ"),
        DiagnosticLine("Dịch vụ Trợ năng chia đôi đã bật", yesNo(SplitScreenService.instance != null)),
        DiagnosticLine("Lệnh chia đôi lần trước", splitCommandSent?.let { if (it) "đã gửi" else "thất bại (dịch vụ chưa bật)" } ?: "chưa thử"),
        DiagnosticLine("Có GPS", feature("android.hardware.location.gps")),
        DiagnosticLine("Google Play services", version("com.google.android.gms")),
        DiagnosticLine("Google Maps", version("com.google.android.apps.maps")),
        DiagnosticLine("Waze", version("com.waze")),
    )
}
