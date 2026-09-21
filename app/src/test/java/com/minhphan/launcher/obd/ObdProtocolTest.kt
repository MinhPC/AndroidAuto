package com.minhphan.launcher.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObdProtocolTest {
    @Test
    fun rpmIsQuarterRpmUnitsInTwoBytes() {
        assertEquals(1726, parsePid("41 0C 1A F8", Pid.Rpm))
        assertEquals(750, parsePid("410C0BB8", Pid.Rpm))
    }

    @Test
    fun speedIsOneByteInKmh() {
        assertEquals(60, parsePid("41 0D 3C", Pid.Speed))
        assertEquals(0, parsePid("41 0D 00", Pid.Speed))
    }

    @Test
    fun temperaturesAreOffsetByForty() {
        assertEquals(83, parsePid("41 05 7B", Pid.Coolant))
        assertEquals(-10, parsePid("41 5C 1E", Pid.Oil))
    }

    @Test
    fun percentagesAreScaledFromTwoFiftyFive() {
        assertEquals(50, parsePid("41 2F 80", Pid.Fuel))
        assertEquals(100, parsePid("41 11 FF", Pid.Throttle))
        assertEquals(0, parsePid("41 04 00", Pid.Load))
    }

    @Test
    fun answerIsFoundAmongTheAdaptersOtherLines() {
        assertEquals(750, parsePid("SEARCHING...\r41 0C 0B B8\r", Pid.Rpm))
        assertEquals(750, parsePid("\r\n41 0C 0B B8\r\n\r\n", Pid.Rpm))
    }

    @Test
    fun answerToAnotherValueIsNotMistakenForThisOne() {
        assertNull(parsePid("41 0D 3C", Pid.Rpm))
    }

    @Test
    fun noAnswerAndErrorsGiveNull() {
        assertNull(parsePid("NO DATA", Pid.Rpm))
        assertNull(parsePid("UNABLE TO CONNECT", Pid.Speed))
        assertNull(parsePid("?", Pid.Speed))
        assertNull(parsePid("", Pid.Speed))
    }

    @Test
    fun truncatedAnswerIsRejected() {
        assertNull(parsePid("41 0C 1A", Pid.Rpm))
        assertNull(parsePid("41 0C 1A ZZ", Pid.Rpm))
    }

    @Test
    fun supportedValuesAnswerIsFourBytes() {
        assertNotNull(parsePidResponse("41 00 BE 3F A8 13", 0x00, 4))
        assertNull(parsePidResponse("NO DATA", 0x00, 4))
    }

    @Test
    fun voltageIsReadFromTheAdaptersAnswer() {
        assertEquals(12.4f, parseVoltage("12.4V")!!, 0.001f)
        assertEquals(14f, parseVoltage("ATRV\r14V\r")!!, 0.001f)
        assertEquals(12.45f, parseVoltage("12.45V")!!, 0.001f)
        assertNull(parseVoltage("?"))
    }

    @Test
    fun withReplacesOnlyTheNamedValue() {
        val values = ObdValues(speedKmh = 10, rpm = 900).with(Pid.Speed, 20).with(Pid.Coolant, 88)
        assertEquals(ObdValues(speedKmh = 20, rpm = 900, coolantC = 88), values)
    }

    @Test
    fun autoPicksAPairedDeviceNamedLikeAnAdapter() {
        val phone = ObdDevice("Pixel Buds", "AA:AA")
        val icar = ObdDevice("Vgate iCar Pro", "BB:BB")
        assertEquals(icar, chooseAdapter(listOf(phone, icar), AUTO_ADDRESS))
        assertNull(chooseAdapter(listOf(phone), AUTO_ADDRESS))
        assertEquals(phone, chooseAdapter(listOf(phone, icar), "AA:AA"))
        assertNull(chooseAdapter(listOf(icar), "CC:CC"))
    }

    @Test
    fun autoDoesNotPickADeviceThatOnlyContainsAnAdapterName() {
        val helmet = ObdDevice("Helmet intercom", "AA:AA")
        val elm = ObdDevice("ELM327", "BB:BB")
        assertEquals(elm, chooseAdapter(listOf(helmet, elm), AUTO_ADDRESS))
        assertNull(chooseAdapter(listOf(helmet), AUTO_ADDRESS))
    }

    @Test
    fun recentValuesAreKeptForAWhileAndThenForgotten() {
        var clock = 1_000L
        val recent = RecentValues(staleAfterMs = 15_000L) { clock }
        assertNull(recent.get())

        val reading = ObdValues(speedKmh = 80)
        recent.record(reading)
        clock += 14_999L
        assertEquals(reading, recent.get())
        clock += 1L
        assertNull(recent.get())
    }

    @Test
    fun recentValuesSayHowLongTheyStillLast() {
        var clock = 0L
        val recent = RecentValues(staleAfterMs = 15_000L) { clock }
        assertEquals(0L, recent.remainingMs())
        recent.record(ObdValues(speedKmh = 80))
        clock += 4_000L
        assertEquals(11_000L, recent.remainingMs())
        clock += 11_000L
        assertEquals(0L, recent.remainingMs())
    }

    @Test
    fun aNewerReadingReplacesTheOldAndRestartsTheClock() {
        var clock = 0L
        val recent = RecentValues(staleAfterMs = 10_000L) { clock }
        recent.record(ObdValues(speedKmh = 10))
        clock += 9_000L
        recent.record(ObdValues(speedKmh = 20))
        clock += 9_000L
        assertEquals(ObdValues(speedKmh = 20), recent.get())
    }
}
