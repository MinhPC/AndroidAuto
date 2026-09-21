package com.minhphan.launcher.sync

import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineOfStateTest {
    @Test
    fun aConnectedAdapterGivesTheReadings() {
        val engine = ObdState.Connected(ObdValues(rpm = 2100, coolantC = 90)).toEngine()
        assertEquals(2100, engine.rpm)
        assertEquals(90, engine.coolantC)
    }

    @Test
    fun aCarThatWentQuietIsZeroRpm() {
        assertEquals(0, ObdState.Connecting(ObdValues(rpm = 800), 15_000, carSilent = true).toEngine().rpm)
        assertEquals(0, ObdState.Problem(ObdProblem.NoVehicle).toEngine().rpm)
    }

    @Test
    fun aLinkThatIsMissingSaysNothingAboutTheEngine() {
        assertNull(ObdState.Connecting(ObdValues(rpm = 800), 15_000).toEngine().rpm)
        assertNull(ObdState.Problem(ObdProblem.Lost).toEngine().rpm)
        assertNull(ObdState.Problem(ObdProblem.CannotConnect).toEngine().rpm)
    }
}
