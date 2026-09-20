package com.minhphan.launcher.update

import androidx.annotation.StringRes
import org.json.JSONObject

/** One entry of update.json, describing the newest published build. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String?,
)

sealed interface UpdateState {
    /** No update server configured at build time (UPDATE_BASE_URL is empty). */
    data object NotConfigured : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState

    /** [info] is set when the failure happened after an update was found, so Retry can resume it. */
    data class Failed(@StringRes val messageRes: Int, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Expected shape:
 * `{"versionCode": 2, "versionName": "1.1.0", "apkUrl": "https://...", "sha256": "<hex, optional>", "notes": "<optional>"}`
 */
fun parseUpdateManifest(json: String): UpdateInfo {
    val obj = JSONObject(json)
    val apkUrl = obj.getString("apkUrl")
    require(apkUrl.startsWith("https://")) { "apkUrl must use https" }
    return UpdateInfo(
        versionCode = obj.getLong("versionCode"),
        versionName = obj.getString("versionName"),
        apkUrl = apkUrl,
        sha256 = obj.optString("sha256").takeIf { it.isNotBlank() }?.lowercase(),
        notes = obj.optString("notes").takeIf { it.isNotBlank() },
    )
}

fun UpdateInfo.isNewerThan(currentVersionCode: Long) = versionCode > currentVersionCode
