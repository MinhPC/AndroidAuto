package com.minhphan.launcher.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import com.minhphan.launcher.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why there is no data right now. Everything except [NoPermission] and [NoAdapter] is retried automatically. */
enum class ObdProblem { NoPermission, BluetoothOff, NoAdapter, CannotConnect, NoVehicle, Lost }

/**
 * [Connecting] and [Problem] carry the last live reading as `last` while it is recent enough to show (dimmed);
 * a problem with the car itself ([ObdProblem.NoVehicle]) never does, since then the values are no longer true.
 * The flow says nothing while it waits, so `lastExpiresInMs` tells the screen when to stop showing `last` itself.
 */
sealed interface ObdState {
    data class Connecting(val last: ObdValues? = null, val lastExpiresInMs: Long = 0) : ObdState

    data class Connected(val values: ObdValues) : ObdState

    data class Problem(val problem: ObdProblem, val last: ObdValues? = null, val lastExpiresInMs: Long = 0) : ObdState
}

/** A serial line to an ELM327 adapter. */
interface ElmLink : Closeable {
    /** Sends [command] and returns what the adapter said before its ">" prompt; throws [IOException] on timeout. */
    suspend fun send(command: String, timeoutMs: Long): String
}

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

private const val RETRY_MS = 3_000L
private const val STALE_AFTER_MS = 15_000L
private const val IDLE_RETRY_MS = 5_000L
private const val COMMAND_TIMEOUT_MS = 2_000L
private const val RESET_TIMEOUT_MS = 5_000L

/** Finding the protocol the car speaks can take several seconds, the first time. */
private const val PROBE_TIMEOUT_MS = 15_000L
private const val UNSUPPORTED_AFTER_MISSES = 3
private const val SILENT_ROUNDS_BEFORE_NO_VEHICLE = 5
private const val VOLTAGE_EVERY_ROUNDS = 5

private val INIT_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")

/** Asked on every round, because speed and rpm are what the eye follows. */
private val FAST_PIDS = listOf(Pid.Rpm, Pid.Speed)

/** One of these is asked per round, in turn. */
private val SLOW_PIDS = listOf(Pid.Coolant, Pid.Load, Pid.Throttle, Pid.Fuel, Pid.Oil)

/**
 * Talks to the OBD adapter at [address] ("" picks a paired one by its name) and reports what it reads, forever:
 * whenever the link drops or the car is switched off it starts over after a few seconds. Cancelling the
 * collector closes the connection.
 */
fun obdStates(context: Context, address: String, permitted: Boolean): Flow<ObdState> = flow {
    val simulated = BuildConfig.DEBUG && address == SIMULATED_ADDRESS
    if (!simulated && !permitted) {
        emit(ObdState.Problem(ObdProblem.NoPermission))
        return@flow
    }
    val recent = RecentValues(STALE_AFTER_MS, SystemClock::elapsedRealtime)
    while (true) {
        emit(ObdState.Connecting(recent.get(), recent.remainingMs()))
        val problem = try {
            val link = if (simulated) SimulatedElmLink() else openBluetoothLink(context, address)
            link.use {
                runSession(it) { state ->
                    if (state is ObdState.Connected) recent.record(state.values)
                    emit(state)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException) {
            e.problem
        } catch (_: SecurityException) { // the permission was withdrawn while running
            ObdProblem.NoPermission
        } catch (_: Exception) { // an adapter or firmware quirk must end in a retry, never a crash
            ObdProblem.CannotConnect
        }
        val keepValues = problem == ObdProblem.Lost || problem == ObdProblem.CannotConnect
        emit(ObdState.Problem(problem, if (keepValues) recent.get() else null, if (keepValues) recent.remainingMs() else 0))
        delay(retryDelay(problem))
    }
}.flowOn(Dispatchers.IO)

private class ObdException(val problem: ObdProblem) : Exception()

@SuppressLint("MissingPermission")
private suspend fun openBluetoothLink(context: Context, address: String): ElmLink {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null || !adapter.isEnabled) throw ObdException(ObdProblem.BluetoothOff)
    val chosen = chooseAdapter(bondedDevices(context), address) ?: throw ObdException(ObdProblem.NoAdapter)
    val device = adapter.getRemoteDevice(chosen.address)
    // Some clones only accept the insecure channel, so try that when the normal one is refused.
    val socket = try {
        device.createRfcommSocketToServiceRecord(SPP_UUID).also { connect(it) }
    } catch (_: IOException) {
        device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { connect(it) }
    }
    return BluetoothElmLink(socket)
}

/**
 * Connects on its own thread, because closing the socket is the only way to abort a connect() that is blocking.
 * The caller has checked the permission; a withdrawn one surfaces as a SecurityException that [obdStates] handles.
 */
@SuppressLint("MissingPermission")
private suspend fun connect(socket: BluetoothSocket) {
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            thread(name = "obd-connect", isDaemon = true) {
                try {
                    socket.connect()
                    continuation.resume(Unit)
                } catch (e: Exception) { // not only IOException: a SecurityException here must reach the caller, not kill the app
                    continuation.resumeWithException(e)
                }
            }
        }
    } catch (e: Throwable) {
        // Also when the collector was cancelled just after connect() succeeded: the adapter accepts only one link.
        runCatching { socket.close() }
        throw e
    }
}

private class BluetoothElmLink(private val socket: BluetoothSocket) : ElmLink {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override suspend fun send(command: String, timeoutMs: Long): String {
        while (input.available() > 0) input.read() // whatever is left over from an earlier, timed-out command
        output.write("$command\r".toByteArray())
        output.flush()
        val text = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (input.available() > 0) {
                val byte = input.read()
                if (byte < 0) throw IOException("The adapter closed the connection")
                if (byte == PROMPT) return text.toString()
                text.append(byte.toChar())
            } else {
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("No answer to $command")
                delay(5)
            }
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val PROMPT = '>'.code
    }
}

/**
 * Sets the adapter up, then, for as long as the link lasts, waits for the car to answer and reads its values.
 * A car that is switched off does not drop the link: the adapter stays connected and is asked again every few
 * seconds, because reconnecting over and over can wedge cheap adapters. Returns only when the link breaks.
 */
private suspend fun runSession(link: ElmLink, publish: suspend (ObdState) -> Unit): ObdProblem {
    try {
        link.send("ATZ", RESET_TIMEOUT_MS)
        INIT_COMMANDS.forEach { link.send(it, COMMAND_TIMEOUT_MS) }
        while (true) {
            // "0100" asks which values the car has; the answer proves the car is awake and picks the protocol.
            if (parsePidResponse(link.send("0100", PROBE_TIMEOUT_MS), 0x00, 4) != null) readUntilSilent(link, publish)
            publish(ObdState.Problem(ObdProblem.NoVehicle))
            delay(RETRY_MS)
        }
    } catch (_: IOException) {
        return ObdProblem.Lost
    }
}

/** Reads the values round after round, publishing each round, until the car stops answering. */
internal suspend fun readUntilSilent(link: ElmLink, publish: suspend (ObdState) -> Unit) {
    var values = ObdValues(voltage = parseVoltage(link.send("ATRV", COMMAND_TIMEOUT_MS)))
    publish(ObdState.Connected(values))

    val misses = HashMap<Pid, Int>()
    val unsupported = HashSet<Pid>()
    var silentRounds = 0
    var round = 0
    while (true) {
        val asked = (FAST_PIDS + SLOW_PIDS[round % SLOW_PIDS.size]).filter { it !in unsupported }
        val missed = ArrayList<Pid>()
        for (pid in asked) {
            val value = parsePid(link.send("01%02X".format(pid.code), COMMAND_TIMEOUT_MS), pid)
            if (value == null) missed += pid else {
                values = values.with(pid, value)
                misses.remove(pid)
            }
        }
        if (round % VOLTAGE_EVERY_ROUNDS == 0) {
            parseVoltage(link.send("ATRV", COMMAND_TIMEOUT_MS))?.let { values = values.copy(voltage = it) }
        }
        if (missed.size == asked.size && asked.isNotEmpty()) {
            // Nothing answered: the ignition is probably off, not that these values are unsupported. The readings
            // are no longer confirmed, so they are shown as the last ones (dimmed), not as live.
            if (++silentRounds >= SILENT_ROUNDS_BEFORE_NO_VEHICLE) return
            publish(ObdState.Connecting(values, STALE_AFTER_MS))
            round++
            continue
        } else {
            silentRounds = 0
            for (pid in missed) {
                val count = (misses[pid] ?: 0) + 1
                misses[pid] = count
                if (count >= UNSUPPORTED_AFTER_MISSES) {
                    // Speed and rpm are on every car and are what the eye follows: keep asking, whatever the slow ones do.
                    if (pid !in FAST_PIDS) unsupported += pid
                    values = values.with(pid, null)
                }
            }
        }
        publish(ObdState.Connected(values))
        round++
    }
}

private fun retryDelay(problem: ObdProblem) =
    if (problem == ObdProblem.NoAdapter || problem == ObdProblem.BluetoothOff) IDLE_RETRY_MS else RETRY_MS
