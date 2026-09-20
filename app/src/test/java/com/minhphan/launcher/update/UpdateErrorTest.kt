package com.minhphan.launcher.update

import com.minhphan.launcher.R
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class UpdateErrorTest {
    @Test
    fun dnsFailureIsReportedAsDns() {
        assertEquals(R.string.update_error_dns, classifyUpdateError(UnknownHostException("github.com")).messageRes)
    }

    @Test
    fun certificateProblemIsReportedAsTlsSoTheClockGetsChecked() {
        val failure = classifyUpdateError(SSLHandshakeException("Chain validation failed"))
        assertEquals(R.string.update_error_tls, failure.messageRes)
        assertTrue(failure.detail.startsWith("SSLHandshakeException"))
    }

    @Test
    fun timeoutAndHttpStatusAreDistinguished() {
        assertEquals(R.string.update_error_timeout, classifyUpdateError(SocketTimeoutException()).messageRes)
        val http = classifyUpdateError(HttpStatusException(404))
        assertEquals(R.string.update_error_http, http.messageRes)
        assertEquals("HttpStatusException: HTTP 404", http.detail)
    }

    @Test
    fun badManifestAndUnknownErrors() {
        assertEquals(R.string.update_error_manifest, classifyUpdateError(JSONException("x")).messageRes)
        assertEquals(R.string.update_error_manifest, classifyUpdateError(IllegalArgumentException("apkUrl must use https")).messageRes)
        assertEquals(R.string.update_error_network, classifyUpdateError(ConnectException("refused")).messageRes)
    }

    @Test
    fun detailIsTruncated() {
        assertEquals(140, classifyUpdateError(IllegalStateException("x".repeat(500))).detail.length)
    }
}
