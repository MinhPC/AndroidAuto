package com.minhphan.launcher.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A Bluetooth device that has been paired in Android's own settings. */
data class ObdDevice(val name: String, val address: String)

/** Stored as the chosen address to mean: pick a paired device whose name looks like an OBD adapter. */
const val AUTO_ADDRESS = ""

/** Debug builds only: an adapter that is really a program, so the screen can be tried without a car. */
const val SIMULATED_ADDRESS = "simulated"

/** Android 12 and later ask at run time before an app may talk to a paired device; older versions never ask. */
val BLUETOOTH_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

fun hasBluetoothPermission(context: Context): Boolean =
    BLUETOOTH_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The paired devices, by name; empty when Bluetooth is missing or the permission is not granted. */
@SuppressLint("MissingPermission")
fun bondedDevices(context: Context): List<ObdDevice> {
    if (!hasBluetoothPermission(context)) return emptyList()
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return try {
        adapter.bondedDevices.orEmpty()
            .map { ObdDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (_: SecurityException) {
        emptyList()
    }
}

/** The brand or type must start a word, so "elm" finds "ELM327" but not "Helmet intercom". */
private val ADAPTER_NAME = Regex("(?<![a-z])(obd|elm|icar|vgate|v-?link|veepeak|konnwei|carista)", RegexOption.IGNORE_CASE)

/** The device stored as [address], or for [AUTO_ADDRESS] the first paired one named like an OBD adapter. */
fun chooseAdapter(devices: List<ObdDevice>, address: String): ObdDevice? =
    if (address == AUTO_ADDRESS) devices.firstOrNull { ADAPTER_NAME.containsMatchIn(it.name) }
    else devices.firstOrNull { it.address == address }
