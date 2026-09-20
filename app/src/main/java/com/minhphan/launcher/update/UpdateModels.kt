package com.minhphan.launcher.update

import androidx.annotation.StringRes
import com.minhphan.launcher.R
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    /**
     * [info] is set when the failure happened after an update was found, so Retry can resume it.
     * [detail] is the technical reason (exception / HTTP status), shown small under the message.
     */
    data class Failed(
        @StringRes val messageRes: Int,
        val info: UpdateInfo? = null,
        val detail: String? = null,
    ) : UpdateState
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

class UpdateFailure(@StringRes val messageRes: Int, val detail: String)

/** Turns a network / parsing exception into a specific, user-readable reason plus the raw detail. */
fun classifyUpdateError(error: Throwable): UpdateFailure {
    val detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(MAX_DETAIL_LENGTH)
    val messageRes = when (error) {
        is UnknownHostException -> R.string.update_error_dns
        // Most often a wrong clock on the head unit (certificate "not yet valid"/"expired") or an outdated CA store.
        is SSLException -> R.string.update_error_tls
        is SocketTimeoutException -> R.string.update_error_timeout
        is HttpStatusException -> R.string.update_error_http
        is JSONException, is IllegalArgumentException -> R.string.update_error_manifest
        else -> R.string.update_error_network
    }
    return UpdateFailure(messageRes, detail)
}

private const val MAX_DETAIL_LENGTH = 140

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
