package com.minhphan.launcher.obd

import android.content.Context
import com.minhphan.launcher.data.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The one connection to the OBD adapter, shared by the home screen and the trip recorder: an adapter takes only one
 * link at a time. It connects while somebody is collecting [states] and lets go a few seconds after the last one
 * stops, so the adapter is free for other apps; the address and the permission are followed live. Once it has
 * let go the last state is forgotten, so coming back never shows an old reading as if it were live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdHub(context: Context, settings: StateFlow<LauncherSettings>, scope: CoroutineScope) {
    private val bluetoothGranted = MutableStateFlow(hasBluetoothPermission(context))

    /** The values on Home that are not always read; followed live, so choosing another does not reconnect. */
    private val extraPids: StateFlow<Set<Pid>> = settings.map { extraPidsFor(it.obdFields) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, extraPidsFor(settings.value.obdFields))

    val states: StateFlow<ObdState> = combine(settings.map { it.obdAddress }.distinctUntilChanged(), bluetoothGranted, ::Pair)
        .flatMapLatest { (address, granted) -> obdStates(context.applicationContext, address, granted) { extraPids.value } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ObdState.Connecting())

    fun setBluetoothGranted(granted: Boolean) {
        bluetoothGranted.value = granted
    }
}
