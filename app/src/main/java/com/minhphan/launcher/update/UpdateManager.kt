package com.minhphan.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.minhphan.launcher.BuildConfig
import com.minhphan.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks update.json, downloads the APK and hands it to the system installer.
 * Android itself refuses the install unless the APK is signed with the same key as the installed app.
 */
class UpdateManager(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL

    private val installedPackage = this.context.packageManager.getPackageInfo(this.context.packageName, 0)
    val currentVersionName: String = installedPackage.versionName.orEmpty()
    private val currentVersionCode: Long = PackageInfoCompat.getLongVersionCode(installedPackage)

    private val _state = MutableStateFlow<UpdateState>(
        if (manifestUrl.isBlank()) UpdateState.NotConfigured else UpdateState.Idle,
    )
    val state: StateFlow<UpdateState> = _state

    private var job: Job? = null

    init {
        scope.launch {
            InstallEvents.results.collect { result ->
                val installing = _state.value as? UpdateState.Installing ?: return@collect
                when (result.status) {
                    PackageInstaller.STATUS_SUCCESS -> Unit // the app process is replaced by the new version
                    PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Available(installing.info)
                    else -> _state.value = UpdateState.Failed(
                        R.string.update_install_failed, installing.info, "status ${result.status}: ${result.message.orEmpty()}".take(140),
                    )
                }
            }
        }
    }

    /** Silent background check, at most once every [AUTO_CHECK_INTERVAL_MS]. */
    fun checkOnStart() {
        val sinceLast = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L)
        if (sinceLast >= AUTO_CHECK_INTERVAL_MS) check(manual = false)
    }

    /** [manual] checks report progress and "up to date" / errors; automatic ones only surface a new version. */
    fun check(manual: Boolean) {
        if (manifestUrl.isBlank() || isBusy()) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val info = runCatching { fetchManifest() }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            _state.value = when {
                info.isFailure -> if (manual) failed(info.exceptionOrNull()!!) else UpdateState.Idle
                info.getOrThrow().isNewerThan(currentVersionCode) -> UpdateState.Available(info.getOrThrow())
                manual -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        }
    }

    fun install(info: UpdateInfo) {
        if (isBusy()) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            // The user must allow installs from this app once; send them to that switch, then they tap again.
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            _state.value = UpdateState.Failed(R.string.update_error_permission, info)
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(info, 0)
            val apk = runCatching { download(info) }.getOrElse {
                _state.value = failed(it, info)
                return@launch
            }
            if (!isValidUpdate(apk, info)) {
                _state.value = UpdateState.Failed(R.string.update_error_invalid, info)
                return@launch
            }
            _state.value = runCatching { withContext(Dispatchers.IO) { commitInstall(apk) } }.fold(
                onSuccess = { UpdateState.Installing(info) },
                onFailure = { UpdateState.Failed(R.string.update_install_failed, info, classifyUpdateError(it).detail) },
            )
        }
    }

    private fun failed(error: Throwable, info: UpdateInfo? = null): UpdateState.Failed {
        val failure = classifyUpdateError(error)
        return UpdateState.Failed(failure.messageRes, info, failure.detail)
    }

    private fun isBusy() = when (_state.value) {
        is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> true
        else -> false
    }

    private suspend fun fetchManifest(): UpdateInfo = withContext(Dispatchers.IO) {
        val conn = open(manifestUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpStatusException(conn.responseCode)
            parseUpdateManifest(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "CarLauncher-${info.versionName}.apk")
        val conn = open(info.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpStatusException(conn.responseCode)
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = UpdateState.Downloading(info, percent)
                            }
                        }
                        ensureActive()
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** The file must be our own package at exactly the advertised version (and match the checksum, if given). */
    private suspend fun isValidUpdate(apk: File, info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return@withContext false
        archive.packageName == context.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) == info.versionCode &&
            (info.sha256 == null || sha256(apk) == info.sha256)
    }

    private fun commitInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            try {
                apk.inputStream().use { input ->
                    session.openWrite("update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val result = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
                // Mutable so the system can attach the status extras to it.
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Only https is allowed" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
