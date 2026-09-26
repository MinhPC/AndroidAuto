package com.minhphan.launcher.obd

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Corrects the battery voltage the adapter measures itself (ATRV): a cheap adapter reads it through its own
 * circuit and can be well off. Two readings taken beside a multimeter at the battery, one with the engine off and
 * one with it running, give a straight line from what the adapter says to the real voltage.
 *
 * The defaults are this car's adapter (iCar Pro): 11.4 V for 12.78 V engine off, 13.1 V for 14.78 V running.
 */
data class VoltageCalibration(
    val enabled: Boolean = true,
    val offAdapter: Float = 11.4f,
    val offReal: Float = 12.78f,
    val runningAdapter: Float = 13.1f,
    val runningReal: Float = 14.78f,
) {
    /** The real voltage for what the adapter said, to a hundredth of a volt. */
    fun apply(adapter: Float): Float {
        if (!enabled) return adapter
        val span = runningAdapter - offAdapter
        val real = if (abs(span) < MIN_SPAN_VOLTS) {
            // Two readings too close together say nothing about the slope: only shift by their average difference.
            adapter + ((offReal - offAdapter) + (runningReal - runningAdapter)) / 2
        } else {
            offReal + (adapter - offAdapter) * (runningReal - offReal) / span
        }
        return (real * 100).roundToInt() / 100f
    }

    private companion object {
        const val MIN_SPAN_VOLTS = 0.5f
    }
}

/** [values] with its battery voltage corrected; what the adapter said is kept as [ObdValues.adapterVoltage]. */
fun ObdValues.calibrated(calibration: VoltageCalibration): ObdValues {
    val raw = adapterVoltage ?: voltage ?: return this
    return copy(voltage = calibration.apply(raw), adapterVoltage = raw)
}

/** [state] with every reading it carries calibrated. */
fun ObdState.calibrated(calibration: VoltageCalibration): ObdState = when (this) {
    is ObdState.Connected -> copy(values = values.calibrated(calibration))
    is ObdState.Connecting -> copy(last = last?.calibrated(calibration))
    is ObdState.Problem -> copy(last = last?.calibrated(calibration))
}
