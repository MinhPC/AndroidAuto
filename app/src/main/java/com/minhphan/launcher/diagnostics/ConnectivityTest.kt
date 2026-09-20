package com.minhphan.launcher.diagnostics

import com.minhphan.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Hosts an update touches, plus a general-internet baseline. */
private val HOSTS = listOf(
    "www.google.com",
    "github.com",
    "release-assets.githubusercontent.com",
)

/**
 * Step-by-step network check for the update server: DNS, then an HTTPS request per host, then the real
 * update.json URL. Each line either shows success or the exact exception, which tells wrong clock,
 * blocked domain and no internet apart.
 */
suspend fun runConnectivityTest(): List<DiagnosticLine> = withContext(Dispatchers.IO) {
    buildList {
        for (host in HOSTS) {
            val dns = runCatching { InetAddress.getAllByName(host).first().hostAddress.orEmpty() }
            add(DiagnosticLine("DNS $host", dns.fold({ it }, { it.short() })))
            if (dns.isSuccess) {
                val https = runCatching { httpsStatus("https://$host/") }
                add(DiagnosticLine("HTTPS $host", https.fold({ "HTTP $it" }, { it.short() })))
            }
        }
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
        if (manifestUrl.isNotBlank()) {
            val manifest = runCatching { httpsStatus(manifestUrl) }
            add(DiagnosticLine("update.json", manifest.fold({ "HTTP $it" }, { it.short() })))
        }
    }
}

/** Response code after following redirects, like the updater does. */
private fun httpsStatus(url: String): Int {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    try {
        return conn.responseCode
    } finally {
        conn.disconnect()
    }
}

private fun Throwable.short() = "${javaClass.simpleName}: ${message.orEmpty()}".take(100)
