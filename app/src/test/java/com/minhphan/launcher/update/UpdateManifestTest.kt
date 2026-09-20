package com.minhphan.launcher.update

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private fun manifest(extra: String = "", versionCode: Int = 2, apkUrl: String = "https://example.com/a.apk") =
        """{"versionCode": $versionCode, "versionName": "1.1.0", "apkUrl": "$apkUrl"$extra}"""

    @Test
    fun parsesAllFields() {
        val info = parseUpdateManifest(manifest(""", "sha256": "ABCDEF", "notes": "Sửa lỗi""""))
        assertEquals(2L, info.versionCode)
        assertEquals("1.1.0", info.versionName)
        assertEquals("https://example.com/a.apk", info.apkUrl)
        assertEquals("abcdef", info.sha256)
        assertEquals("Sửa lỗi", info.notes)
    }

    @Test
    fun optionalFieldsDefaultToNull() {
        val info = parseUpdateManifest(manifest())
        assertNull(info.sha256)
        assertNull(info.notes)
    }

    @Test
    fun rejectsNonHttpsApkUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            parseUpdateManifest(manifest(apkUrl = "http://example.com/a.apk"))
        }
    }

    @Test
    fun rejectsMissingVersionCode() {
        assertThrows(JSONException::class.java) {
            parseUpdateManifest("""{"versionName": "1.1.0", "apkUrl": "https://example.com/a.apk"}""")
        }
    }

    @Test
    fun onlyStrictlyHigherVersionCodeIsNewer() {
        val info = parseUpdateManifest(manifest())
        assertTrue(info.isNewerThan(1))
        assertFalse(info.isNewerThan(2))
        assertFalse(info.isNewerThan(3))
    }
}
