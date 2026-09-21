package com.minhphan.launcher.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the trip recorder is doing, for the person at the car screen: a head unit has no logcat, so when nothing
 * reaches Firestore this says where it stops. Times are wall-clock milliseconds, 0 for "never".
 */
data class SyncState(
    /** The recorder service is running and listening for GPS fixes. */
    val recording: Boolean = false,
    val lastFixAt: Long = 0,
    val fixes: Int = 0,
    /** Writes handed to Firestore, how many the server has confirmed, and how many it refused. */
    val writes: Int = 0,
    val confirmed: Int = 0,
    val failed: Int = 0,
    val lastConfirmedAt: Long = 0,
    /** Why the last refused write was refused, as Firestore says it (for instance PERMISSION_DENIED). */
    val lastError: String? = null,
) {
    /** Writes waiting for the server: made, and neither confirmed nor refused yet (no connection, for instance). */
    val pending: Int get() = (writes - confirmed - failed).coerceAtLeast(0)
}

class SyncStatus(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    fun recording(running: Boolean) = _state.update { it.copy(recording = running) }

    fun fix() = _state.update { it.copy(lastFixAt = clock(), fixes = it.fixes + 1) }

    fun written() = _state.update { it.copy(writes = it.writes + 1) }

    fun confirmed() = _state.update { it.copy(confirmed = it.confirmed + 1, lastConfirmedAt = clock(), lastError = null) }

    fun failed(reason: String) = _state.update { it.copy(failed = it.failed + 1, lastError = reason) }
}
