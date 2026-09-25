package com.minhphan.launcher.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(25, parsePid("41 0F 41", Pid.Intake))
    }

    @Test
    fun aSupportBitmapListsThePidsWhoseBitsAreSet() {
        // BE 3F A8 13: bit 1 is PID 01, the last bit is PID 20, which says the next block exists.
        val pids = decodeSupported(0x00, intArrayOf(0xBE, 0x3F, 0xA8, 0x13))
        assertTrue(listOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x20).all { it in pids })
        assertTrue(listOf(0x02, 0x08, 0x0A, 0x12, 0x2F).none { it in pids })

        assertEquals(setOf(0x55), decodeSupported(0x40, intArrayOf(0x00, 0x00, 0x08, 0x00)))
    }

    @Test
    fun everyEcuThatAnswersCounts() {
        val lines = parseAllPidResponses("41 00 BE 3F A8 13\r41 00 08 00 00 00\rNO DATA", 0x00, 4)
        assertEquals(2, lines.size)
        assertTrue(0x0D in decodeSupported(0x00, lines[0]))
        assertTrue(0x05 in decodeSupported(0x00, lines[1])) // the second ECU's only PID
    }

    @Test
    fun fuelTrimIsZeroAtOneTwentyEightAndScaledFromThere() {
        assertEquals(0, parsePid("41 07 80", Pid.FuelTrim))
        assertEquals(20, parsePid("41 07 99", Pid.FuelTrim))
        assertEquals(-10, parsePid("41 07 73", Pid.FuelTrim))
        assertEquals(-100, parsePid("41 07 00", Pid.FuelTrim))
    }

    @Test
    fun percentagesAreScaledFromTwoFiftyFive() {
        assertEquals(100, parsePid("41 11 FF", Pid.Throttle))
        assertEquals(0, parsePid("41 04 00", Pid.Load))
    }

    @Test
    fun theOtherValuesDecodeToTheirUnits() {
        assertEquals(35, parsePid("41 0B 23", Pid.Map)) // kPa as it is
        assertEquals(101, parsePid("41 33 65", Pid.Baro))
        assertEquals(16, parsePid("41 0E A0", Pid.Timing)) // 160 / 2 - 64 degrees
        assertEquals(-6, parsePid("41 0E 74", Pid.Timing))
        assertEquals(12, parsePid("41 10 04 B0", Pid.Maf)) // 1200 / 100 g/s
        assertEquals(31, parsePid("41 46 47", Pid.Ambient))
        assertEquals(90, parsePid("41 5C 82", Pid.Oil))
        assertEquals(62, parsePid("41 2F 9E", Pid.FuelLevel))
        assertEquals(-3, parsePid("41 06 7C", Pid.ShortTrim))
    }

    @Test
    fun theCarScannerValuesDecodeToTheirUnits() {
        assertEquals(3, parsePid("41 08 84", Pid.ShortTrim2))
        assertEquals(300, parsePid("41 0A 64", Pid.FuelPressure)) // 3 kPa steps
        assertEquals(750, parsePid("41 1F 02 EE", Pid.RunTime)) // seconds
        assertEquals(1200, parsePid("41 23 04 B0", Pid.RailPressure)) // 10 kPa steps, 120 bar
        assertEquals(40, parsePid("41 2C 66", Pid.Egr))
        assertEquals(12, parsePid("41 30 0C", Pid.WarmUps))
        assertEquals(4660, parsePid("41 31 12 34", Pid.ClearedDistance))
        assertEquals(560, parsePid("41 3C 17 70", Pid.Catalyst)) // 6000 / 10 - 40
        assertEquals(14200, parsePid("41 42 37 78", Pid.ModuleVoltage)) // millivolts
        assertEquals(120, parsePid("41 43 01 32", Pid.AbsoluteLoad)) // can pass 100 %
        assertEquals(32768, parsePid("41 44 80 00", Pid.Lambda)) // lambda 1
        assertEquals(64, parsePid("41 5E 00 40", Pid.FuelRate)) // 1/20 L/h, 3.2 L/h
        assertEquals(-25, parsePid("41 62 64", Pid.Torque))
        assertEquals(350, parsePid("41 63 01 5E", Pid.ReferenceTorque))
    }

    @Test
    fun theStepGivesTheReadingItsDecimals() {
        fun reading(field: ObdField, answer: String) =
            ObdValues().with(field.pid!!, parsePid(answer, field.pid!!)).reading(field)!!
        assertEquals(14.2f, reading(ObdField.MODULE_VOLTAGE, "41 42 37 78"), 0.001f)
        assertEquals(1f, reading(ObdField.LAMBDA, "41 44 80 00"), 0.0001f)
        assertEquals(3.2f, reading(ObdField.FUEL_RATE, "41 5E 00 40"), 0.001f)
        assertEquals(120f, reading(ObdField.RAIL_PRESSURE, "41 23 04 B0"), 0.01f)
        assertEquals(12.5f, reading(ObdField.RUN_TIME, "41 1F 02 EE"), 0.01f)
    }

    @Test
    fun theNamesCoverTheOxygenSensorRanges() {
        assertEquals("Oxygen sensor 1 voltage", obdPidName(0x14))
        assertEquals("Oxygen sensor 8 lambda", obdPidName(0x2B))
        assertEquals("Actual engine torque", obdPidName(0x62))
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
