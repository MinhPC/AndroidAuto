package com.minhphan.launcher.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoltageCalibrationTest {
    private val calibration = VoltageCalibration()

    @Test
    fun theTwoMeasuredReadingsGiveTheMultimetersVoltage() {
        assertEquals(12.78f, calibration.apply(11.4f), 0.001f)
        assertEquals(14.78f, calibration.apply(13.1f), 0.001f)
    }

    @Test
    fun readingsInBetweenAndBeyondFollowTheLine() {
        assertEquals(13.72f, calibration.apply(12.2f), 0.011f)
        assertEquals(12.66f, calibration.apply(11.3f), 0.001f)
    }

    @Test
    fun switchedOffLeavesTheAdaptersVoltage() {
        assertEquals(13.1f, calibration.copy(enabled = false).apply(13.1f), 0f)
    }

    @Test
    fun readingsTooCloseTogetherOnlyShift() {
        val close = VoltageCalibration(offAdapter = 12.0f, offReal = 12.5f, runningAdapter = 12.2f, runningReal = 12.7f)
        assertEquals(13.5f, close.apply(13.0f), 0.001f)
    }

    @Test
    fun theStateKeepsWhatTheAdapterSaidAndIsNotCorrectedTwice() {
        val once = ObdState.Connected(ObdValues(voltage = 13.1f)).calibrated(calibration) as ObdState.Connected
        assertEquals(14.78f, once.values.voltage!!, 0.001f)
        assertEquals(13.1f, once.values.adapterVoltage!!, 0f)
        val twice = once.calibrated(calibration) as ObdState.Connected
        assertEquals(14.78f, twice.values.voltage!!, 0.001f)
    }

    @Test
    fun noVoltageStaysNone() {
        assertNull(ObdValues(rpm = 800).calibrated(calibration).voltage)
    }
}
