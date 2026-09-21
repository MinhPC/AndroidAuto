package com.minhphan.launcher.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {
    private var clock = 1_000L
    private val status = SyncStatus { clock }

    @Test
    fun startsEmpty() {
        val state = status.state.value
        assertFalse(state.recording)
        assertEquals(0, state.writes)
        assertEquals(0L, state.lastFixAt)
        assertNull(state.lastError)
    }

    @Test
    fun aWriteIsPendingUntilTheServerAnswers() {
        status.written()
        status.written()
        assertEquals(2, status.state.value.pending)

        clock = 5_000L
        status.confirmed()
        assertEquals(1, status.state.value.pending)
        assertEquals(5_000L, status.state.value.lastConfirmedAt)

        status.failed("PERMISSION_DENIED: Missing or insufficient permissions.")
        val state = status.state.value
        assertEquals(0, state.pending)
        assertEquals("PERMISSION_DENIED: Missing or insufficient permissions.", state.lastError)
    }

    @Test
    fun aConfirmedWriteClearsTheLastError() {
        status.written()
        status.failed("UNAVAILABLE")
        status.written()
        status.confirmed()
        assertNull(status.state.value.lastError)
    }

    @Test
    fun fixesAreCountedWithTheirTime() {
        clock = 2_000L
        status.fix()
        clock = 3_000L
        status.fix()
        assertEquals(2, status.state.value.fixes)
        assertEquals(3_000L, status.state.value.lastFixAt)
    }

    @Test
    fun recordingFollowsTheService() {
        status.recording(true)
        assertTrue(status.state.value.recording)
        status.recording(false)
        assertFalse(status.state.value.recording)
    }
}
